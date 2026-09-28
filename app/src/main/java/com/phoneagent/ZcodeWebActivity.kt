package com.phoneagent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

/** Third launcher icon: the official ZCode web UI served from the device itself. */
class ZcodeWebActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var loading: View
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zcode_web)
        webView = findViewById(R.id.webview)
        loading = findViewById(R.id.loading)
        errorBox = findViewById(R.id.error)
        errorText = findViewById(R.id.error_text)
        findViewById<Button>(R.id.retry).setOnClickListener { connect() }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        connect()
    }

    private fun connect() {
        loading.visibility = View.VISIBLE
        errorBox.visibility = View.GONE
        webView.visibility = View.GONE

        startForegroundService(Intent(this, ZcodeWebService::class.java))

        thread(name = "zcode-web-connect") {
            val deadline = System.currentTimeMillis() + 160_000
            while (System.currentTimeMillis() < deadline) {
                if (ZcodeWebState.ready) break
                if (ZcodeWebState.lastError != null) break
                Thread.sleep(500)
            }
            runOnUiThread { showState() }
        }
    }

    private fun showState() {
        loading.visibility = View.GONE
        if (ZcodeWebState.ready) {
            errorBox.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl("http://127.0.0.1:${ZcodeWebState.port}/")
        } else {
            errorText.text = ZcodeWebState.lastError ?: getString(R.string.dsh_error)
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
