package com.alatta.nativeapp

import android.graphics.Color as AndroidColor
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
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
 * Printed pages and verse metadata are packaged locally. Tafsir/recitation use HTTPS.
 * No WebRTC, Worker, or YouTube player is created or modified here.
 * MainActivity's existing BackHandler uses Android system back navigation.
 */
@Composable
internal fun QuranReaderScreen(modifier: Modifier = Modifier, onExit: () -> Unit) {
    val context = LocalContext.current
    var loadingProblem by remember { mutableStateOf<String?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val loader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/mushaf/", WebViewAssetLoader.PathHandler { path ->
                if (!Regex("[0-9]{3}\\.svg").matches(path)) return@PathHandler null
                try {
                    // Android's asset merge expands source .svg.gz files and strips .gz.
                    WebResourceResponse("image/svg+xml", "UTF-8", context.assets.open("mushaf/pages/$path"))
                } catch (_: java.io.IOException) { null }
            })
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }
    val reader = remember(context) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.rgb(253, 247, 230))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.textZoom = 100 // The packaged printed-page artwork keeps its original line layout.
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false // Playback starts only after an explicit verse choice.
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    // AndroidX serves the packaged reader from a stable, secure origin.
                    // External Quran API requests remain normal HTTPS requests.
                    return request?.url?.let { uri -> loader.shouldInterceptRequest(uri) }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript("Boolean(document.getElementById('indexButton') && document.getElementById('pageText') && document.querySelector('.toolbar'))") { result ->
                        pageReady = result == "true"
                        loadingProblem = if (pageReady) null else "تعذّر عرض واجهة المصحف. جرّب فتح القسم مرة أخرى أو تحديث Android System WebView."
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
            loadUrl("https://appassets.androidplatform.net/assets/atta-quran.html")
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
