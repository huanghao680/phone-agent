package com.phoneagent

import android.webkit.WebView

/**
 * Shared WebView setup for the web UI activities (zcode / dsh / opencode).
 *
 * Pinch zoom: Chromium WebView only performs gesture zoom when setSupportZoom
 * is on, and even then a page viewport meta with user-scalable=no would still
 * win. The bundles currently ship permissive metas; WEBVIEW_PATCH_JS rewrites
 * the meta after load anyway so a future bundle update can't silently break it.
 *
 * Zoom-OUT floor is the layout-fit scale: Chromium refuses to scale below
 * where the layout width fills the viewport, whatever minimum-scale says. The
 * patch therefore widens the layout viewport, which puts the floor below 1x and
 * makes more content visible at once. Pinching back in reaches 8x.
 *
 * WEB_UI_SCALE is the user's chosen ratio (percent). It is applied twice:
 *  - as setInitialScale, so the page opens at that ratio;
 *  - as the divisor of the viewport width, because that is what decides where
 *    the scale floor sits — setting only the initial scale would be undone by
 *    the next layout pass.
 */
object WebUi {

    /** Layout viewport width factor: wider viewport = smaller content = zoom out. */
    private fun viewportFactor(scalePercent: Int): Float = 100f / scalePercent.coerceIn(50, 150)

    /** Injected after load; rewrites the viewport meta to honour [scalePercent]. */
    fun patchJs(scalePercent: Int = 100): String {
        val f = viewportFactor(scalePercent)
        return """
    (function(){
      var m=document.querySelector('meta[name="viewport"]');
      var base=window.innerWidth||800;
      var w=Math.round(base*$f);
      var s=(base/w).toFixed(3);
      var c='width='+w+', initial-scale='+s+', minimum-scale=0.1, maximum-scale=8, user-scalable=yes';
      if(m){m.setAttribute('content',c);}
    })();
    """.trimIndent()
    }

    fun apply(web: WebView, scalePercent: Int = 100) {
        val pct = scalePercent.coerceIn(50, 150)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // make the SPA fill the landscape viewport: the web apps center
        // themselves at a phone-ish max-width, leaving bars on wide screens
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.setSupportZoom(true)
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        // opens the page at the chosen ratio (100 = 1:1)
        web.setInitialScale(pct)
    }
}
