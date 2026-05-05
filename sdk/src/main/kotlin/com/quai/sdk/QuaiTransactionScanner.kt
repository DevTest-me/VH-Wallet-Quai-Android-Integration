package com.quai.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * QuaiTransactionScanner
 *
 * Fetches received transactions for a Quai Network address using the Quaiscan API.
 *
 * Key implementation notes:
 * - Uses the Quaiscan block explorer API (not RPC)
 * - API endpoint: /api?module=account&action=txlist
 * - Values are returned as DECIMAL strings (not hex) — parse with BigInteger(value, 10)
 * - Timestamps are in SECONDS — multiply by 1000 for milliseconds
 * - Filter by filter_by=to to only get received transactions
 * - Currently only Cyprus1 zone is supported (mainnet: cyprus1.colosseum.quaiscan.io)
 *
 * Dependencies required in build.gradle:
 *   implementation 'com.squareup.okhttp3:okhttp:4.11.0'
 */
class QuaiTransactionScanner(
    private val isTestnet: Boolean = false
) {

    private val logger = Logger.getLogger(QuaiTransactionScanner::class.java.name)

    private val baseUrl: String get() = if (isTestnet) TESTNET_EXPLORER_URL else MAINNET_EXPLORER_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch received transactions for a Quai address.
     *
     * @param address Valid Cyprus1 QUAI address
     * @param limit Number of transactions to fetch (default 10)
     * @return List of QuaiTransaction objects, sorted newest first
     */
    suspend fun fetchReceivedTransactions(
        address: String,
        limit: Int = 10
    ): List<QuaiTransaction> = withContext(Dispatchers.IO) {

        // Validate address format before making API call
        if (!QuaiWalletManager.isValidCyprus1Address(address)) {
            logger.warning("Invalid Quai address format: $address — skipping fetch")
            return@withContext emptyList()
        }

        val apiUrl = "$baseUrl/api?module=account&action=txlist" +
                "&address=$address" +
                "&sort=desc" +
                "&page=1" +
                "&offset=$limit" +
                "&filter_by=to" // Only fetch transactions where our address is the recipient

        logger.info("QUAI Scanner → Fetching from: $apiUrl")

        try {
            val request = Request.Builder().url(apiUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                logger.severe("QUAI Scanner → HTTP error: ${response.code}")
                response.close()
                return@withContext emptyList()
            }

            val body = response.body?.string()
            response.close()

            if (body.isNullOrBlank()) {
                logger.severe("QUAI Scanner → Empty response")
                return@withContext emptyList()
            }

            logger.info("QUAI Scanner → Response (${body.length} chars): ${body.take(300)}")

            return@withContext parseTransactions(body, address)

        } catch (e: Exception) {
            logger.severe("QUAI Scanner → Fetch failed: ${e.message}")
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    /**
     * Fetch the QUAI balance for an address via RPC.
     *
     * @param address Valid Cyprus1 QUAI address
     * @return Balance in QUAI (not Wei), or null on failure
     */
    suspend fun fetchBalance(address: String): Double? = withContext(Dispatchers.IO) {
        val rpcUrl = if (isTestnet)
            QuaiTransactionSender.TESTNET_RPC_URL
        else
            QuaiTransactionSender.MAINNET_RPC_URL

        try {
            val body = """
                {
                    "jsonrpc": "2.0",
                    "method": "quai_getBalance",
                    "params": ["$address", "latest"],
                    "id": 1
                }
            """.trimIndent()

            val request = Request.Builder()
                .url(rpcUrl)
                .post(body.toRequestBody())
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            response.close()

            if (responseBody.isNullOrBlank()) return@withContext null

            // Extract hex result
            val hexRegex = Regex(""""result"\s*:\s*"(0x[0-9a-fA-F]+)"""")
            val hexBalance = hexRegex.find(responseBody)?.groupValues?.get(1) ?: return@withContext null

            val weiBalance = BigInteger(hexBalance.removePrefix("0x"), 16)
            val quaiBalance = BigDecimal(weiBalance)
                .divide(BigDecimal("1000000000000000000"), 18, RoundingMode.DOWN)

            return@withContext quaiBalance.toDouble()

        } catch (e: Exception) {
            logger.severe("QUAI Scanner → Balance fetch failed: ${e.message}")
            return@withContext null
        }
    }

    // Private parsing helpers
    private fun parseTransactions(body: String, ownAddress: String): List<QuaiTransaction> {
        val transactions = mutableListOf<QuaiTransaction>()

        // Check API status
        val statusRegex = Regex(""""status"\s*:\s*"(\d+)"""")
        val status = statusRegex.find(body)?.groupValues?.get(1) ?: "0"

        if (status == "0") {
            val messageRegex = Regex(""""message"\s*:\s*"([^"]+)"""")
            val message = messageRegex.find(body)?.groupValues?.get(1) ?: ""
            logger.info("QUAI Scanner → API status 0: $message")
            return emptyList()
        }

        // Extract result array items (simple approach without full JSON library)
        // Each tx is a JSON object within the result array
        val txPattern = Regex("""\{[^{}]*"transactionHash"[^{}]*\}|\{[^{}]*"hash"[^{}]*\}""")
        val txMatches = txPattern.findAll(body)

        // Better approach: split by transaction boundaries
        val resultStart = body.indexOf(""""result"""")
        if (resultStart == -1) {
            logger.warning("QUAI Scanner → No result array found")
            return emptyList()
        }

        // Use a proper field extractor
        fun extractField(txJson: String, field: String): String? {
            val regex = Regex(""""$field"\s*:\s*"?([^",}\]]+)"?""")
            return regex.find(txJson)?.groupValues?.get(1)?.trim()
        }

        // Split transactions by looking for hash fields
        val txJsonList = extractTransactionJsons(body)

        for (txJson in txJsonList) {
            try {
                val hash = extractField(txJson, "transactionHash")
                    ?: extractField(txJson, "hash")
                    ?: continue

                val from = extractField(txJson, "from") ?: continue
                val to = extractField(txJson, "to") ?: continue

                // Only process if our address is the recipient
                if (to.lowercase() != ownAddress.lowercase()) {
                    logger.info("QUAI Scanner → Skipping tx not to our address")
                    continue
                }

                // IMPORTANT: Value is a DECIMAL string, not hex
                val valueStr = extractField(txJson, "value") ?: "0"
                val amountInQuai = parseWeiDecimalToQuai(valueStr)

                if (amountInQuai <= 0) {
                    logger.info("QUAI Scanner → Skipping zero value tx: $hash")
                    continue
                }

                // IMPORTANT: Timestamp is in SECONDS, convert to milliseconds
                val timestampSeconds = extractField(txJson, "timeStamp")?.toLongOrNull()
                    ?: (System.currentTimeMillis() / 1000)
                val timestampMs = timestampSeconds * 1000L

                transactions.add(
                    QuaiTransaction(
                        hash = hash,
                        fromAddress = from,
                        toAddress = to,
                        amountInQuai = amountInQuai,
                        timestampMs = timestampMs,
                        isSent = false
                    )
                )

                logger.info("QUAI Scanner → Added tx: $hash ($amountInQuai QUAI from $from)")

            } catch (e: Exception) {
                logger.warning("QUAI Scanner → Failed to parse tx: ${e.message}")
            }
        }

        logger.info("QUAI Scanner → Found ${transactions.size} received transactions")
        return transactions
    }

    /**
     * CRITICAL: Quaiscan returns value as a DECIMAL string (not hex).
     * Parse as BigInteger base 10, then divide by 10^18 to get QUAI.
     */
    private fun parseWeiDecimalToQuai(valueStr: String): Double {
        return try {
            if (valueStr.isBlank() || valueStr == "0") return 0.0
            val weiValue = BigInteger(valueStr, 10) // BASE 10, not hex
            val quaiValue = BigDecimal(weiValue)
                .divide(BigDecimal("1000000000000000000"), 18, RoundingMode.DOWN)
            quaiValue.toDouble()
        } catch (e: Exception) {
            logger.warning("QUAI Scanner → Failed to parse value: $valueStr")
            0.0
        }
    }

    private fun extractTransactionJsons(body: String): List<String> {
        val txList = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        var txStart = -1
        var inResultArray = false

        val resultArrayStart = body.indexOf(""""result":[""")
            .takeIf { it != -1 }
            ?: body.indexOf(""""result": [""")
                .takeIf { it != -1 }
            ?: return emptyList()

        var i = resultArrayStart
        while (i < body.length) {
            val c = body[i]

            if (escape) { escape = false; i++; continue }
            if (c == '\\' && inString) { escape = true; i++; continue }
            if (c == '"') { inString = !inString; i++; continue }
            if (inString) { i++; continue }

            if (c == '[' && !inResultArray && i >= resultArrayStart) {
                inResultArray = true
            } else if (inResultArray) {
                if (c == '{') {
                    if (depth == 0) txStart = i
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0 && txStart != -1) {
                        txList.add(body.substring(txStart, i + 1))
                        txStart = -1
                    }
                } else if (c == ']' && depth == 0) {
                    break
                }
            }
            i++
        }

        return txList
    }

    private fun String.toRequestBody() =
        okhttp3.RequestBody.create(
            okhttp3.MediaType.parse("application/json"),
            this
        )

    companion object {
        const val MAINNET_EXPLORER_URL = "https://cyprus1.colosseum.quaiscan.io"
        const val TESTNET_EXPLORER_URL = "https://orchard.quaiscan.io"
    }
}

/**
 * Represents a single Quai Network transaction.
 */
data class QuaiTransaction(
    val hash: String,
    val fromAddress: String,
    val toAddress: String,
    val amountInQuai: Double,
    val timestampMs: Long,
    val isSent: Boolean
) {
    val explorerUrl: String
        get() = "${QuaiTransactionScanner.MAINNET_EXPLORER_URL}/tx/$hash"
}