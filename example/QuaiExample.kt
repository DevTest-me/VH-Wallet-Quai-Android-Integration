package com.quai.sdk.example

import com.quai.sdk.QuaiTransactionScanner
import com.quai.sdk.QuaiTransactionSender
import com.quai.sdk.QuaiWalletManager
import kotlinx.coroutines.runBlocking

/**
 * QuaiExample.kt
 *
 * Demonstrates how to use the Quai Network Kotlin SDK.
 * Copy the relevant snippets into your Android project.
 */
object QuaiExample {

    // 1. ADDRESS GENERATION

    fun exampleGenerateAddress() {
        val mnemonic = "your twelve word mnemonic phrase goes here replace this now"

        // Generate a valid Cyprus1 QUAI address for account index 0
        // The SDK scans change indices automatically until a valid address is found
        val account = QuaiWalletManager.generateAddress(mnemonic, accountIndex = 0)

        println("Address:      ${account.address}")       // 0x000... (Cyprus1)
        println("Account idx:  ${account.accountIndex}")  // 0
        println("Change idx:   ${account.changeIndex}")   // Could be 0, 47, 312... depends on mnemonic
        // ⚠️ Never log privateKeyHex in production
    }

    // 2. ADDRESS VALIDATION

    fun exampleValidateAddress() {
        val validAddress   = "0x001f2e4a..."  // prefix 0x001 → valid Cyprus1 QUAI
        val qiAddress      = "0x00a7b3c..."  // prefix 0x00a = 10 → QI ledger, INVALID for QUAI
        val wrongFormat    = "0xABCDEF"      // too short → invalid

        println(QuaiWalletManager.isValidCyprus1Address(validAddress))  // true
        println(QuaiWalletManager.isValidCyprus1Address(qiAddress))     // false
        println(QuaiWalletManager.isValidCyprus1Address(wrongFormat))   // false

        // Why does this matter?
        // Quai Network uses the address prefix to determine which ledger (QUAI vs QI)
        // and which zone (Cyprus1, Cyprus2, etc.) an address belongs to.
        // Sending to or from a wrong-prefix address will result in a failed transaction.
    }

    // 3. SENDING A TRANSACTION (via signing service)

    fun exampleSendTransaction() = runBlocking {
        val mnemonic   = "your twelve word mnemonic phrase goes here replace this now"
        val toAddress  = "0x0023abcd..."  // recipient Cyprus1 address
        val amount     = 1.5             // QUAI (not Wei)

        val sender = QuaiTransactionSender(isTestnet = false)

        try {
            val txHash = sender.sendTransaction(
                mnemonic      = mnemonic,
                accountIndex  = 0,
                toAddress     = toAddress,
                amountInQuai  = amount
            )
            println("✅ Transaction sent: $txHash")
            println("🔍 Explorer: ${QuaiTransactionSender.EXPLORER_MAINNET}/tx/$txHash")
        } catch (e: Exception) {
            println("❌ Transaction failed: ${e.message}")
        }
    }

    // 4. FIRST TRANSACTION WITH MEMO TAG

    fun exampleSendWithMemo() = runBlocking {
        val mnemonic  = "your twelve word mnemonic phrase goes here replace this now"
        val toAddress = "0x0001beef..."
        val amount    = 0.5

        // Encode your memo as hex (e.g. "my-app-tag" → hex bytes)
        val memoText = "my-app-tag"
        val memoHex  = "0x" + memoText.toByteArray().joinToString("") { "%02x".format(it) }

        val sender = QuaiTransactionSender(isTestnet = false)
        val txHash = sender.sendTransaction(
            mnemonic     = mnemonic,
            accountIndex = 0,
            toAddress    = toAddress,
            amountInQuai = amount,
            memoHex      = memoHex  // Optional memo in data field
        )
        println("✅ Sent with memo: $txHash")
    }

    // 5. ESTIMATE GAS FEE

    fun exampleEstimateGas() = runBlocking {
        val sender = QuaiTransactionSender(isTestnet = false)
        val gasFee = sender.estimateGasFee()
        println("Estimated gas fee: $gasFee QUAI")
    }

    // 6. FETCH RECEIVED TRANSACTIONS

    fun exampleFetchTransactions() = runBlocking {
        val address = "0x000abc..."  // A valid Cyprus1 address

        val scanner = QuaiTransactionScanner(isTestnet = false)
        val transactions = scanner.fetchReceivedTransactions(address, limit = 10)

        transactions.forEach { tx ->
            println("Hash:    ${tx.hash}")
            println("From:    ${tx.fromAddress}")
            println("Amount:  ${tx.amountInQuai} QUAI")
            println("Date:    ${java.util.Date(tx.timestampMs)}")
            println("Explorer: ${tx.explorerUrl}")
            println("──")
        }
    }

    // 7. FETCH BALANCE

    fun exampleFetchBalance() = runBlocking {
        val address = "0x000abc..."
        val scanner = QuaiTransactionScanner(isTestnet = false)
        val balance = scanner.fetchBalance(address)
        println("Balance: $balance QUAI")
    }

    // 8. TESTNET USAGE

    fun exampleTestnet() = runBlocking {
        // Just pass isTestnet = true to any class
        // Testnet: Orchard network (orchard.quaiscan.io)
        val sender  = QuaiTransactionSender(isTestnet = true)
        val scanner = QuaiTransactionScanner(isTestnet = true)

        println("Testnet RPC: ${sender.rpcUrl}")
        // Everything else works the same
    }
}