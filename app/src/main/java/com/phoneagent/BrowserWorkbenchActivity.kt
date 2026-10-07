package com.phoneagent

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Isolated browser workbench the agent can drive over the tool channel:
 * /browser-open loads a URL here, /browser-snapshot extracts the rendered page
 * (title / visible text / links / inputs) as JSON for the model, /browser-close
 * tears it down. A second WebView completely separate from the webUI views —
 * UI is for humans, the agent reads through the snapshot, following the
 * dsh-mobile-apk workbench design in a single-tab v1.
 *
 * Everything WebView-ish runs on the main thread; the tool service waits on a
 * latch (5s cap) for the JavaScript extraction callback.
 */
class BrowserWorkbenchActivity : ComponentActivity() {

    companion object {
        @Volatile private var instance: BrowserWorkbenchActivity? = null

        private val main = Handler(Looper.getMainLooper())

        fun isOpen(): Boolean = instance != null

        fun open(ctx: android.content.Context, url: String) {
            val i = android.content.Intent(ctx, BrowserWorkbenchActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (url.isNotBlank()) i.putExtra("url", url)
            ctx.startActivity(i)
        }

        fun close() {
            instance?.let { a ->
                main.post { a.finishAndRemoveTask() }
            }
        }

        /** Runs the extraction script in the page; blocks up to 5s for the result. */
        fun snapshot(): Map<String, Any?> {
            val a = instance ?: return mapOf("error" to "browser not open")
            val latch = CountDownLatch(1)
            var result: Map<String, Any?> = mapOf("error" to "timeout")
            main.post {
                a.web.evaluateJavascript(EXTRACT_JS) { json ->
                    val m = parseJsObject(json).toMutableMap()
                    val lines = consoleLines()
                    if (lines.isNotEmpty()) m["console"] = lines
                    m["consoleCleared"] = false
                    result = m
                    latch.countDown()
                }
            }
            latch.await(5, TimeUnit.SECONDS)
            return result
        }

        /** Recent console + JS error lines, surfaced in snapshots. */
        private val console = java.util.Collections.synchronizedList(ArrayList<String>(20))

        fun consoleLines(): List<String> = synchronized(console) { console.toList() }

        fun clearConsole() = synchronized(console) { console.clear() }

        private fun logConsole(line: String) {
            synchronized(console) {
                console.add(line.take(200))
                if (console.size > 20) console.removeAt(0)
            }
        }

        private fun parseJsObject(json: String): Map<String, Any?> = try {
            // evaluateJavascript serializes the JS value as JSON: a JS string
            // arrives as a quoted literal, so decode one level before parsing
            val v = org.json.JSONTokener(json).nextValue()
            val o = when (v) {
                is String -> org.json.JSONObject(v)
                is org.json.JSONObject -> v
                else -> org.json.JSONObject("{}")
            }
            val m = HashMap<String, Any?>()
            for (k in o.keys()) m[k] = o.opt(k)
            m
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "parse"))
        }

        /** Visible text + interactive elements with CSS-px centers for tap scaling. */
        private const val EXTRACT_JS = """
            (function(){
              function center(el){
                var r=el.getBoundingClientRect();
                return {x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)};
              }
              var links=[],inputs=[],buttons=[];
              document.querySelectorAll('a[href]').forEach(function(el,i){
                if(links.length<40&&el.textContent.trim())links.push({t:el.textContent.trim().slice(0,80),href:el.href.slice(0,200),c:center(el)});
              });
              document.querySelectorAll('input,textarea,select').forEach(function(el){
                if(inputs.length<25)inputs.push({tag:el.tagName.toLowerCase(),type:el.type||'',ph:el.placeholder||'',name:el.name||'',c:center(el)});
              });
              document.querySelectorAll('button,[role=button],input[type=submit]').forEach(function(el){
                if(buttons.length<25&&el.textContent.trim())buttons.push({t:el.textContent.trim().slice(0,60),c:center(el)});
              });
              var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').slice(0,12000);
              return JSON.stringify({title:document.title,url:location.href,iw:window.innerWidth,sw:screen.width,text:text,links:links,inputs:inputs,buttons:buttons});
            })()
        """
    }

    lateinit var web: WebView
        private set

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // isolated workbench: never touches the webUI WebView's state
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
        }
        // console telemetry: an agent debugging its own dev server needs the
        // error output, not just the rendered DOM (Mobile-Harness does the same)
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                val mm = m ?: return false
                logConsole("[${mm.messageLevel()}] ${mm.message()}")
                return true
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, r: android.webkit.JsResult?): Boolean {
                logConsole("[alert] ${message ?: ""}")
                r?.confirm()
                return true
            }
        }
        instance = this
        val url = intent.getStringExtra("url")
        if (!url.isNullOrBlank()) web.loadUrl(url)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        web.destroy()
        super.onDestroy()
    }
}
