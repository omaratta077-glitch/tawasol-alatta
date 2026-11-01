package com.alatta.nativeapp

import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** لا نعيد تحميل/نسخ مقاطع Meta. نعرض التضمينات الرسمية للفيديوهات العامة فقط. */
internal data class SocialVideoLink(val provider: String, val url: String, val embedUrl: String)

internal fun socialVideoUrl(raw: String): SocialVideoLink? {
    val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "https") return null
    val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
    val path = uri.path?.trimEnd('/') ?: return null
    if (host == "instagram.com") {
        val match = Regex("^/(reel|p)/([A-Za-z0-9_-]{5,40})$").matchEntire(path) ?: return null
        val publicUrl = "https://www.instagram.com/${match.groupValues[1]}/${match.groupValues[2]}/"
        return SocialVideoLink("إنستجرام", publicUrl, publicUrl + "embed/")
    }
    if (host == "facebook.com" || host == "fb.watch") {
        val supported = host == "fb.watch" && Regex("^/[A-Za-z0-9_-]{4,40}$").matches(path) ||
            Regex("^/reel/[0-9]{4,30}$").matches(path) ||
            Regex("^/[^/]{1,80}/videos/[0-9]{4,30}$").matches(path) ||
            path == "/watch" && uri.getQueryParameter("v")?.matches(Regex("[0-9]{4,30}")) == true
        if (!supported) return null
        val safeUrl = "https://" + host + path + if (path == "/watch") {
            "?v=" + uri.getQueryParameter("v")
        } else "/"
        return SocialVideoLink("فيسبوك", safeUrl,
            "https://www.facebook.com/plugins/video.php?href=" +
            Uri.encode(safeUrl) + "&show_text=false&width=480")
    }
    return null
}

internal fun socialVideoInPost(text: String): SocialVideoLink? {
    // دعم رابط واحد معلن في نص المنشور؛ لا نفك روابط مختصرة غير موثوقة.
    val urls = Regex("https://[^\\s<>]+").findAll(text)
    return urls.mapNotNull { socialVideoUrl(it.value.trimEnd('.', ',', ')', '،')) }.firstOrNull()
}

@Composable
internal fun SocialVideoPostEmbed(link: SocialVideoLink, modifier: Modifier = Modifier,
    portrait: Boolean = false, onSwipe: ((Float, Float) -> Unit)? = null) {
    var opened by remember(link.url, portrait) { mutableStateOf(portrait) }
    val context = LocalContext.current
    val aspectModifier = if (portrait) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(330.dp)
    Box(modifier.then(aspectModifier).background(Color(0xFF090B0B), RoundedCornerShape(16.dp))
        .border(1.dp, PremiumGold, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        if (opened) {
            val view = remember(link.embedUrl) {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    // In reels, initialize the official embed on arrival. Provider autoplay policies still apply.
                    settings.mediaPlaybackRequiresUserGesture = !portrait
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val host = request.url.host.orEmpty().lowercase()
                            // لا ننقل المستخدم تلقائيًا إلى صفحة تسجيل الدخول.
                            return host !in setOf("www.facebook.com", "facebook.com",
                                "www.instagram.com", "instagram.com")
                        }
                    }
                    loadUrl(if (portrait && link.provider == "فيسبوك") {
                        link.embedUrl + "&autoplay=true&mute=true"
                    } else link.embedUrl)
                }
            }
            val gestureHost = remember(view) {
                MiniPlayerTouchHost(context).apply {
                    addView(view, android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                }
            }
            DisposableEffect(gestureHost) {
                onDispose { view.stopLoading(); gestureHost.removeAllViews(); view.destroy() }
            }
            AndroidView(factory = { gestureHost }, update = { it.onDrag = onSwipe },
                modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.clickable { opened = true }.padding(23.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("▶", color = PremiumGoldLight, fontSize = 42.sp)
                Text("فيديو ${link.provider}", color = PremiumGoldLight,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("اضغط للتشغيل داخل العطا • يتطلب منشورًا عامًا يسمح بالتضمين",
                    color = Color.White, fontSize = 11.sp)
            }
        }
    }
}
