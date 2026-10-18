package com.alatta.nativeapp

import android.graphics.Color as AndroidColor
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * One dedicated Mushaf view: 114 surahs, 30 juz, 240 quarters, 604 pages.
 * Quranic text, tafsir and recitation come from Al Quran Cloud via HTTPS.
 * No WebRTC, Worker, or YouTube player is created or modified here.
 * MainActivity's existing BackHandler uses Android system back navigation.
 */
@Composable
internal fun QuranReaderScreen(modifier: Modifier = Modifier, onExit: () -> Unit) {
    val context = LocalContext.current
    val reader = remember(context) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.rgb(253, 247, 230))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.textZoom = 100 // Avoid Android text auto-enlargement breaking the Mushaf layout.
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false // Playback starts only after an explicit verse choice.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    // The reader renders its own network-retry UI. Do not navigate
                    // outside the trusted local page when third-party APIs fail.
                }
            }
            val html = context.assets.open("atta-quran.html").bufferedReader().use { it.readText() }
            // HTML inherits an HTTPS origin that matches the read-only Quran API.
            // No JavascriptInterface is exposed to third-party sources.
            loadDataWithBaseURL("https://api.alquran.cloud/", html, "text/html", "UTF-8", null)
        }
    }
    DisposableEffect(reader) {
        onDispose {
            runCatching { reader.loadUrl("about:blank") }
            runCatching { reader.destroy() }
        }
    }
    // The phone's system Back dismisses the verse/index sheet first, then exits the reader.
    // No extra on-screen back icon is added to the Mushaf.
    BackHandler {
        reader.evaluateJavascript("(function(){return window.attaQuranBack && window.attaQuranBack() ? 'dismissed' : 'exit';})()") { result ->
            if (result != "\"dismissed\"") onExit()
        }
    }
    AndroidView(factory = { reader }, modifier = modifier.fillMaxSize())
}
