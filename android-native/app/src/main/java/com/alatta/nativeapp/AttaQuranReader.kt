package com.alatta.nativeapp

import android.graphics.Color as AndroidColor
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
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
    var loadingProblem by remember { mutableStateOf<String?>(null) }
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
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    // Serve the bundled Mushaf through a normal HTTPS document navigation.
                    // This avoids the fragile loadDataWithBaseURL path on some Android WebViews
                    // while keeping same-origin fetches to the public read-only Quran API.
                    if (request?.url?.toString() == "https://api.alquran.cloud/__atta_mushaf_local__.html") {
                        return try {
                            WebResourceResponse("text/html", "UTF-8", context.assets.open("atta-quran.html"))
                        } catch (_: Exception) {
                            WebResourceResponse("text/plain", "UTF-8", 404, "Missing Quran asset", emptyMap(),
                                "Missing Quran asset".byteInputStream())
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript("Boolean(document.getElementById('indexButton') && document.getElementById('pageText') && document.querySelector('.toolbar'))") { result ->
                        loadingProblem = if (result == "true") null else "تعذّر عرض واجهة المصحف. افتح القسم مرة أخرى أو حدّث Android System WebView."
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) loadingProblem =
                        "تعذّر فتح المصحف (${error?.errorCode}). حاول فتح القسم مرة أخرى."
                }
            }
            // Asset is intercepted locally; it is never downloaded from the API.
            // This real document navigation works more reliably than data URLs on HONOR WebView.
            loadUrl("https://api.alquran.cloud/__atta_mushaf_local__.html")
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
    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { reader }, modifier = Modifier.fillMaxSize())
        loadingProblem?.let { issue ->
            Text(issue, modifier = Modifier.align(Alignment.Center)
                .background(Color(0xFFFFF9E9)), color = Color(0xFF884300))
        }
    }
}
