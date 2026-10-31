package com.alatta.nativeapp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import java.net.URI
import java.net.URLEncoder

/**
 * روابط فيديوهات فيسبوك العامة فقط؛ لا يتم تنزيل المحتوى أو استخراج روابط بث خاصة.
 * تُقبل روابط الريلز، الفيديو، watch، والمشاركة المباشرة، مع رفض أي مضيف مختلف.
 */
private val facebookUrlPattern = Regex("""https?://[^\s<>"'،؛]+""", RegexOption.IGNORE_CASE)

internal fun facebookReelUrl(text: String): String? =
    facebookUrlPattern.findAll(text).mapNotNull { candidate ->
        val raw = candidate.value.trimEnd('.', ',', ')', ']', '}', '!', '?', '؛', '،')
        val uri = runCatching { URI(raw) }.getOrNull() ?: return@mapNotNull null
        val host = uri.host?.lowercase() ?: return@mapNotNull null
        val path = uri.path.orEmpty()
        val validHost = host in setOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "fb.watch")
        val validPath = when {
            host == "fb.watch" -> path.trim('/').isNotEmpty()
            path.matches(Regex("""/(?i:reel)/[A-Za-z0-9_-]+/?""")) -> true
            path.matches(Regex("""/(?i:share)/(?i:r|v)/[A-Za-z0-9_-]+/?""")) -> true
            path.matches(Regex("""/[^/?]+/(?i:videos)/[A-Za-z0-9_-]+/?""")) -> true
            path.equals("/watch", ignoreCase = true) || path.equals("/watch/", ignoreCase = true) ||
                path.equals("/video.php", ignoreCase = true) -> Regex("""(?:^|&)v=[0-9]+(?:&|$)""").containsMatchIn(uri.rawQuery.orEmpty())
            else -> false
        }
        if (uri.scheme != "https" || !validHost || !validPath || uri.userInfo != null || raw.length > 2048)
            return@mapNotNull null
        val normalizedHost = if (host == "fb.watch") "fb.watch" else "www.facebook.com"
        val query = if (path.equals("/watch", true) || path.equals("/watch/", true) || path.equals("/video.php", true)) {
            uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }.orEmpty()
        } else ""
        "https://$normalizedHost$path" + if (query.isNotEmpty()) "?$query" else ""
    }.firstOrNull()

/** استخدام Facebook Embedded Video Player الرسمي بدلاً من استخراج ملف MP4 أو HLS. */
internal fun facebookEmbedUrl(publicVideoUrl: String): String =
    "https://www.facebook.com/plugins/video.php?href=" +
        URLEncoder.encode(publicVideoUrl, "UTF-8") + "&show_text=false&width=500"


@androidx.compose.runtime.Composable
internal fun FacebookEmbeddedReel(publicVideoUrl: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val embed = androidx.compose.runtime.remember(publicVideoUrl) { facebookEmbedUrl(publicVideoUrl) }
    val webView = androidx.compose.runtime.remember(context, embed) {
        android.webkit.WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webChromeClient = android.webkit.WebChromeClient()
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?
                ): Boolean {
                    if (request?.isForMainFrame != true) return false
                    val host = request.url.host.orEmpty().lowercase()
                    // امنع التنقل داخل المشغل إلى صفحات غير تابعة لفيسبوك.
                    return host !in setOf("facebook.com", "www.facebook.com", "m.facebook.com")
                }
            }
            loadUrl(embed)
        }
    }
    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.Text(
            "فيديو فيسبوك عام",
            color = androidx.compose.ui.graphics.Color.White
        )
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { viewContext ->
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView
            },
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .height(420.dp)
        )
        androidx.compose.material3.TextButton(onClick = {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(publicVideoUrl))
            runCatching { context.startActivity(intent) }
        }) {
            androidx.compose.material3.Text("فتح الفيديو الأصلي إذا منع فيسبوك التضمين")
        }
    }
}
