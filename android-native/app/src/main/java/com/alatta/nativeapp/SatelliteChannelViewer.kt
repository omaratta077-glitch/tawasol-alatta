package com.alatta.nativeapp

import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Keep official publisher viewing pages inside AL-ATTA Media without pretending
 * their URL is a distributable HLS stream. For YouTube use only the official
 * YouTube embedded player, not extracted audio/video links.
 */
@Composable
internal fun SatelliteChannelViewer(channel: SatelliteCandidate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val webView = remember(channel.id) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true // Required by publisher live players
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            val id = channel.youtubeVideoId
            if (id != null && id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                // Base URL supplies Referer to YouTube; YouTube requires client identity.
                val html = """<!doctype html><html lang="ar"><head><meta name="viewport"
                    content="width=device-width,initial-scale=1"><style>
                    html,body{margin:0;background:#090909;color:#efd085;height:100%;font-family:sans-serif}
                    iframe{border:0;width:100%;height:100%;min-height:240px}</style></head><body>
                    <iframe src="https://www.youtube.com/embed/$id?playsinline=1&rel=0"
                    title="Official broadcaster video" allow="autoplay;encrypted-media;picture-in-picture;fullscreen"
                    allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe>
                    </body></html>"""
                loadDataWithBaseURL("https://tawasol-alatta.omaratta077-4fd.workers.dev/", html, "text/html", "UTF-8", null)
            } else {
                loadUrl(channel.officialPage)
            }
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f), color = PremiumBackground) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(channel.name, color = PremiumGoldLight, fontSize = 17.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("إغلاق", color = PremiumGoldLight) }
                }
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("البث من الناشر الرسمي • قد تتغير الإتاحة حسب البلد", color = PremiumMuted,
                        fontSize = 10.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(channel.officialPage)))
                        }
                    }) { Text("فتح المصدر", color = Color.White, fontSize = 12.sp) }
                }
            }
        }
    }
}
