package com.alatta.nativeapp

import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Accept canonical public Facebook videos only; never scrape a personalized Facebook feed. */
internal fun attaFacebookReelLink(text: String): String? {
    for (hit in Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).findAll(text)) {
        val uri = runCatching { URI(hit.value.trimEnd('.', ',', ';', ')', ']', '،', '؛')) }.getOrNull() ?: continue
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" ||
            uri.host?.lowercase(Locale.ROOT) !in setOf("facebook.com", "www.facebook.com", "m.facebook.com") ||
            uri.port !in listOf(-1, 443) || uri.rawUserInfo != null) continue
        val path = uri.path.orEmpty()
        val reel = Regex("""^/reel/([0-9]{3,30})/?$""").matchEntire(path)
        if (reel != null) return "https://www.facebook.com/reel/" + reel.groupValues[1]
        val video = Regex("""^/[^/]+/videos/([0-9]{3,30})/?$""").matchEntire(path)
            ?: Regex("""^/videos/([0-9]{3,30})/?$""").matchEntire(path)
        if (video != null) return "https://www.facebook.com/watch/?v=" + video.groupValues[1]
        if (path == "/watch" || path == "/watch/") {
            val id = Regex("""(?:^|&)v=([0-9]{3,30})(?:&|$)""").find(uri.rawQuery.orEmpty())?.groupValues?.get(1)
            if (id != null) return "https://www.facebook.com/watch/?v=" + id
        }
    }
    return null
}

internal fun attaReelPosts(posts: List<JSONObject>): List<JSONObject> =
    posts.filter { post -> post.optString("id").isNotBlank() &&
        (post.optString("video").isNotBlank() || attaFacebookReelLink(post.optString("text")) != null) }
        .distinctBy { it.optString("id") }

/** Uses the existing Media3 viewer for native videos and Facebook's official embedded player. */
@Composable
internal fun AttaReelsScreen(
    posts: List<JSONObject>,
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
    onAdd: () -> Unit,
    onReact: (String) -> Unit,
    onComments: (String) -> Unit,
    onShare: (String) -> Unit,
    onSave: (String) -> Unit
) {
    BackHandler(onBack = onExit)
    val reels = remember(posts) { attaReelPosts(posts) }
    val pager = rememberPagerState(pageCount = { reels.size })
    Column(modifier.fillMaxSize().background(Color(0xFF050505))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TextButton(onClick = onExit) { SiteIcon("arrow-right", tint = Color.White, size = 20.dp) }
            Text("ريلز العطا", color = GoldLight, fontWeight = FontWeight.Bold,
                fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) {
                SiteIcon("plus", tint = GoldLight, size = 18.dp)
                Spacer(Modifier.width(4.dp))
                Text("مشاركة فيديو", color = GoldLight)
            }
        }
        if (reels.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                SiteIcon("play", tint = GoldLight, size = 46.dp)
                Spacer(Modifier.height(14.dp))
                Text("لسه مفيش ريلز في المنشورات المحمّلة", color = Color.White)
                Text("شارك فيديو من العطا أو رابط ريلز عام من فيسبوك في منشور، وهيظهر هنا.",
                    color = Color.LightGray, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = GoldLight)) {
                    Text("شارك أول ريلز", color = Color.Black)
                }
            }
        } else {
            VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val post = reels[page]
                val facebook = attaFacebookReelLink(post.optString("text"))
                    ?.takeIf { post.optString("video").isBlank() }
                val id = post.optString("id")
                val author = post.optJSONObject("authorInfo")
                val authorName = author?.optString("fullName")?.takeIf { it.isNotBlank() }
                    ?: author?.optString("displayName")?.takeIf { it.isNotBlank() }
                    ?: post.optString("author", "مستخدم العطا")
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (page == pager.currentPage) {
                        if (facebook != null) {
                            AttaFacebookReelEmbed(facebook, Modifier.fillMaxSize().padding(bottom = 108.dp))
                        } else {
                            Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 82.dp),
                                contentAlignment = Alignment.Center) {
                                AttaExpandableVideo(media(post.optString("video")),
                                    media(post.optString("image")).takeIf { it.isNotBlank() })
                            }
                        }
                    }
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Color(0xE6000000)).padding(12.dp)) {
                        Text(authorName, color = GoldLight, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (facebook != null) "فيديو عام من فيسبوك • المشغّل الرسمي"
                            else "فيديو من منشورات العطا • اضغط للتشغيل",
                            color = Color.LightGray, fontSize = 11.sp)
                        Text(post.optString("text"), color = Color.White, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically) {
                            AttaReelAction("heart", "إعجاب") { onReact(id) }
                            AttaReelAction("message-circle", "تعليق") { onComments(id) }
                            AttaReelAction("repeat", "مشاركة") { onShare(id) }
                            AttaReelAction("bookmark", "حفظ") { onSave(id) }
                        }
                    }
                    Text((page + 1).toString() + " / " + reels.size,
                        color = GoldLight, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(14.dp))
                }
            }
        }
    }
}

@Composable
private fun AttaReelAction(icon: String, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        SiteIcon(icon, tint = GoldLight, size = 22.dp)
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun AttaFacebookReelEmbed(link: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember(link) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val host = request.url.host.orEmpty().lowercase(Locale.ROOT)
                    return request.url.scheme != "https" ||
                        !(host == "facebook.com" || host.endsWith(".facebook.com"))
                }
            }
            loadUrl("https://www.facebook.com/plugins/video.php?href=" + Uri.encode(link) + "&show_text=0&width=360")
        }
    }
    DisposableEffect(view) {
        onDispose {
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        }
    }
    Column(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f))
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("فتح الفيديو على فيسبوك إذا تعذر التضمين", color = GoldLight, fontSize = 12.sp)
        }
    }
}
