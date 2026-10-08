package com.phoneagent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Wires the web UI's file input to the system pickers.
 *
 * The official web SPAs trigger a plain <input type=file>, which on Android needs
 * a WebChromeClient callback; without it the attachment button does nothing.
 * Photos go to the photo picker (API 33+ visual media picker) when the input
 * accepts images, everything else to the generic document picker — the same split
 * dsh-mobile-apk landed on after finding a single picker gives a poor experience.
 */
object AttachmentBridge {

    private const val REQ = 9101

    private var callback: ValueCallback<Array<Uri>>? = null

    /**
     * Delegates a chooser request to the system pickers. Shared by attach()'s
     * WebChromeClient and the browser workbench's own client.
     */
    fun fileChooserCallback(
        activity: Activity,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: android.webkit.WebChromeClient.FileChooserParams?,
    ): Boolean {
        callback?.onReceiveValue(null)
        callback = filePathCallback
        val acceptImages = acceptsImages(fileChooserParams)
        val intent = if (acceptImages) {
            Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                .setType("image/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        val chooser = Intent.createChooser(intent, if (acceptImages) "选择图片" else "选择文件")
        return try {
            activity.startActivityForResult(chooser, REQ)
            true
        } catch (_: Exception) {
            callback = null
            false
        }
    }

    fun attach(activity: Activity, webView: WebView) {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                return fileChooserCallback(activity, filePathCallback, fileChooserParams)
            }
        }
    }

    /** Call from the activity's onActivityResult. */
    fun onResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ) return false
        val cb = callback ?: return false
        callback = null
        val uris = when {
            resultCode != Activity.RESULT_OK || data == null -> null
            data.clipData != null -> (0 until data.clipData!!.itemCount).mapNotNull { data.clipData!!.getItemAt(it).uri }
                .toTypedArray()
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        cb.onReceiveValue(uris)
        return true
    }

    private fun acceptsImages(params: android.webkit.WebChromeClient.FileChooserParams?): Boolean {
        val types = params?.acceptTypes ?: return false
        if (types.isEmpty()) return false
        return types.any { it.isNotBlank() && (it.startsWith("image/") || it == "*/*") }
    }
}
