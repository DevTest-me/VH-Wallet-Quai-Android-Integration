package com.quai.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Credentials
import org.web3j.crypto.MnemonicUtils
import org.web3j.utils.Convert
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * QuaiTransactionSender
 *
 * Handles sending QUAI transactions on the Quai Network.
 *
 * Architecture note:
 * Quai Network uses a custom transaction encoding that is NOT compatible with
 * standard web3j RLP encoding. This SDK solves this by offloading transaction
 * signing to a lightweight Node.js signing service (quai-service on Render).
 *
 * The signing service source code is available at:
 * https://github.com/DevTest-me/quai-service
 *
 * Flow:
 *   1. Derive private key from mnemonic
 *   2. Fetch nonce, gas price, and chain ID from Quai RPC
 *   3. POST transaction params to signing service
 *   4. Signing service encodes + signs using quais.js and broadcasts to network
 *   5. Returns transaction hash
 *
 * Dependencies required in build.gradle:
 *   implementation 'com.squareup.okhttp3:okhttp:4.11.0'
 *   implementation 'org.web3j:core:4.9.4'
 */
class QuaiTransactionSender(
    private val isTestnet: Boolean = false,
    private val signingServiceUrl: String = DEFAULT_SIGNING_SERVICE_URL
) {

    private val logger = Logger.getLogger(QuaiTransactionSender::class.java.name)

    // RPC endpoints
    val rpcUrl: String get() = if (isTestnet) TESTNET_RPC_URL else MAINNET_RPC_URL

    // HTTP client with generous timeouts for Quai Network
    private val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Send a native QUAI transaction.
     *
     * @param mnemonic BIP-39 mnemonic phrase of the sender
     * @param accountIndex HD wallet account index
     * @param toAddress Recipient's Cyprus1 QUAI address
     * @param amountInQuai Amount to send in QUAI (not Wei)
     * @param memoHex Optional hex-encoded data field (e.g. for tagging first transactions)
     * @return Transaction hash on success
     * @throws IllegalStateException if transaction fails
     */
    suspend fun sendTransaction(
        mnemonic: String,
        accountIndex: Int,
        toAddress: String,
        amountInQuai: Double,
        memoHex: String? = null
    ): String = withContext(Dispatchers.IO) {

        logger.info("QUAI → Starting transaction: $amountInQuai QUAI to $toAddress")
        logger.info("QUAI → Network: ${if (isTestnet) "Orchard Testnet" else "Colosseum Mainnet"}")

        // Step 1: Derive private key and from address
        val account = QuaiWalletManager.generateAddress(mnemonic, accountIndex)
        val fromAddress = account.address
        val privateKeyHex = account.privateKeyHex

        logger.info("QUAI → From address: $fromAddress")

        // Validate recipient address
        if (!QuaiWalletManager.isValidCyprus1Address(toAddress)) {
            throw IllegalArgumentException(
                "Invalid recipient address: $toAddress\n" +
                "Must be a valid Cyprus1 QUAI address (prefix 0x000–0x003)"
            )
        }

        val checksummedTo = org.web3j.crypto.Keys.toChecksumAddress(toAddress.lowercase())

        // Step 2: Fetch nonce
        val nonce = getNonce(fromAddress)
        logger.info("QUAI → Nonce: $nonce")

        // Step 3: Fetch gas price
        val gasPrice = getGasPrice()
        logger.info("QUAI → Gas price: $gasPrice")

        // Step 4: Fetch chain ID
        val chainId = getChainId()
        logger.info("QUAI → Chain ID: $chainId")

        // Step 5: Calculate amount in Wei
        val amountInWei = Convert.toWei(amountInQuai.toString(), Convert.Unit.ETHER).toBigInteger()

        // Step 6: Determine gas limit (higher if memo/data included)
        val gasLimit = if (memoHex != null) BigInteger.valueOf(30_000) else BigInteger.valueOf(21_000)

        // Step 7: Build request body for signing service
        val requestJson = buildSigningRequest(
            privateKeyHex = privateKeyHex,
            toAddress = checksummedTo,
            amountInWei = amountInWei,
            nonce = nonce,
            gasPrice = gasPrice,
            gasLimit = gasLimit,
            chainId = chainId,
            rpcUrl = rpcUrl,
            dataField = memoHex
        )

        logger.info("QUAI → Sending to signing service: $signingServiceUrl")

        // Step 8: Call signing service
        val requestBody = requestJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$signingServiceUrl/sign-transaction")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            response.close()
            throw IllegalStateException("Signing service error (${response.code}): $errorBody")
        }

        val responseBody = response.body?.string() ?: throw IllegalStateException("Empty response from signing service")
        response.close()

        logger.info("QUAI → Signing service response: $responseBody")

        // Step 9: Parse transaction hash
        val txHash = parseTransactionHash(responseBody)
        logger.info("QUAI → ✅ Transaction submitted: $txHash")

        return@withContext txHash
    }

    /**
     * Estimate the gas fee for a QUAI transaction.
     *
     * @return Estimated fee in QUAI
     */
    suspend fun estimateGasFee(): Double = withContext(Dispatchers.IO) {
        return@withContext try {
            val gasPrice = getGasPrice()
            val gasLimit = BigInteger.valueOf(21_000)
            val gasCost = gasPrice.multiply(gasLimit)
            Convert.fromWei(gasCost.toString(), Convert.Unit.ETHER).toDouble()
        } catch (e: Exception) {
            logger.warning("QUAI → Failed to estimate gas: ${e.message}, using fallback")
            0.000021 // Conservative fallback
        }
    }

    // Private RPC helpers
    private fun getNonce(address: String): BigInteger {
        val body = """
            {
                "jsonrpc": "2.0",
                "method": "quai_getTransactionCount",
                "params": ["$address", "latest"],
                "id": 1
            }
        """.trimIndent()

        val response = postRpc(body)
        val hex = response["result"]?.toString()?.trim('"') ?: "0x0"
        return BigInteger(hex.removePrefix("0x"), 16)
    }

    private fun getGasPrice(): BigInteger {
        val body = """
            {
                "jsonrpc": "2.0",
                "method": "quai_gasPrice",
                "params": [],
                "id": 2
            }
        """.trimIndent()

        val response = postRpc(body)
        val hex = response["result"]?.toString()?.trim('"') ?: "0x0"
        return BigInteger(hex.removePrefix("0x"), 16)
    }

    private fun getChainId(): BigInteger {
        val body = """
            {
                "jsonrpc": "2.0",
                "method": "quai_chainId",
                "params": [],
                "id": 3
            }
        """.trimIndent()

        val response = postRpc(body)
        val hex = response["result"]?.toString()?.trim('"') ?: "0x2328"
        return BigInteger(hex.removePrefix("0x"), 16)
    }

    private fun postRpc(body: String): Map<String, Any?> {
        val request = Request.Builder()
            .url(rpcUrl)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IllegalStateException("Empty RPC response")
        response.close()

        // Simple JSON parsing without pulling in a full JSON library dependency
        return parseSimpleJson(responseBody)
    }

    private fun buildSigningRequest(
        privateKeyHex: String,
        toAddress: String,
        amountInWei: BigInteger,
        nonce: BigInteger,
        gasPrice: BigInteger,
        gasLimit: BigInteger,
        chainId: BigInteger,
        rpcUrl: String,
        dataField: String?
    ): String {
        val dataEntry = if (dataField != null) """"data": "$dataField",""" else ""
        return """
{
    "privateKey": "$privateKeyHex",
    "to": "$toAddress",
    "value": "$amountInWei",
    "nonce": "$nonce",
    "gasPrice": "$gasPrice",
    "gasLimit": "$gasLimit",
    "chainId": "$chainId",
    "rpcUrl": "$rpcUrl"${if (dataField != null) """,
    "data": "$dataField"""" else ""}
}
        """.trimIndent()
    }

    private fun parseTransactionHash(responseBody: String): String {
        // Extract txHash from response JSON
        val txHashRegex = Regex(""""txHash"\s*:\s*"([^"]+)"""")
        val match = txHashRegex.find(responseBody)
            ?: throw IllegalStateException("No txHash in response: $responseBody")

        // Check for success flag
        if (responseBody.contains(""""success":false""") || responseBody.contains(""""success": false""")) {
            val errorRegex = Regex(""""error"\s*:\s*"([^"]+)"""")
            val error = errorRegex.find(responseBody)?.groupValues?.get(1) ?: "Unknown error"
            throw IllegalStateException("Transaction failed: $error")
        }

        return match.groupValues[1]
    }

    // Minimal JSON parser for RPC responses (avoids extra dependency)
    private fun parseSimpleJson(json: String): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val resultRegex = Regex(""""result"\s*:\s*"?([^",}\]]+)"?""")
        val errorRegex = Regex(""""error"\s*:\s*\{([^}]+)}""")

        resultRegex.find(json)?.let { result["result"] = it.groupValues[1].trim() }
        errorRegex.find(json)?.let { result["error"] = it.groupValues[1].trim() }

        return result
    }

    companion object {
        // Replace with your own signing service URL if self-hosting
        const val DEFAULT_SIGNING_SERVICE_URL = "https://quai-service.onrender.com"

        const val MAINNET_RPC_URL = "https://rpc.quai.network/cyprus1"
        const val TESTNET_RPC_URL = "https://rpc.orchard.quai.network/cyprus1"

        const val EXPLORER_MAINNET = "https://cyprus1.colosseum.quaiscan.io"
        const val EXPLORER_TESTNET = "https://orchard.quaiscan.io"
    }
}