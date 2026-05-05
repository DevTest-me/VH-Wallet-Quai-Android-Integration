package com.quai.sdk

import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * QuaiJavaScriptBridge
 *
 * Alternative transaction sender that uses a WebView + quais.js SDK directly.
 *
 * Use this as a fallback or alternative to QuaiTransactionSender if you prefer
 * to avoid the external signing service and run quais.js natively on-device.
 *
 * Trade-offs vs signing service:
 * - ✅ No external service dependency
 * - ✅ Private key never leaves the device
 * - ⚠️  Requires WebView (heavier)
 * - ⚠️  Slower to initialize (~15s first load)
 * - ⚠️  Requires internet to load quais.js from CDN
 *
 * Usage:
 *   val bridge = QuaiJavaScriptBridge(context)
 *   bridge.initialize()  // Call once, on main thread
 *   val txHash = bridge.sendTransaction(privateKey, toAddress, amount, rpcUrl)
 *   bridge.cleanup()
 *
 * Dependencies required in build.gradle:
 *   No additional dependencies needed beyond Android SDK
 */
class QuaiJavaScriptBridge(private val context: Context) {

    private lateinit var webView: WebView
    private var isInitialized = false
    private val initLatch = CountDownLatch(1)
    private val logger = Logger.getLogger(QuaiJavaScriptBridge::class.java.name)

    /**
     * Initialize the WebView and load the quais.js SDK.
     * Must be called on the main thread before any transaction.
     */
    fun initialize() {
        logger.info("QUAI-JS → Initializing WebView...")

        webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true

        WebView.setWebContentsDebuggingEnabled(true)

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun sendResult(result: String) {
                logger.info("QUAI-JS → Result received: $result")
                resultCallback?.invoke(result)
            }

            @JavascriptInterface
            fun sendError(error: String) {
                logger.severe("QUAI-JS → Error received: $error")
                errorCallback?.invoke(error)
            }

            @JavascriptInterface
            fun log(message: String) {
                logger.info("QUAI-JS → $message")
            }
        }, "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val level = when (it.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                        ConsoleMessage.MessageLevel.WARNING -> "WARN"
                        else -> "INFO"
                    }
                    logger.info("QUAI-JS-CONSOLE [$level]: ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                logger.info("QUAI-JS → WebView page loaded successfully")
                isInitialized = true
                initLatch.countDown()
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                logger.severe("QUAI-JS → WebView error: $description (code: $errorCode)")
            }
        }

        logger.info("QUAI-JS → Loading quais.js SDK from CDN...")
        webView.loadDataWithBaseURL(
            "https://cdn.jsdelivr.net",
            QUAI_HTML_TEMPLATE,
            "text/html",
            "UTF-8",
            null
        )
    }

    private var resultCallback: ((String) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    /**
     * Send a QUAI transaction using the quais.js SDK inside a WebView.
     *
     * @param privateKey Raw private key hex (0x prefixed)
     * @param toAddress Recipient Cyprus1 QUAI address
     * @param amountInQuai Amount in QUAI (not Wei)
     * @param rpcUrl Quai Network RPC endpoint
     * @return Transaction hash on success
     */
    suspend fun sendTransaction(
        privateKey: String,
        toAddress: String,
        amountInQuai: Double,
        rpcUrl: String
    ): String = suspendCancellableCoroutine { continuation ->

        logger.info("QUAI-JS → Waiting for WebView initialization...")

        if (!initLatch.await(15, TimeUnit.SECONDS)) {
            logger.severe("QUAI-JS → WebView initialization timeout (15s)")
            continuation.resumeWithException(
                IllegalStateException("WebView initialization timeout after 15 seconds")
            )
            return@suspendCancellableCoroutine
        }

        logger.info("QUAI-JS → WebView initialized, preparing transaction...")

        resultCallback = { result ->
            logger.info("QUAI-JS → Transaction completed: $result")
            continuation.resume(result)
            resultCallback = null
            errorCallback = null
        }

        errorCallback = { error ->
            logger.severe("QUAI-JS → Transaction failed: $error")
            continuation.resumeWithException(Exception(error))
            resultCallback = null
            errorCallback = null
        }

        val jsCode = """
            (async function() {
                try {
                    AndroidBridge.log('Step 1: Creating provider with RPC: $rpcUrl');
                    const provider = new quais.JsonRpcProvider('$rpcUrl', undefined, { usePathing: true });

                    AndroidBridge.log('Step 2: Creating wallet from private key');
                    const wallet = new quais.Wallet('$privateKey', provider);

                    AndroidBridge.log('Step 3: Wallet address: ' + wallet.address);

                    AndroidBridge.log('Step 4: Checking balance...');
                    const balance = await wallet.getBalance();
                    AndroidBridge.log('Step 5: Balance: ' + quais.formatQuai(balance) + ' QUAI');

                    AndroidBridge.log('Step 6: Preparing transaction to $toAddress');
                    const txRequest = {
                        to: '$toAddress',
                        value: quais.parseQuai('$amountInQuai')
                    };

                    AndroidBridge.log('Step 7: Sending transaction...');
                    const tx = await wallet.sendTransaction(txRequest);
                    AndroidBridge.log('Step 8: Transaction hash: ' + tx.hash);

                    AndroidBridge.log('Step 9: Waiting for confirmation...');
                    const receipt = await tx.wait();
                    AndroidBridge.log('Step 10: Confirmed in block ' + receipt.blockNumber);

                    AndroidBridge.sendResult(receipt.hash);
                } catch (error) {
                    AndroidBridge.log('Error: ' + error.message);
                    AndroidBridge.sendError(error.message || error.toString());
                }
            })();
        """.trimIndent()

        logger.info("QUAI-JS → Executing JavaScript...")
        webView.post {
            webView.evaluateJavascript(jsCode) { result ->
                logger.info("QUAI-JS → JS evaluation result: $result")
            }
        }
    }

    /**
     * Release WebView resources. Call when done with all transactions.
     */
    fun cleanup() {
        logger.info("QUAI-JS → Cleaning up WebView")
        webView.destroy()
    }

    companion object {
        private const val QUAI_HTML_TEMPLATE = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <script src="https://cdn.jsdelivr.net/npm/quais@1.0.0-alpha.15/dist/quais.umd.js"></script>
            </head>
            <body>
                <script>
                    console.log('Quais SDK loaded:', typeof quais !== 'undefined');
                    if (typeof AndroidBridge !== 'undefined') {
                        AndroidBridge.log('Quais SDK successfully loaded');
                    }
                </script>
            </body>
            </html>
        """
    }
}