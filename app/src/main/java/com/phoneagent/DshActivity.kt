package com.phoneagent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

/** DeepSeek Harness launcher icon target: foreground service + fullscreen WebView. */
class DshActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var loading: View
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dsh)
        webView = findViewById(R.id.webview)
        loading = findViewById(R.id.loading)
        errorBox = findViewById(R.id.error)
        errorText = findViewById(R.id.error_text)
        findViewById<Button>(R.id.retry).setOnClickListener { connect() }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (req?.isForMainFrame == true) {
                    errorText.text = err?.description ?: getString(R.string.dsh_error)
                }
            }
        }
        connect()
    }

    private fun connect() {
        loading.visibility = View.VISIBLE
        errorBox.visibility = View.GONE
        webView.visibility = View.GONE

        startForegroundService(Intent(this, DshService::class.java))

        thread(name = "dsh-connect") {
            val deadline = System.currentTimeMillis() + 160_000
            while (System.currentTimeMillis() < deadline) {
                if (DshState.serverReady) break
                if (DshState.lastError != null) {
                    runOnUiThread { showState() }
                    return@thread
                }
                Thread.sleep(500)
            }
            runOnUiThread { showState() }
        }
    }

    private fun showState() {
        loading.visibility = View.GONE
        if (DshState.serverReady) {
            errorBox.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(DshState.startUrl ?: "http://127.0.0.1:3080")
        } else {
            errorText.text = DshState.lastError ?: getString(R.string.dsh_error)
            errorBox.visibility = View.VISIBLE
            webView.visibility = View.GONE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
