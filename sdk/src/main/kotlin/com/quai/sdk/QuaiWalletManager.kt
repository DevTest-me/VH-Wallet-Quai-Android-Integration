package com.quai.sdk

import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Credentials
import org.web3j.crypto.MnemonicUtils
import java.util.logging.Logger

/**
 * QuaiWalletManager
 *
 * Handles Quai Network address generation and validation for Android/Kotlin apps.
 *
 * Key concepts:
 * - Quai uses BIP-44 derivation path: m/44'/994'/accountIndex'/0/changeIndex
 * - Only Cyprus1 zone addresses are valid for QUAI ledger (prefix 0x000–0x003)
 * - Addresses with prefix 0x004+ belong to the QI ledger — these must be rejected
 * - You must scan multiple change indices until a valid Cyprus1 address is found
 *
 * Dependencies required in build.gradle:
 *   implementation 'org.web3j:core:4.9.4'
 *   implementation 'org.bitcoinj:bitcoinj-core:0.15.10'
 */
object QuaiWalletManager {

    private val logger = Logger.getLogger(QuaiWalletManager::class.java.name)

    // BIP-44 coin type for Quai Network
    private const val QUAI_COIN_TYPE = 994

    // Cyprus1 zone: valid QUAI addresses have first 3 hex chars with value 0x000–0x003
    private const val CYPRUS1_MAX_PREFIX = 0x003

    // Maximum derivation attempts before giving up
    private const val MAX_DERIVATION_ATTEMPTS = 10_000

    /**
     * Generate a valid Cyprus1 QUAI address for the given account index.
     *
     * Because Quai address validity depends on the derived address prefix,
     * we iterate over the change index until we find a valid Cyprus1 address.
     *
     * @param mnemonic BIP-39 mnemonic phrase (space-separated words)
     * @param accountIndex HD wallet account index (0, 1, 2, ...)
     * @return QuaiAccount containing the valid address and its private key hex
     * @throws IllegalStateException if no valid address found within MAX_DERIVATION_ATTEMPTS
     */
    fun generateAddress(mnemonic: String, accountIndex: Int): QuaiAccount {
        logger.info("Generating Quai address for account index $accountIndex...")

        val seed = MnemonicUtils.generateSeed(mnemonic, "")
        val masterKeypair = Bip32ECKeyPair.generateKeyPair(seed)

        for (changeIndex in 0 until MAX_DERIVATION_ATTEMPTS) {
            val path = parseDerivationPath("m/44'/$QUAI_COIN_TYPE'/$accountIndex'/0/$changeIndex")
            val keypair = Bip32ECKeyPair.deriveKeyPair(masterKeypair, path)
            val credentials = Credentials.create(keypair)
            val address = credentials.address.lowercase()

            if (isValidCyprus1Address(address)) {
                val privateKeyHex = "0x" + keypair.privateKey.toString(16).padStart(64, '0')
                logger.info("Valid Cyprus1 QUAI address found: $address at change index $changeIndex")
                return QuaiAccount(
                    address = org.web3j.crypto.Keys.toChecksumAddress(address),
                    privateKeyHex = privateKeyHex,
                    accountIndex = accountIndex,
                    changeIndex = changeIndex
                )
            }

            if (changeIndex % 500 == 0 && changeIndex > 0) {
                logger.info("Still searching... attempt $changeIndex")
            }
        }

        throw IllegalStateException(
            "No valid Cyprus1 QUAI address found for account $accountIndex after $MAX_DERIVATION_ATTEMPTS attempts"
        )
    }

    /**
     * Validate whether an address is a valid Cyprus1 QUAI address.
     *
     * Rules:
     * - Must match standard EVM address format: 0x + 40 hex chars
     * - First 3 hex chars (12 bits) must have integer value 0–3 (0x000–0x003)
     * - Values 0x004 and above indicate QI ledger addresses — these are INVALID for QUAI
     *
     * @param address Ethereum-style hex address string
     * @return true if valid Cyprus1 QUAI address
     */
    fun isValidCyprus1Address(address: String): Boolean {
        if (!address.matches(Regex("^0x[0-9a-fA-F]{40}$"))) return false
        val prefix = address.substring(2, 5).lowercase()
        val value = prefix.toIntOrNull(16) ?: return false
        return value in 0..CYPRUS1_MAX_PREFIX
    }

    /**
     * Recover a Quai account from mnemonic by scanning derivation paths.
     * Useful for wallet restore flows.
     *
     * @param mnemonic BIP-39 mnemonic phrase
     * @param accountIndex HD wallet account index
     * @return QuaiAccount if found
     */
    fun recoverAccount(mnemonic: String, accountIndex: Int): QuaiAccount {
        logger.info("Recovering Quai account for index $accountIndex...")
        return generateAddress(mnemonic, accountIndex)
    }

    /**
     * Derive a private key hex from mnemonic for a known address.
     * Scans change indices until the address matches.
     *
     * @param mnemonic BIP-39 mnemonic phrase
     * @param accountIndex HD wallet account index
     * @param targetAddress The known Quai address to match
     * @return Private key as hex string, or null if not found
     */
    fun derivePrivateKey(mnemonic: String, accountIndex: Int, targetAddress: String): String? {
        val seed = MnemonicUtils.generateSeed(mnemonic, "")
        val masterKeypair = Bip32ECKeyPair.generateKeyPair(seed)
        val normalizedTarget = targetAddress.lowercase()

        for (changeIndex in 0 until MAX_DERIVATION_ATTEMPTS) {
            val path = parseDerivationPath("m/44'/$QUAI_COIN_TYPE'/$accountIndex'/0/$changeIndex")
            val keypair = Bip32ECKeyPair.deriveKeyPair(masterKeypair, path)
            val credentials = Credentials.create(keypair)

            if (credentials.address.lowercase() == normalizedTarget) {
                return "0x" + keypair.privateKey.toString(16).padStart(64, '0')
            }
        }

        logger.warning("Could not find private key for address $targetAddress")
        return null
    }

    // Parse BIP-44 derivation path string to IntArray for web3j
    internal fun parseDerivationPath(path: String): IntArray {
        return path.split("/").drop(1)
            .map { it.replace("'", "") }
            .map { it.toInt() }
            .toIntArray()
    }
}

/**
 * Represents a derived Quai account.
 *
 * @param address Checksummed Cyprus1 QUAI address (0x prefixed)
 * @param privateKeyHex Raw private key as hex string (0x prefixed) — store securely!
 * @param accountIndex BIP-44 account index used for derivation
 * @param changeIndex Change index where the valid address was found
 */
data class QuaiAccount(
    val address: String,
    val privateKeyHex: String,
    val accountIndex: Int,
    val changeIndex: Int
)