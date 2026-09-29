package com.phoneagent

import android.webkit.WebView

/**
 * Shared WebView setup for the two webUI activities (zcode web + dsh web).
 *
 * Pinch zoom: Chromium WebView only performs gesture zoom when setSupportZoom
 * is on, and even then a page viewport meta with user-scalable=no would still
 * win. The bundles currently ship permissive metas; WEBVIEW_PATCH_JS rewrites
 * the meta after load anyway so a future bundle update can't silently break it.
 */
const val WEBVIEW_PATCH_JS = """
    (function(){
      var m=document.querySelector('meta[name="viewport"]');
      var c='width=device-width, initial-scale=1, minimum-scale=0.1, maximum-scale=8, user-scalable=yes';
      if(m){m.setAttribute('content',c);}
    })();
    """

fun WebView.applyWebUiSettings() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // make the SPA fill the landscape viewport: the web apps center themselves
    // at a phone-ish max-width, which leaves big black bars on wide screens
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
}
