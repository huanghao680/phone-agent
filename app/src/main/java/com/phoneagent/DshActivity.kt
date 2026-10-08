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

        val webScale = Prefs.webUiScale(this)
        WebUi.apply(webView, webScale)
        AttachmentBridge.attach(this, webView)
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (req?.isForMainFrame == true) {
                    errorText.text = err?.description ?: getString(R.string.dsh_error)
                }
            }

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

        startForegroundService(Intent(this, DshService::class.java))
        EngineState.markRunning(this, "dsh")
        WatchdogTargets.watchDsh(this)

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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (AttachmentBridge.onResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }
}
