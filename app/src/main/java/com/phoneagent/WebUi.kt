package com.phoneagent

import android.webkit.WebView

/**
 * Shared WebView setup for the two webUI activities (zcode web + dsh web).
 *
 * Pinch zoom: Chromium WebView only performs gesture zoom when setSupportZoom
 * is on, and even then a page viewport meta with user-scalable=no would still
 * win. The bundles currently ship permissive metas; WEBVIEW_PATCH_JS rewrites
 * the meta after load anyway so a future bundle update can't silently break it.
 *
 * Zoom-OUT floor is the layout-fit scale: Chromium refuses to scale below
 * where the layout width fills the viewport, whatever minimum-scale says. The
 * patch therefore widens the layout viewport to 2x the screen, which puts the
 * floor at 0.5x and makes more content visible at once (the web apps are
 * responsive and our injected CSS fills the width). Pinching back in reaches
 * 8x, so the usable range is 0.5x-8x.
 */
const val WEBVIEW_PATCH_JS = """
    (function(){
      var m=document.querySelector('meta[name="viewport"]');
      var base=window.innerWidth||800;
      var w=Math.round(base*2);
      var s=(base/w).toFixed(3);
      var c='width='+w+', initial-scale='+s+', minimum-scale=0.1, maximum-scale=8, user-scalable=yes';
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
