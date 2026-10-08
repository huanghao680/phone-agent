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

        val webScale = Prefs.webUiScale(this)
        WebUi.apply(webView, webScale)
        AttachmentBridge.attach(this, webView)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(WebUi.patchJs(webScale) +
                    """
                    (function(){
                      var s=document.createElement('style');
                      s.textContent='html,body{margin:0;padding:0;width:100%;height:100%;max-width:none!important;min-width:0!important;}'+
                        '#root,.app,main,div[class*="layout"],div[class*="container"]{max-width:none!important;width:100%!important;}';
                      document.head.appendChild(s);
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        }
        connect()
    }

    private fun connect() {
        loading.visibility = View.VISIBLE
        errorBox.visibility = View.GONE
        webView.visibility = View.GONE

        startForegroundService(Intent(this, ZcodeWebService::class.java))
        EngineState.markRunning(this, "zcode")
        WatchdogTargets.watchZcode(this)

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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (AttachmentBridge.onResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }
}
