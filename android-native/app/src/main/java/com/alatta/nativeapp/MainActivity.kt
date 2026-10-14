package com.alatta.nativeapp

import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.os.Bundle
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val BASE = "https://tawasol-alatta.omaratta077-4fd.workers.dev"
// Colors transcribed from the FINAL :root block in cloudflare-site/public/style.css.
// Intentionally independent of the web CSS: styling changes require an Android update.
private val Gold = Color(0xFFE6B94E)
private val GoldLight = Color(0xFFF6D776)
private val Black = Color(0xFF040506)
private val Panel = Color(0xFF090D0F)
private val Border = Color(0xFF6B5429)
private val White = Color(0xFFF7F8F9)
private val Muted = Color(0xFFC9D0D4)

private fun googleTokenDetails(idToken: String): JSONObject {
    // Preview only. Server validates Google's signature, audience and expiry.
    return try {
        val payload = idToken.split('.').getOrNull(1) ?: return JSONObject()
        JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
    } catch (_: Exception) { JSONObject() }
}
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
internal fun media(s: String): String {
    val value = s.trim()
    return when {
        value.startsWith("/media/") -> BASE + value
        value.startsWith("https://") -> value
        value.startsWith("data:image/", ignoreCase = true) -> value
        value.startsWith("data:video/", ignoreCase = true) -> value
        else -> ""
    }
}
private fun JSONObject.arr(name: String): List<JSONObject> {
    val a = optJSONArray(name) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
private fun JSONObject.name(): String = optString("fullName").ifBlank { optString("displayName").ifBlank { "مستخدم" } }
private fun api(path: String, payload: JSONObject? = null): JSONObject {
    val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
        requestMethod = if (payload == null) "GET" else "POST"
        connectTimeout = 20000; readTimeout = 30000
        setRequestProperty("Accept", "application/json")
        if (payload != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        }
    }
    return try {
        val status = conn.responseCode
        val content = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
        val obj = JSONObject(content)
        if (status !in 200..299 || obj.optBoolean("ok", true) == false) throw IllegalStateException(obj.optString("error", "HTTP $status"))
        obj
    } finally { conn.disconnect() }
}
private fun upload(token: String, uri: Uri, mime: String, filename: String, bytes: ByteArray): String {
    val kind = if (mime.startsWith("video/")) "video" else "image"
    val path = "/api/media-upload?token=${enc(token)}&kind=$kind&name=${enc(filename)}"
    val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true
        connectTimeout = 20000; readTimeout = 120000
        setRequestProperty("Content-Type", mime)
        outputStream.use { it.write(bytes) }
    }
    return try {
        val code = conn.responseCode
        val obj = JSONObject((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}")
        if (code !in 200..299) throw IllegalStateException(obj.optString("error", "فشل رفع الملف"))
        obj.getString("url")
    } finally { conn.disconnect() }
}


// Files up to the Cloudflare R2 endpoint limit (50 MiB) are streamed in 64 KiB chunks.
// Do not use readBytes() for videos: phones may run out of memory.
private fun uploadChatMedia(context: android.content.Context, token: String, uri: Uri, mime: String, type: String): String {
    val kind = when {
        type == "video" && mime.startsWith("video/") -> "video"
        type == "image" && mime.startsWith("image/") -> "image"
        type == "audio" && mime.startsWith("audio/") -> "audio"
        type == "file" && mime == "application/pdf" -> "file"
        else -> throw IllegalArgumentException("نوع الملف غير مدعوم")
    }
    val maxBytes = 50L * 1024L * 1024L
    var displayName = when (kind) {
        "video" -> "chat-video.mp4"
        "image" -> "chat-image.jpg"
        "audio" -> "chat-audio.m4a"
        else -> "chat-document.pdf"
    }
    var declaredSize = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
        }
    }
    require(declaredSize <= maxBytes) { "الحد الأقصى للفيديو أو الصورة 50 ميجابايت" }
    val path = "/api/media-upload?token=${enc(token)}&kind=$kind&name=${enc(displayName)}"
    val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true
        connectTimeout = 20000; readTimeout = 150000
        setRequestProperty("Content-Type", mime)
        if (declaredSize > 0L) setFixedLengthStreamingMode(declaredSize)
        else setChunkedStreamingMode(64 * 1024)
    }
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            conn.outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= maxBytes) { "حجم الملف يتجاوز 50 ميجابايت" }
                    output.write(buffer, 0, n)
                }
            }
        } ?: error("تعذر قراءة الفيديو من جهازك")
        val code = conn.responseCode
        val result = JSONObject((if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: "{}")
        if (code !in 200..299 || !result.optBoolean("ok", true)) {
            throw IllegalStateException(result.optString("error", "تعذر رفع المرفق"))
        }
        result.getString("url")
    } finally { conn.disconnect() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NativeApp() }
    }
}

@Composable private fun GoldButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black), shape = RoundedCornerShape(12.dp), modifier = Modifier.shadow(7.dp, RoundedCornerShape(12.dp), ambientColor = Gold.copy(alpha = .35f), spotColor = Gold.copy(alpha = .30f)).heightIn(min = 42.dp)) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}
@Composable private fun SectionTitle(text: String) {
    Text(text, color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 7.dp))
}
@Composable private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .shadow(12.dp, RoundedCornerShape(15.dp))
            .border(1.dp, Border.copy(alpha = .55f), RoundedCornerShape(15.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0A0F12), Color(0xFF070B0E))), RoundedCornerShape(15.dp))
            .padding(12.dp), content = content
    )
}
@Composable internal fun NativeVideo(url: String) {
    AttaExpandableVideo(url)
}
private fun openYouTube(context: android.content.Context, id: String) {
    if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) return
    val link = Uri.parse("https://www.youtube.com/watch?v=$id")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, link).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        // No installed handler; the native player remains accessible above.
    }
}

/** YouTube's supported embedded player, with a real HTTPS Referer supplied by WebView.
 *  The previous v1.0.18 player could show an endless spinner because it only
 *  watched the outer HTML page, not the YouTube player lifecycle.
 */
private class AttaYoutubeFullscreenChrome(
    private val context: android.content.Context,
    private val onPlayerEvent: (String) -> Unit
) : WebChromeClient() {
    private var fullscreenView: android.view.View? = null
    private var callback: CustomViewCallback? = null
    private var root: android.view.ViewGroup? = null
    private var owner: android.app.Activity? = null

    override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
        val line = message?.message().orEmpty()
        if (line.startsWith("ALATTA_YT:")) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onPlayerEvent(line.removePrefix("ALATTA_YT:"))
            }
            return true
        }
        return super.onConsoleMessage(message)
    }

    override fun onShowCustomView(view: android.view.View?, cb: CustomViewCallback?) {
        val activity = context as? android.app.Activity
        val container = activity?.window?.decorView as? android.view.ViewGroup
        if (activity == null || view == null || container == null || fullscreenView != null) {
            cb?.onCustomViewHidden()
            return
        }
        owner = activity
        root = container
        callback = cb
        fullscreenView = view
        container.addView(view, android.view.ViewGroup.LayoutParams(-1, -1))
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    override fun onHideCustomView() {
        fullscreenView?.let { root?.removeView(it) }
        owner?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val oldCallback = callback
        fullscreenView = null
        callback = null
        root = null
        owner = null
        oldCallback?.onCustomViewHidden()
    }

    fun release() {
        if (fullscreenView != null) onHideCustomView()
    }
}

private fun attaYouTubeHtml(videoId: String): String {
    // The YouTube IFrame API is the documented interface for play/pause/seek.
    // An HTTPS origin is essential: file:/// and about:blank are not valid.
    return """<!doctype html><html lang="ar"><head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,user-scalable=yes">
      <meta name="referrer" content="strict-origin-when-cross-origin">
      <style>html,body,#player{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000}
      iframe{display:block;border:0;width:100%;height:100%}</style></head>
      <body><div id="player"></div><script>
      var alattaPlayer=null, alattaTicker=null;
      function report(v){console.log('ALATTA_YT:'+v);}
      function alattaTime(){
        try {
          if(!alattaPlayer || !alattaPlayer.getCurrentTime) return;
          var t=alattaPlayer.getCurrentTime(), d=alattaPlayer.getDuration();
          if(Number.isFinite(t) && Number.isFinite(d))
            report('TIME:'+Math.max(0,Math.floor(t))+':'+Math.max(0,Math.floor(d)));
        } catch(e) {}
      }
      window.alattaControl=function(action,value){
        if(!alattaPlayer) return;
        try {
          if(action==='seekBy') alattaPlayer.seekTo(Math.max(0,alattaPlayer.getCurrentTime()+value),true);
          if(action==='seek') alattaPlayer.seekTo(Math.max(0,value),true);
          if(action==='toggle') {
            if(alattaPlayer.getPlayerState()===1) alattaPlayer.pauseVideo();
            else alattaPlayer.playVideo();
          }
          alattaTime();
        } catch(e){ report('CONTROL_ERROR'); }
      };
      function onYouTubeIframeAPIReady(){
        try {
          alattaPlayer=new YT.Player('player',{
            width:'100%',height:'100%',videoId:'$videoId',
            playerVars:{playsinline:1,rel:0,fs:1,controls:1,origin:'$BASE'},
            events:{
              onReady:function(){
                report('READY');
                alattaTime();
                if(alattaTicker) clearInterval(alattaTicker);
                alattaTicker=setInterval(alattaTime,850);
              },
              onStateChange:function(ev){
                if(ev.data===1) report('PLAYING');
                if(ev.data===2) report('PAUSED');
                if(ev.data===0) report('ENDED');
                alattaTime();
              },
              onError:function(ev){report('ERROR:'+ev.data);}
            }
          });
        } catch(e){report('ERROR:INIT');}
      }
      var script=document.createElement('script');
      script.src='https://www.youtube.com/iframe_api';
      script.onerror=function(){report('ERROR:SCRIPT');};
      document.head.appendChild(script);
      window.addEventListener('pagehide',function(){if(alattaTicker) clearInterval(alattaTicker);});
      </script></body></html>"""
}

private fun attaYoutubeError(code: String): String = when (code) {
    "100" -> "الفيديو محذوف أو خاص؛ اختر فيديو آخر."
    "101", "150" -> "صاحب الفيديو منع تشغيله داخل التطبيقات. افتحه في YouTube."
    "153" -> "YouTube رفض تعريف مصدر المشغّل (153). جرّب التشغيل المباشر أو YouTube."
    "5" -> "الفيديو لم يعمل في مشغّل HTML5 على هذا الجهاز."
    "SCRIPT", "INIT" -> "تعذّر تشغيل خدمة YouTube المضمّنة على الجهاز."
    else -> "تعذّر تشغيل الفيديو داخل التطبيق (رمز $code)."
}

private fun attaYoutubeTimestamp(seconds: Int): String =
    "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)

@Composable
private fun AttaYoutubeDisplay(
    webView: WebView,
    modeDirect: Boolean,
    videoId: String,
    loading: Boolean,
    error: String,
    zoom: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = {
                // The same WebView must survive entry/exit from the fullscreen dialog:
                // reparent it rather than reloading the video and losing playback time.
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView
            },
            update = { view ->
                if (view.tag != "atta-youtube-loaded") {
                    view.tag = "atta-youtube-loaded"
                    if (modeDirect) {
                        val address = "https://www.youtube.com/embed/$videoId?playsinline=1&rel=0&fs=1&origin=${enc(BASE)}"
                        view.loadUrl(address, mapOf("Referer" to "$BASE/"))
                    } else {
                        view.loadDataWithBaseURL("$BASE/", attaYouTubeHtml(videoId), "text/html", "UTF-8", null)
                    }
                }
            },
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom)
        )
        if (loading) CircularProgressIndicator(color = Gold, modifier = Modifier.size(32.dp))
        if (error.isNotEmpty()) {
            Text(error, color = GoldLight, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .94f)).padding(12.dp))
        }
    }
}

@Composable
private fun AttaYoutubeControls(
    canSeek: Boolean,
    isPlaying: Boolean,
    position: Int,
    duration: Int,
    zoom: Float,
    onCommand: (String, Int) -> Unit,
    onZoom: (Float) -> Unit,
    onFullscreen: () -> Unit,
    isFullscreen: Boolean
) {
    var dragging by remember { mutableStateOf(false) }
    var dragTime by remember { mutableFloatStateOf(0f) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(attaYoutubeTimestamp(if (dragging) dragTime.toInt() else position), color = GoldLight, fontSize = 11.sp)
            Slider(
                value = (if (dragging) dragTime else position.toFloat()).coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                onValueChange = { dragging = true; dragTime = it },
                onValueChangeFinished = {
                    if (dragging) onCommand("seek", dragTime.toInt())
                    dragging = false
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = canSeek && duration > 0,
                colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = GoldLight, inactiveTrackColor = Border),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(attaYoutubeTimestamp(duration), color = Muted, fontSize = 11.sp)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onCommand("seekBy", -10) }, enabled = canSeek) {
                Text("↶ 10ث", color = if (canSeek) GoldLight else Muted, fontSize = 13.sp)
            }
            TextButton(onClick = { onCommand("toggle", 0) }, enabled = canSeek) {
                Text(if (isPlaying) "❚❚" else "▶", color = if (canSeek) Gold else Muted, fontSize = 18.sp)
            }
            TextButton(onClick = { onCommand("seekBy", 10) }, enabled = canSeek) {
                Text("10ث ↷", color = if (canSeek) GoldLight else Muted, fontSize = 13.sp)
            }
            TextButton(onClick = { onZoom((zoom - .25f).coerceAtLeast(1f)) }) {
                Text("−", color = GoldLight, fontSize = 20.sp)
            }
            TextButton(onClick = { onZoom((zoom + .25f).coerceAtMost(2.5f)) }) {
                Text("+", color = GoldLight, fontSize = 20.sp)
            }
            TextButton(onClick = onFullscreen) {
                Text(if (isFullscreen) "⤢" else "⛶", color = GoldLight, fontSize = 23.sp)
            }
        }
        if (!canSeek) Text("التقديم والسحب يعملان في وضع التحكم التفاعلي IFrame.",
            color = Muted, fontSize = 11.sp)
    }
}

@Composable private fun YouTubePlayer(id: String) {
    val safeId = id.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return
    val ctx = LocalContext.current
    // Start with the documented IFrame API for functional seek/play controls.
    // Keep the previously working direct player available as fallback.
    var useDirectEmbed by remember(safeId) { mutableStateOf(false) }
    var retryCounter by remember(safeId) { mutableIntStateOf(0) }
    var loadError by remember(safeId, useDirectEmbed, retryCounter) { mutableStateOf("") }
    var loading by remember(safeId, useDirectEmbed, retryCounter) { mutableStateOf(true) }
    var playerReady by remember(safeId, useDirectEmbed, retryCounter) { mutableStateOf(false) }
    var isPlaying by remember(safeId, useDirectEmbed, retryCounter) { mutableStateOf(false) }
    var position by remember(safeId, useDirectEmbed, retryCounter) { mutableIntStateOf(0) }
    var duration by remember(safeId, useDirectEmbed, retryCounter) { mutableIntStateOf(0) }
    var zoom by remember(safeId) { mutableFloatStateOf(1f) }
    var fullscreen by remember(safeId) { mutableStateOf(false) }

    val chrome = remember(ctx, safeId, useDirectEmbed, retryCounter) {
        AttaYoutubeFullscreenChrome(ctx) { event ->
            when {
                event == "READY" || event == "PLAYING" -> {
                    playerReady = true
                    loading = false
                    loadError = ""
                    if (event == "PLAYING") isPlaying = true
                }
                event == "PAUSED" || event == "ENDED" -> isPlaying = false
                event.startsWith("TIME:") -> {
                    val parts = event.removePrefix("TIME:").split(':')
                    if (parts.size == 2) {
                        position = parts[0].toIntOrNull()?.coerceAtLeast(0) ?: position
                        duration = parts[1].toIntOrNull()?.coerceAtLeast(0) ?: duration
                    }
                }
                event.startsWith("ERROR:") -> {
                    loading = false
                    loadError = attaYoutubeError(event.removePrefix("ERROR:"))
                }
            }
        }
    }
    val webView = remember(ctx, safeId, useDirectEmbed, retryCounter) {
        WebView(ctx).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.loadsImagesAutomatically = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webChromeClient = chrome
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (useDirectEmbed && !playerReady && loadError.isEmpty()) {
                        playerReady = true
                        loading = false
                    }
                }
                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?,
                                             error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        loading = false
                        loadError = "تعذّر فتح YouTube. جرّب التشغيل المباشر أو تطبيق YouTube."
                    }
                }
                override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?,
                                                 response: android.webkit.WebResourceResponse?) {
                    if (request?.isForMainFrame == true && (response?.statusCode ?: 0) >= 400) {
                        loading = false
                        loadError = "خادم YouTube رفض تحميل الفيديو (${response?.statusCode})."
                    }
                }
            }
        }
    }
    DisposableEffect(webView, chrome) {
        onDispose {
            chrome.release()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }
    LaunchedEffect(safeId, useDirectEmbed, retryCounter) {
        delay(15000)
        if (!playerReady && loadError.isEmpty()) {
            loading = false
            loadError = "استغرق تشغيل الفيديو وقتًا طويلًا. جرّب التشغيل المباشر أو افتحه في YouTube."
        }
    }
    fun send(command: String, value: Int) {
        if (useDirectEmbed || !playerReady) return
        // Only fixed command names and integer arguments reach the HTML player.
        if (command !in setOf("seek", "seekBy", "toggle")) return
        webView.evaluateJavascript("window.alattaControl && window.alattaControl('$command',$value);", null)
    }
    fun switchMode() {
        fullscreen = false
        useDirectEmbed = !useDirectEmbed
        retryCounter += 1
        zoom = 1f
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!fullscreen) {
            AttaYoutubeDisplay(
                webView = webView, modeDirect = useDirectEmbed, videoId = safeId,
                loading = loading, error = loadError, zoom = zoom,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
            AttaYoutubeControls(
                canSeek = playerReady && !useDirectEmbed, isPlaying = isPlaying,
                position = position, duration = duration, zoom = zoom,
                onCommand = { action, amount -> send(action, amount) }, onZoom = { zoom = it },
                onFullscreen = { fullscreen = true }, isFullscreen = false
            )
        }
        Text("تحكم في الفيديو: تقديم وتأخير 10 ثوانٍ، سحب الشريط، تكبير، تصغير وملء الشاشة.",
            color = Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { switchMode() }) {
                Text(if (useDirectEmbed) "تشغيل التحكم التفاعلي" else "التشغيل المباشر (بديل)", color = GoldLight)
            }
            TextButton(onClick = { fullscreen = false; retryCounter += 1 }) {
                Text("إعادة المحاولة", color = GoldLight)
            }
        }
        GoldButton("▶ فتح في YouTube") { openYouTube(ctx, safeId) }
    }
    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Black).padding(10.dp),
                verticalArrangement = Arrangement.Center) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("يوتيوب • تواصل العطا", color = GoldLight, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold)
                    TextButton(onClick = { fullscreen = false }) {
                        Text("إغلاق ✕", color = GoldLight)
                    }
                }
                AttaYoutubeDisplay(
                    webView = webView, modeDirect = useDirectEmbed, videoId = safeId,
                    loading = loading, error = loadError, zoom = zoom,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                AttaYoutubeControls(
                    canSeek = playerReady && !useDirectEmbed, isPlaying = isPlaying,
                    position = position, duration = duration, zoom = zoom,
                    onCommand = { action, amount -> send(action, amount) }, onZoom = { zoom = it },
                    onFullscreen = { fullscreen = false }, isFullscreen = true
                )
            }
        }
    }
}

@Composable private fun Avatar(url: String, label: String, onClick: (() -> Unit)? = null) {
    if (media(url).isNotBlank()) ProfileMediaImage(value = url, description = label, modifier = Modifier.size(42.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).border(1.dp, Gold, CircleShape).clip(CircleShape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(42.dp).background(Gold, CircleShape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) { Text(label.take(1), color = Black, fontWeight = FontWeight.Bold) }
}

@Composable private fun NativeApp() {
    AppUpdatePrompt()
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("alatta_native", 0) }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf("login") }
    var regName by remember { mutableStateOf("") }
    var regUsername by remember { mutableStateOf("") }
    var regBirthDate by remember { mutableStateOf("") }
    var regPhone by remember { mutableStateOf("") }
    var regEmail by remember { mutableStateOf("") }
    var regCountry by remember { mutableStateOf("") }
    var regCity by remember { mutableStateOf("") }
    var googleIdToken by remember { mutableStateOf("") }
    var googleEmail by remember { mutableStateOf("") }
    var googleName by remember { mutableStateOf("") }
    var googleBirthDate by remember { mutableStateOf("") }
    var googleCity by remember { mutableStateOf("") }
    var googleUsername by remember { mutableStateOf("") }
    var nativeUsernameSignup by remember { mutableStateOf(false) }
    var birthDateSignup by remember { mutableStateOf(false) }
    var changePasswordDialog by remember { mutableStateOf(false) }
    var currentPassword by remember { mutableStateOf("") }
    var nextPassword by remember { mutableStateOf("") }
    var nextPasswordAgain by remember { mutableStateOf("") }
    var resetEmail by remember { mutableStateOf("") }
    var resetRequestId by remember { mutableStateOf("") }
    var resetCode by remember { mutableStateOf("") }
    var resetPassword by remember { mutableStateOf("") }
    var resetConfirm by remember { mutableStateOf("") }
    var regGender by remember { mutableStateOf("") }
    var regPass by remember { mutableStateOf("") }
    var regConfirm by remember { mutableStateOf("") }
    var verificationId by remember { mutableStateOf("") }
    var verificationDelivery by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var googleEnabled by remember { mutableStateOf(false) }
    var googleClientId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf(prefs.getString("name", "") ?: "") }
    var myUsername by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
    var tab by remember { mutableStateOf("الرئيسية") }
    val visited = remember { mutableStateListOf<String>() }
    var confirmExit by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var showStoryComposer by remember { mutableStateOf(false) }
    var selectedStory by remember { mutableStateOf<JSONObject?>(null) }
    var featureMessage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedMime by remember { mutableStateOf("") }
    var posts by remember { mutableStateOf(emptyList<JSONObject>()) }
    var stories by remember { mutableStateOf(emptyList<JSONObject>()) }
    var storyText by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf(emptyList<JSONObject>()) }
    var groupName by remember { mutableStateOf("") }
    var users by remember { mutableStateOf(emptyList<JSONObject>()) }
    var friends by remember { mutableStateOf(emptyList<JSONObject>()) }
    var requests by remember { mutableStateOf(emptyList<JSONObject>()) }
    var notifications by remember { mutableStateOf(emptyList<JSONObject>()) }
    var profile by remember { mutableStateOf(JSONObject()) }
    var myAvatar by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var chatWith by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(emptyList<JSONObject>()) }
    var inboxPreviews by remember { mutableStateOf(emptyList<ChatPreview>()) }
    var inboxLoading by remember { mutableStateOf(false) }
    var chatText by remember { mutableStateOf("") }
    var activeCall by remember { mutableStateOf<NativeCallSession?>(null) }
    var incomingCall by remember { mutableStateOf<NativeCallSession?>(null) }
    var pendingPermissionCall by remember { mutableStateOf<NativeCallSession?>(null) }
    val knownCalls = remember(token) { mutableSetOf<String>() }
    var profilePeer by remember { mutableStateOf("") }
    var publicProfile by remember { mutableStateOf<JSONObject?>(null) }
    var publicPosts by remember { mutableStateOf(emptyList<JSONObject>()) }
    var publicProfileLoading by remember { mutableStateOf(false) }
    var youtubeSearch by remember { mutableStateOf("") }
    var youtubeItems by remember { mutableStateOf(emptyList<JSONObject>()) }
    var youtubeId by remember { mutableStateOf("") }
    val youtubeListState = rememberLazyListState()
    var profileBio by remember { mutableStateOf("") }
    // An Activity-scoped job is not cancelled just because login or a tab leaves composition.
    val scope = (ctx as ComponentActivity).lifecycleScope
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pickedUri = uri
        pickedMime = uri?.let { ctx.contentResolver.getType(it) } ?: ""
    }
    fun task(block: suspend () -> Unit) {
        scope.launch {
            busy = true; error = ""
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "تعذر إتمام العملية" }
            finally { busy = false }
        }
    }
    fun applySession(result: JSONObject, fallbackName: String) {
        token = result.getString("token")
        val u = result.optJSONObject("user") ?: JSONObject()
        displayName = u.name().ifBlank { fallbackName }
        myUsername = u.optString("username", fallbackName).lowercase()
        myAvatar = u.optString("avatar")
        prefs.edit().putString("token", token).putString("name", displayName).putString("username", myUsername).apply()
        password = ""; regPass = ""; regConfirm = ""; googleIdToken = ""
        resetPassword = ""; resetConfirm = ""; authMode = "login"; tab = "الرئيسية"
    }
    @Suppress("UNUSED_PARAMETER")
    fun chooseGoogle(signup: Boolean) {
        if (busy) return
        if (!googleEnabled || googleClientId.isBlank()) {
            error = "تسجيل Google غير مُفعّل على الموقع. راجع GOOGLE_CLIENT_ID في Cloudflare."
            return
        }
        task {
            // Native Google account picker shown over this Activity. No Chrome or WebView.
            // The existing Cloudflare /api/google-login still verifies the ID token.
            val pickedToken = GoogleNativeSignIn.pickAccount(ctx as ComponentActivity, googleClientId)
                ?: return@task // User intentionally closed the account picker.
            // Existing Google users sign in immediately from either button;
            // only NEW users see the chosen name/username/date-of-birth form.
            try {
                val result = withContext(Dispatchers.IO) {
                    api("/api/google-login", JSONObject()
                        .put("credential", pickedToken)
                        .put("signupFlow", "android-login"))
                }
                applySession(result, "Google")
            } catch (e: IllegalStateException) {
                if (!e.message.orEmpty().contains("USERNAME_REQUIRED")) throw e
                val claims = googleTokenDetails(pickedToken) // Preview; Worker verifies.
                googleIdToken = pickedToken
                googleEmail = claims.optString("email")
                googleName = claims.optString("name").ifBlank { claims.optString("given_name") }
                googleBirthDate = ""
                googleCity = ""
                googleUsername = ""
                authMode = "googleProfile"
            }
        }
    }
    suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) { api("$path${if ('?' in path) "&" else "?"}token=${enc(token)}") }
    suspend fun post(path: String, data: JSONObject): JSONObject = withContext(Dispatchers.IO) { api(path, data.put("token", token)) }
    val callPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val next = pendingPermissionCall
        pendingPermissionCall = null
        if (next != null && granted.values.all { it }) activeCall = next
        else {
            featureMessage = "لا يمكن إجراء المكالمة بدون السماح بالميكروفون والكاميرا عند الحاجة"
            if (next?.incoming == true) scope.launch {
                try {
                    val packet = JSONObject().put("type", "call-reject").put("to", next.peer)
                        .put("from", myUsername).put("callId", next.callId)
                        .put("callType", next.type).put("reason", "permission-denied")
                        .put("signalId", UUID.randomUUID().toString())
                        .put("createdAt", System.currentTimeMillis())
                    post("/api/message-send", JSONObject().put("to", next.peer)
                        .put("text", "").put("media", packet.toString())
                        .put("mediaType", "call-signal"))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
    }
    fun openCall(peer: String, type: String, incoming: Boolean = false, callId: String = UUID.randomUUID().toString()) {
        if (peer.isBlank() || token.isBlank()) return
        if (activeCall != null) { featureMessage = "توجد مكالمة جارية"; return }
        val session = NativeCallSession(peer.lowercase(), type, callId, incoming)
        val permissionNames = if (type == "video")
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            else arrayOf(Manifest.permission.RECORD_AUDIO)
        val needed = permissionNames.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) activeCall = session
        else { pendingPermissionCall = session; callPermissions.launch(needed.toTypedArray()) }
    }
    fun openPublicProfile(other: String) {
        if (other.isBlank() || token.isBlank()) return
        profilePeer = other
        publicProfile = null; publicPosts = emptyList(); publicProfileLoading = true
        scope.launch {
            try {
                val detail = get("/api/profile-public?username=${enc(other)}")
                // Only the recent public posts returned by the existing API are shown.
                val latest = get("/api/posts?limit=50").arr("posts").filter {
                    it.optString("author").equals(other, ignoreCase = true)
                }
                if (profilePeer == other) { publicProfile = detail; publicPosts = latest }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (profilePeer == other) featureMessage = e.message ?: "تعذر فتح صفحة المستخدم" }
            finally { if (profilePeer == other) publicProfileLoading = false }
        }
    }
    fun sendCallResponse(session: NativeCallSession, reason: String) {
        scope.launch {
            try {
                val data = JSONObject().put("type", "call-reject").put("to", session.peer)
                    .put("from", myUsername).put("callId", session.callId)
                    .put("callType", session.type).put("reason", reason)
                    .put("signalId", UUID.randomUUID().toString())
                    .put("createdAt", System.currentTimeMillis())
                post("/api/message-send", JSONObject().put("to", session.peer)
                    .put("text", "").put("media", data.toString())
                    .put("mediaType", "call-signal"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }
    fun sendSelectedChatMedia(uri: Uri, requiredType: String) {
        if (chatWith.isBlank() || token.isBlank()) return
        scope.launch {
            busy = true; error = ""
            try {
                val receiver = chatWith
                val mime = ctx.contentResolver.getType(uri) ?: when (requiredType) {
                    "video" -> "video/mp4"
                    "audio" -> "audio/mpeg"
                    "file" -> "application/pdf"
                    else -> "image/jpeg"
                }
                require(if (requiredType == "file") mime == "application/pdf" else mime.startsWith("$requiredType/")) {
                    "نوع الملف غير مدعوم"
                }
                val url = withContext(Dispatchers.IO) { uploadChatMedia(ctx, token, uri, mime, requiredType) }
                post("/api/message-send", JSONObject().put("to", receiver)
                    .put("text", "").put("media", url).put("mediaType", requiredType))
                if (chatWith == receiver && tab == "الرسائل")
                    messages = get("/api/conversation?with=${enc(receiver)}").arr("messages")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "فشل إرسال المرفق" }
            finally { busy = false }
        }
    }
    fun changeOwnProfileImage(kind: String, uri: Uri) {
        if (token.isBlank()) return
        task {
            val data = withContext(Dispatchers.IO) { encodeProfileImage(ctx, uri, kind) }
            val updated = post("/api/profile", JSONObject().put(kind, data))
            // Server returns the updated user, but reload if an older deployment omits it.
            profile = updated.optJSONObject("user") ?: get("/api/me").optJSONObject("user") ?: JSONObject()
            profileBio = profile.optString("bio")
            myAvatar = profile.optString("avatar")
            featureMessage = if (kind == "avatar") "تم تحديث الصورة الشخصية على الموقع والتطبيق" else "تم تحديث صورة الغلاف على الموقع والتطبيق"
        }
    }
    val profileAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) changeOwnProfileImage("avatar", uri)
    }
    val profileCoverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) changeOwnProfileImage("cover", uri)
    }
    val chatImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendSelectedChatMedia(uri, "image")
    }
    val chatVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendSelectedChatMedia(uri, "video")
    }
    val chatAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendSelectedChatMedia(uri, "audio")
    }
    val chatPdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendSelectedChatMedia(uri, "file")
    }

    // A single lightweight authenticated WebSocket receives incoming calls in the
    // foreground. WebRTC transport itself is active only while a call screen is open.
    LaunchedEffect(token, myUsername) {
        if (token.isBlank() || myUsername.isBlank()) return@LaunchedEffect
        val socketClient = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
        try {
            while (isActive) {
                val done = CompletableDeferred<Unit>()
                val socket = socketClient.newWebSocket(
                    Request.Builder().url("wss://tawasol-alatta.omaratta077-4fd.workers.dev/ws?token=${enc(token)}").build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val msg = try { JSONObject(text) } catch (_: Exception) { return }
                            val signal = if (msg.optString("type") == "chat" && msg.optString("mediaType") == "call-signal")
                                try { JSONObject(msg.optString("media")).apply {
                                    if (!has("from")) put("from", msg.optString("from"))
                                    if (!has("to")) put("to", msg.optString("to"))
                                } } catch (_: Exception) { null }
                                else msg
                            val isCall = signal?.optString("type") == "call-request"
                            val sender = signal?.optString("from").orEmpty()
                            val callId = signal?.optString("callId").orEmpty()
                            if (isCall && sender.isNotBlank() && sender != myUsername && callId.isNotBlank()) {
                                val age = System.currentTimeMillis() - signal!!.optLong("createdAt", 0L)
                                if (age in 0..45000L) scope.launch(Dispatchers.Main) {
                                    if (knownCalls.add(callId)) {
                                        val invite = NativeCallSession(sender,
                                            signal.optString("callType", "audio"), callId, true)
                                        if (activeCall == null && incomingCall == null) incomingCall = invite
                                        else sendCallResponse(invite, "busy")
                                    }
                                }
                            } else if (msg.optString("type") == "chat" && msg.optString("mediaType") != "call-signal") {
                                val from = msg.optString("from"); val to = msg.optString("to")
                                scope.launch(Dispatchers.Main) {
                                    val other = chatWith
                                    if (tab == "الرسائل" && other.isNotBlank() && (from == other || to == other)) {
                                        try { messages = get("/api/conversation?with=${enc(other)}").arr("messages") }
                                        catch (_: Exception) { }
                                    }
                                }
                            }
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(Unit) }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { done.complete(Unit) }
                    }
                )
                try { done.await() } finally { socket.close(1000, "closing") }
                delay(2500)
            }
        } finally { socketClient.dispatcher.executorService.shutdown() }
    }
    suspend fun load(which: String) {
        when (which) {
            "الرئيسية" -> { posts = get("/api/posts?limit=40").arr("posts"); stories = get("/api/stories").arr("stories"); try { notifications = get("/api/notifications").arr("notifications") } catch (_: Exception) { } }
            "استكشاف" -> posts = get("/api/explore").arr("posts")
            "الأصدقاء" -> { users = get("/api/users?q=${enc(search)}").arr("users"); friends = get("/api/friends").arr("friends"); requests = get("/api/friend-requests").arr("requests") }
            "الرسائل" -> {
                if (chatWith.isNotBlank()) {
                    messages = get("/api/conversation?with=${enc(chatWith)}").arr("messages")
                } else {
                    inboxLoading = true
                    try {
                        val people = get("/api/friends").arr("friends")
                        friends = people
                        // Use the existing API; don't invent online/read states.
                        val limiter = Semaphore(5)
                        val cached = coroutineScope {
                            people.take(45).map { user ->
                                async {
                                    limiter.acquire()
                                    try {
                                        val id = user.optString("username")
                                        val last = try {
                                            get("/api/conversation?with=${enc(id)}").arr("messages")
                                                .lastOrNull { it.optString("mediaType") != "call-signal" }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { null }
                                        ChatPreview(user, last)
                                    } finally { limiter.release() }
                                }
                            }.awaitAll()
                        }
                        inboxPreviews = cached
                    } finally { inboxLoading = false }
                }
            }
            "الإشعارات" -> notifications = get("/api/notifications").arr("notifications")
            "المجموعات" -> groups = get("/api/groups").arr("groups")
            "الملف" -> { profile = get("/api/me").optJSONObject("user") ?: JSONObject(); profileBio = profile.optString("bio"); myAvatar = profile.optString("avatar") }
            "المحفوظات" -> posts = get("/api/saved-posts").arr("posts")
            "يوتيوب" -> youtubeItems = withContext(Dispatchers.IO) { api("/api/youtube?q=${enc(youtubeSearch)}") }.arr("items")
        }
    }
    fun refresh() = task { load(tab) }
    fun navigate(destination: String) {
        if (tab != destination) { visited.add(tab); tab = destination }
    }
    BackHandler(enabled = token.isNotBlank()) {
        when {
            profilePeer.isNotBlank() -> { profilePeer = ""; publicProfile = null }
            tab == "الرسائل" && chatWith.isNotBlank() -> {
                chatWith = ""; messages = emptyList()
                task { load("الرسائل") }
            }
            visited.isNotEmpty() -> tab = visited.removeAt(visited.lastIndex)
            tab != "الرئيسية" -> tab = "الرئيسية"
            else -> confirmExit = true
        }
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text("إغلاق تواصل العطا؟") },
        text = { Text("هل تريد الخروج من التطبيق؟") },
        confirmButton = { TextButton(onClick = { confirmExit = false; (ctx as? ComponentActivity)?.finish() }) { Text("خروج") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("إلغاء") } }
    )
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        containerColor = Panel,
        titleContentColor = GoldLight,
        textContentColor = White,
        title = { Text("تسجيل الخروج") },
        text = { Text("هل تريد تسجيل الخروج من حسابك على هذا الجهاز؟") },
        confirmButton = {
            TextButton(onClick = {
                confirmLogout = false
                activeCall = null; incomingCall = null
                chatWith = ""; messages = emptyList(); inboxPreviews = emptyList()
                posts = emptyList(); notifications = emptyList(); friends = emptyList()
                profile = JSONObject(); displayName = ""; myUsername = ""; myAvatar = ""
                visited.clear(); tab = "الرئيسية"
                token = ""
                prefs.edit().clear().apply()
            }) { Text("تسجيل الخروج", color = GoldLight) }
        },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("إلغاء", color = White) } }
    )
    if (changePasswordDialog) AlertDialog(
        onDismissRequest = { changePasswordDialog = false; currentPassword = ""; nextPassword = ""; nextPasswordAgain = "" },
        title = { Text("تغيير كلمة السر", color = GoldLight) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PremiumAuthTextField(currentPassword, { currentPassword = it }, "كلمة المرور الحالية", password = true)
                PremiumAuthTextField(nextPassword, { nextPassword = it }, "كلمة المرور الجديدة", password = true)
                PremiumAuthTextField(nextPasswordAgain, { nextPasswordAgain = it }, "تأكيد كلمة المرور الجديدة", password = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                task {
                    post("/api/change-password", JSONObject()
                        .put("oldPassword", currentPassword)
                        .put("newPassword", nextPassword)
                        .put("confirmPassword", nextPasswordAgain))
                    currentPassword = ""; nextPassword = ""; nextPasswordAgain = ""
                    changePasswordDialog = false
                    token = ""; prefs.edit().clear().apply()
                    featureMessage = "تم تغيير كلمة المرور. سجل الدخول بكلمة المرور الجديدة."
                }
            }, enabled = !busy && currentPassword.isNotBlank() && nextPassword.length >= 8 && nextPassword == nextPasswordAgain) {
                Text("حفظ", color = GoldLight)
            }
        },
        dismissButton = { TextButton(onClick = { changePasswordDialog = false }) { Text("إلغاء") } }
    )
    incomingCall?.let { invitation ->
        AlertDialog(
            onDismissRequest = { sendCallResponse(invitation, "rejected"); incomingCall = null },
            title = { Text(if (invitation.type == "video") "مكالمة فيديو واردة" else "مكالمة صوتية واردة", color = GoldLight) },
            text = { Text("${invitation.peer} يتصل بك عبر تواصل العطا") },
            confirmButton = { TextButton(onClick = {
                incomingCall = null
                openCall(invitation.peer, invitation.type, true, invitation.callId)
            }) { Text("رد", color = GoldLight) } },
            dismissButton = { TextButton(onClick = {
                sendCallResponse(invitation, "rejected"); incomingCall = null
            }) { Text("رفض") } }
        )
    }
    if (featureMessage.isNotBlank()) AlertDialog(
        onDismissRequest = { featureMessage = "" },
        title = { Text("معلومة") }, text = { Text(featureMessage) },
        confirmButton = { TextButton(onClick = { featureMessage = "" }) { Text("حسنًا") } }
    )
    if (showStoryComposer) AlertDialog(
        onDismissRequest = { showStoryComposer = false },
        title = { Text("إضافة قصة") },
        text = { OutlinedTextField(storyText, { storyText = it }, label = { Text("اكتب قصتك") }) },
        confirmButton = {
            TextButton(onClick = {
                showStoryComposer = false
                if (storyText.isNotBlank()) task {
                    post("/api/stories", JSONObject().put("text", storyText))
                    storyText = ""; load("الرئيسية")
                }
            }, enabled = storyText.isNotBlank()) { Text("نشر القصة") }
        },
        dismissButton = { TextButton(onClick = { showStoryComposer = false }) { Text("إلغاء") } }
    )
    selectedStory?.let { st ->
        AlertDialog(
            onDismissRequest = { selectedStory = null },
            title = { Text(st.optJSONObject("authorInfo")?.name() ?: "قصة") },
            text = {
                Column {
                    Text(st.optString("text"))
                    val storyMedia = media(st.optString("media"))
                    if (storyMedia.isNotBlank()) {
                        if (storyMedia.contains(".mp4", true)) NativeVideo(storyMedia)
                        else ProfileMediaImage(storyMedia, "القصة", Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedStory = null }) { Text("إغلاق") } }
        )
    }
    // Fetch our actual profile photo after login, even when opening the home feed first.
    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            try { myAvatar = get("/api/me").optJSONObject("user")?.optString("avatar").orEmpty() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* UI can still use the account initial. */ }
        } else myAvatar = ""
    }
    LaunchedEffect(token, tab) {
        if (token.isNotBlank()) {
            try { load(tab) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "تعذر التحميل" }
        }
    }
    LaunchedEffect(Unit) {
        try {
            val conf = withContext(Dispatchers.IO) { api("/api/google-config") }
            googleEnabled = conf.optBoolean("enabled")
            googleClientId = conf.optString("clientId")
            nativeUsernameSignup = conf.optBoolean("nativeUsernameSignup", false)
            birthDateSignup = conf.optBoolean("birthDateSignup", false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { googleEnabled = false }
    }

    // Lightweight refresh of the opened chat; messages stay on Cloudflare.
    LaunchedEffect(tab, chatWith, token) {
        if (tab == "الرسائل" && chatWith.isNotBlank() && token.isNotBlank()) {
            val other = chatWith
            while (true) {
                delay(6000)
                try {
                    val latest = get("/api/conversation?with=${enc(other)}").arr("messages")
                    if (chatWith == other && tab == "الرسائل") messages = latest
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Manual refresh remains available. */ }
            }
        }
    }

    LaunchedEffect(youtubeId, tab) {
        if (tab == "يوتيوب" && youtubeId.isNotBlank()) {
            youtubeListState.animateScrollToItem(1)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Black, surface = Panel, onSurface = White, onBackground = White)) {
            Box(Modifier.fillMaxSize().background(Black)) {
            Column(Modifier.fillMaxSize().background(Black).safeDrawingPadding()) {
                if (token.isBlank()) {
                    // Scroll only the authentication screen; Android navigation remains unobstructed.
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp, vertical = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(painterResource(R.drawable.logo_premium), "شعار العطا",
                            Modifier.size(98.dp).clip(CircleShape))
                        Text("AL-ATTA", color = GoldLight, fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
                        Spacer(Modifier.height(8.dp))
                        if (authMode == "login") {
                            PremiumAuthTextField(username, { username = it }, "الهاتف أو البريد أو اسم المستخدم")
                            PremiumAuthTextField(password, { password = it }, "كلمة السر", password = true)
                            PremiumAuthGoldButton("تسجيل الدخول", !busy && username.isNotBlank() && password.isNotBlank()) {
                                task {
                                    val result = withContext(Dispatchers.IO) {
                                        api("/api/login", JSONObject().put("username", username.trim()).put("password", password))
                                    }
                                    applySession(result, username.trim())
                                }
                            }
                            Text("أو اختر حساب Google من داخل التطبيق", color = GoldLight,
                                fontSize = 12.sp, textAlign = TextAlign.Center)
                            PremiumGoogleButton(enabled = !busy && googleEnabled, label = "الدخول بحساب Google") {
                                chooseGoogle(false)
                            }
                            if (!googleEnabled) Text("يتطلب Google Play Services وإعداد OAuth لتطبيق Android.", color = Muted, fontSize = 11.sp)
                            TextButton(onClick = { authMode = "forgot" }) {
                                Text("هل نسيت كلمة السر؟", color = GoldLight, fontWeight = FontWeight.Bold)
                            }
                            PremiumAuthOutlineButton("إنشاء حساب جديد") { authMode = "register"; error = "" }
                        } else if (authMode == "register") {
                            Text("إنشاء حساب جديد", color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            PremiumGoogleButton(enabled = !busy && googleEnabled, label = "إنشاء حساب عن طريق Google") {
                                chooseGoogle(true)
                            }
                            if (!googleEnabled) Text("سيظهر اختيار حساب Google مباشرة داخل التطبيق", color = Muted, fontSize = 11.sp)
                            Text("أو أنشئ حسابًا بالبريد الإلكتروني", color = GoldLight, fontSize = 13.sp)
                            PremiumAuthTextField(regName, { regName = it }, "الاسم الكامل")
                            PremiumAuthTextField(regUsername, { regUsername = it.lowercase().filter { c -> c.isLetterOrDigit() && c.code < 128 || c == '_' }.take(24) }, "اختر اسم مستخدم فريد (مطلوب)")
                            Text("اختَر معرّفًا فريدًا لتسجيل الدخول؛ الاسم الكامل وحده هو الذي يظهر للآخرين.",
                                color = Muted, fontSize = 11.sp)
                            AttaBirthDatePicker(regBirthDate, { regBirthDate = it })
                            PremiumAuthTextField(regPhone, { regPhone = it }, "رقم الهاتف (اختياري)")
                            PremiumAuthTextField(regEmail, { regEmail = it }, "البريد الإلكتروني لاستقبال كود التأكيد")
                            PremiumAuthTextField(regGender, { regGender = it }, "النوع (اختياري)")
                            PremiumAuthTextField(regCountry, { regCountry = it }, "الدولة (اختياري)")
                            PremiumAuthTextField(regCity, { regCity = it }, "المدينة (اختياري)")
                            PremiumAuthTextField(regPass, { regPass = it }, "كلمة المرور", password = true)
                            PremiumAuthTextField(regConfirm, { regConfirm = it }, "تأكيد كلمة المرور", password = true)
                            if (!birthDateSignup) Text("إنشاء حساب بتاريخ الميلاد يحتاج نشر تحديث Cloudflare v1.0.14 أولًا.", color = Color(0xFFFFC78E), fontSize = 12.sp)
                            PremiumAuthGoldButton("إرسال كود التأكيد", !busy && birthDateSignup && regName.trim().length >= 3 && regUsername.matches(Regex("[a-z0-9_]{3,24}")) && attaAgeFromBirthDate(regBirthDate) != null && regEmail.isNotBlank() && regPass.isNotBlank()) {
                                task {
                                    val body = JSONObject()
                                        .put("fullName", regName.trim()).put("username", regUsername.trim())
                                        .put("birthDate", regBirthDate).put("age", attaAgeFromBirthDate(regBirthDate) ?: 0).put("phone", regPhone.trim())
                                        .put("email", regEmail.trim()).put("gender", regGender.trim())
                                        .put("country", regCountry.trim()).put("city", regCity.trim()).put("password", regPass)
                                        .put("confirmPassword", regConfirm)
                                    val result = withContext(Dispatchers.IO) { api("/api/register-request", body) }
                                    verificationId = result.getString("verificationId")
                                    verificationDelivery = result.optString("delivery", regEmail)
                                    authMode = "verify"
                                }
                            }
                            PremiumAuthOutlineButton("العودة لتسجيل الدخول") { authMode = "login"; error = "" }
                        } else if (authMode == "verify") {
                            Text("تأكيد البريد الإلكتروني", color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("أرسلنا كودًا من 6 أرقام إلى $verificationDelivery", color = Muted,
                                fontSize = 13.sp, textAlign = TextAlign.Center)
                            PremiumAuthTextField(verificationCode, { verificationCode = it.filter(Char::isDigit).take(6) }, "كود التأكيد")
                            PremiumAuthGoldButton("تأكيد وإنشاء الحساب", !busy && verificationCode.length == 6) {
                                task {
                                    val result = withContext(Dispatchers.IO) {
                                        api("/api/register-verify", JSONObject().put("verificationId", verificationId).put("code", verificationCode))
                                    }
                                    applySession(result, regUsername)
                                }
                            }
                            TextButton(onClick = {
                                task {
                                    withContext(Dispatchers.IO) { api("/api/register-resend", JSONObject().put("verificationId", verificationId)) }
                                    featureMessage = "تم إرسال كود جديد إلى بريدك الإلكتروني"
                                }
                            }) { Text("إعادة إرسال الكود", color = GoldLight) }
                            PremiumAuthOutlineButton("الرجوع") { authMode = "register" }
                        } else if (authMode == "googleProfile") {
                            Text("إنشاء حساب عن طريق Google", color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("البريد الذي تم تأكيده بواسطة Google: $googleEmail", color = Muted,
                                textAlign = TextAlign.Center, fontSize = 13.sp)
                            Text("الاسم المقترح من Google قابل للتعديل:", color = GoldLight, fontSize = 13.sp)
                            PremiumAuthTextField(googleName, { googleName = it }, "الاسم الذي يظهر للآخرين")
                            PremiumAuthTextField(googleUsername,
                                { googleUsername = it.lowercase().filter { c -> (c in 'a'..'z') || (c in '0'..'9') || c == '_' }.take(24) },
                                "اختر اسم مستخدم لحسابك")
                            Text("المعرّف لا يظهر تحت اسمك؛ الاسم الكامل الذي تختاره هو الظاهر للناس.",
                                color = Muted, fontSize = 11.sp)
                            AttaBirthDatePicker(googleBirthDate, { googleBirthDate = it })
                            PremiumAuthTextField(googleCity, { googleCity = it.take(80) }, "المدينة (اختياري)")
                            Text("الحسابات الحالية بواسطة Google ستفتح مباشرة دون تغيير بياناتها", color = Muted, fontSize = 11.sp)
                            if (!nativeUsernameSignup) Text(
                                "إنشاء حساب Google جديد يحتاج تفعيل اختيار اسم المستخدم على خادم Cloudflare أولًا. حسابات Google الحالية تستمر في الدخول بشكل طبيعي.",
                                color = Color(0xFFFFC78E), fontSize = 12.sp)
                            PremiumAuthGoldButton("تأكيد إنشاء حساب Google", !busy && nativeUsernameSignup && birthDateSignup &&
                                googleIdToken.isNotBlank() && googleUsername.matches(Regex("[a-z0-9_]{3,24}")) &&
                                googleName.trim().length >= 2 && attaAgeFromBirthDate(googleBirthDate) != null) {
                                task {
                                    val data = JSONObject()
                                        .put("credential", googleIdToken)
                                        .put("signupFlow", "android-signup")
                                        .put("fullName", googleName.trim())
                                        .put("username", googleUsername.trim())
                                        .put("birthDate", googleBirthDate)
                                        .put("age", attaAgeFromBirthDate(googleBirthDate))
                                        .put("city", googleCity.trim())
                                    val result = withContext(Dispatchers.IO) { api("/api/google-login", data) }
                                    require(result.optJSONObject("user")?.optString("username") == googleUsername.trim()) {
                                        "الخادم لم يحفظ اسم المستخدم الذي اخترته؛ راجع تحديث Cloudflare."
                                    }
                                    applySession(result, googleName)
                                }
                            }
                            PremiumAuthOutlineButton("رجوع") { googleIdToken = ""; authMode = "register"; error = "" }
                        } else if (authMode == "forgot") {
                            Text("استعادة كلمة السر", color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("أدخل البريد المرتبط بحسابك. سنرسل كودًا صالحًا لمدة 10 دقائق.", color = Muted, fontSize = 13.sp)
                            PremiumAuthTextField(resetEmail, { resetEmail = it }, "البريد الإلكتروني")
                            PremiumAuthGoldButton("إرسال كود استعادة كلمة السر", !busy && resetEmail.contains("@")) {
                                task {
                                    val result = withContext(Dispatchers.IO) {
                                        api("/api/password-reset-request", JSONObject().put("email", resetEmail.trim()))
                                    }
                                    resetRequestId = result.getString("requestId")
                                    resetCode = ""
                                    authMode = "resetVerify"
                                }
                            }
                            PremiumAuthOutlineButton("العودة لتسجيل الدخول") { authMode = "login"; error = "" }
                        } else if (authMode == "resetVerify") {
                            Text("تعيين كلمة مرور جديدة", color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("لو البريد مسجلًا بحساب بكلمة مرور، ستصلك رسالة بها كود التأكيد.", color = Muted,
                                textAlign = TextAlign.Center, fontSize = 13.sp)
                            PremiumAuthTextField(resetCode, { resetCode = it.filter(Char::isDigit).take(6) }, "كود التأكيد من 6 أرقام")
                            PremiumAuthTextField(resetPassword, { resetPassword = it }, "كلمة المرور الجديدة (8 أحرف على الأقل)", password = true)
                            PremiumAuthTextField(resetConfirm, { resetConfirm = it }, "تأكيد كلمة المرور الجديدة", password = true)
                            PremiumAuthGoldButton("تغيير كلمة السر", !busy && resetCode.length == 6 &&
                                resetPassword.length >= 8 && resetPassword == resetConfirm) {
                                task {
                                    val payload = JSONObject().put("requestId", resetRequestId)
                                        .put("code", resetCode).put("newPassword", resetPassword)
                                        .put("confirmPassword", resetConfirm)
                                    withContext(Dispatchers.IO) { api("/api/password-reset-confirm", payload) }
                                    resetPassword = ""; resetConfirm = ""; resetCode = ""; resetRequestId = ""
                                    authMode = "login"
                                    featureMessage = "تم تحديث كلمة المرور. ادخل الآن باستخدام كلمة المرور الجديدة."
                                }
                            }
                            TextButton(onClick = { authMode = "forgot" }) { Text("لم يصل الكود؟ اطلب كودًا جديدًا", color = GoldLight) }
                            PremiumAuthOutlineButton("العودة لتسجيل الدخول") { authMode = "login"; error = "" }
                        }
                        HorizontalDivider(color = Border.copy(alpha = .7f))
                        Text("العطا", color = GoldLight, fontSize = 14.sp)
                        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Gold)
                        if (error.isNotBlank()) Text(error, color = Color(0xFFFF8888), textAlign = TextAlign.Center)
                    }
                } else {
                    // Persistent authenticated header, independent of the selected tab.
                    // Keeps the AL-ATTA logo beside the app name (not the username),
                    // with message, notification and unboxed YouTube icons.
                    if (tab != "الرسائل") PremiumHeader(
                        current = tab,
                        unreadNotifications = notifications.count { !it.optBoolean("read", false) },
                        onNavigate = ::navigate
                    )
                    if (tab == "الرئيسية" || tab == "استكشاف") {
                        PremiumSearchBar { navigate("استكشاف") }
                    }
                    if (tab != "الرئيسية" && tab != "الرسائل") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tab, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            if (tab != "القائمة") TextButton(onClick = { refresh() }) { Text("تحديث", fontSize = 12.sp) }
                        }
                    }
                    when (tab) {
                        "الرئيسية", "استكشاف", "المحفوظات" -> LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (tab == "الرئيسية") {
                                item {
                                    CardBox {
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                            Avatar(myAvatar, displayName.ifBlank { myUsername })
                                            Text(displayName.ifBlank { myUsername }, color = White,
                                                fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                                modifier = Modifier.weight(1f), maxLines = 1)
                                            GoldButton("نشر", !busy && (draft.isNotBlank() || pickedUri != null)) {
                                                task {
                                                    var image = ""; var video = ""
                                                    pickedUri?.let { uri ->
                                                        val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf() }
                                                        require(bytes.size <= 50 * 1024 * 1024) { "الحد الأقصى 50 ميجابايت" }
                                                        val mime = pickedMime.ifBlank { "image/jpeg" }
                                                        val url = withContext(Dispatchers.IO) { upload(token, uri, mime, uri.lastPathSegment ?: "upload", bytes) }
                                                        if (mime.startsWith("video/")) video = url else image = url
                                                    }
                                                    post("/api/posts", JSONObject().put("text", draft).put("image", image).put("video", video))
                                                    draft = ""; pickedUri = null; load("الرئيسية")
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(draft, { draft = it },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 74.dp),
                                            placeholder = { Text("بم تفكر؟", fontSize = 14.sp) },
                                            minLines = 2, shape = RoundedCornerShape(12.dp))
                                        pickedUri?.let { Text("مرفق: ${it.lastPathSegment ?: "ملف"}", color = GoldLight, fontSize = 11.sp) }
                                        Spacer(Modifier.height(8.dp))
                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            PremiumOutlineAction("image", "صورة") { picker.launch("image/*") }
                                            PremiumOutlineAction("video", "فيديو") { picker.launch("video/*") }
                                            PremiumOutlineAction("chart", "استطلاع") {
                                                featureMessage = "إنشاء الاستطلاعات يحتاج دعمًا من واجهة السيرفر، ولم يتم تفعيله بعد."
                                            }
                                            PremiumOutlineAction("smile", "مشاركة مشاعر") {
                                                featureMessage = "مشاركة المشاعر كمنشور خاص لم تُفعّل بعد."
                                            }
                                        }
                                    }
                                }
                                item {
                                    CardBox {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("القصص", color = White, fontSize = 17.sp,
                                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                            Text("عرض الكل", color = GoldLight, fontSize = 12.sp,
                                                modifier = Modifier.clickable {
                                                    featureMessage = "القائمة تعرض القصص المتاحة حاليًا من السيرفر."
                                                })
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            StoryRing("إضافة قصة", "", true) { showStoryComposer = true }
                                            stories.take(20).forEach { st ->
                                                val storyPhoto = media(st.optString("media").ifBlank { st.optString("image") })
                                                StoryRing(st.optJSONObject("authorInfo")?.name() ?: "مستخدم", storyPhoto, false) {
                                                    selectedStory = st
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            items(posts, key = { it.optString("id", it.hashCode().toString()) }) { p ->
                                val author = p.optJSONObject("authorInfo") ?: JSONObject()
                                val postId = p.optString("id")
                                var comment by remember(postId) { mutableStateOf("") }
                                var showComments by remember(postId) { mutableStateOf(false) }
                                val remoteLiked = p.optBoolean("likedByMe")
                                var likedNow by remember(postId, remoteLiked) { mutableStateOf(remoteLiked) }
                                val visibleLikeCount = (p.optInt("likeCount") +
                                    (if (likedNow) 1 else 0) - (if (remoteLiked) 1 else 0)).coerceAtLeast(0)
                                CardBox {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Avatar(author.optString("avatar"), author.name(), onClick = { openPublicProfile(author.optString("username")) })
                                        Column { Text(author.name().ifBlank { p.optString("author") }, color = GoldLight, fontWeight = FontWeight.Bold); /* المعرّف الداخلي مخفي؛ يظهر الاسم الحقيقي فقط. */ }
                                    }
                                    if (p.optString("text").isNotBlank()) Text(p.optString("text"), color = White, modifier = Modifier.padding(vertical = 10.dp))
                                    val image = media(p.optString("image")); val video = media(p.optString("video"))
                                    if (image.isNotBlank()) ProfileMediaImage(image, "صورة المنشور", Modifier.fillMaxWidth().padding(vertical = 6.dp).heightIn(min = 120.dp, max = 450.dp).clip(RoundedCornerShape(11.dp)).border(.6.dp, Border.copy(alpha = .75f), RoundedCornerShape(11.dp)), contentScale = ContentScale.Fit)
                                    if (video.isNotBlank()) NativeVideo(video)
                                    p.optJSONObject("sharedPost")?.let { shared -> Text("مشاركة: ${shared.optString("text")}", color = GoldLight) }
                                    HorizontalDivider(color = Border.copy(alpha = .5f), modifier = Modifier.padding(vertical = 7.dp))
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            PremiumReactionAction(
                                                count = visibleLikeCount,
                                                liked = likedNow,
                                                onLike = {
                                                    likedNow = !likedNow // Gold feedback without waiting for the network.
                                                    task {
                                                        try {
                                                            post("/api/post-like", JSONObject().put("postId", postId))
                                                            load(tab)
                                                        } catch (e: Exception) {
                                                            likedNow = !likedNow // Revert if the server rejects the request.
                                                            throw e
                                                        }
                                                    }
                                                },
                                                onReact = { reaction ->
                                                    task {
                                                        post("/api/post-react", JSONObject()
                                                            .put("postId", postId).put("reaction", reaction))
                                                        load(tab)
                                                    }
                                                }
                                            )
                                            PremiumPostAction("message-circle", p.optInt("commentCount").toString(), Muted) {
                                                showComments = !showComments
                                            }
                                            // The site 'repeat' arrows are the ONE and ONLY share control.
                                            PremiumPostAction("repeat", p.optInt("shareCount").toString(), GoldLight) {
                                                task { post("/api/post-share", JSONObject().put("postId", postId).put("text", "")); load(tab) }
                                            }
                                        }
                                        PremiumPostAction("bookmark",
                                            if (p.optBoolean("savedByMe")) "محفوظ" else "حفظ", GoldLight) {
                                            task { post("/api/post-save", JSONObject().put("postId", postId)); load(tab) }
                                        }
                                    }
                                    if (showComments) {
                                        p.arr("comments").takeLast(15).forEach { c -> Text("${c.optString("author")}: ${c.optString("text")}", modifier = Modifier.padding(4.dp)) }
                                        OutlinedTextField(comment, { comment = it }, label = { Text("اكتب تعليقًا") }, modifier = Modifier.fillMaxWidth())
                                        GoldButton("إرسال التعليق", !busy && comment.isNotBlank()) { task { post("/api/post-comment", JSONObject().put("postId", postId).put("text", comment)); comment = ""; load(tab) } }
                                    }
                                    if (p.optString("author") == myUsername) TextButton(onClick = { task { post("/api/post-delete", JSONObject().put("postId", postId)); load(tab) } }) { Text("حذف المنشور", color = Color(0xFFFF8B8B)) }
                                }
                            }
                        }
                        "القائمة" -> LazyColumn(
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                CardBox {
                                    SectionTitle("القائمة")
                                    Text("تواصل العطا", color = Muted, fontSize = 12.sp)
                                }
                            }
                            item {
                                CardBox {
                                    PremiumMenuEntry("user", "الملف الشخصي") { navigate("الملف") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("bookmark", "المنشورات المحفوظة") { navigate("المحفوظات") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("users", "المجموعات") { navigate("المجموعات") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("bell", "الإشعارات") { navigate("الإشعارات") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("lock", "تغيير كلمة السر") { changePasswordDialog = true }
                                }
                            }
                            item {
                                CardBox {
                                    PremiumMenuEntry("log-out", "تسجيل الخروج", danger = true) {
                                        confirmLogout = true
                                    }
                                }
                            }
                        }
                        "الأصدقاء" -> LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            item { SectionTitle("طلبات الصداقة") }
                            items(requests) { r ->
                                val u = r.optJSONObject("user") ?: JSONObject()
                                CardBox { Text(u.name(), color = GoldLight); Row {
                                    GoldButton("قبول") { task { post("/api/friend-action", JSONObject().put("username", u.optString("username")).put("action", "accept")); load(tab) } }
                                    TextButton(onClick = { task { post("/api/friend-action", JSONObject().put("username", u.optString("username")).put("action", "decline")); load(tab) } }) { Text("رفض") }
                                } }
                            }
                            item { SectionTitle("بحث عن أشخاص"); OutlinedTextField(search, { search = it }, label = { Text("اسم المستخدم") }, modifier = Modifier.fillMaxWidth()); GoldButton("بحث") { refresh() } }
                            items(users) { u ->
                                CardBox { Row(verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(u.optString("avatar"), u.name(), onClick = { openPublicProfile(u.optString("username")) }); Spacer(Modifier.width(8.dp)); Text(u.name(), modifier = Modifier.weight(1f))
                                    val state = u.optString("friendState")
                                    TextButton(onClick = { task { if (state == "incoming") post("/api/friend-action", JSONObject().put("username", u.optString("username")).put("action", "accept")) else post("/api/friend-request", JSONObject().put("username", u.optString("username"))); load(tab) } }, enabled = state != "friends" && state != "outgoing" && u.optString("username") != myUsername) { Text(when (state) { "friends" -> "صديق"; "outgoing" -> "مرسل"; "incoming" -> "قبول"; else -> "إضافة" }) }
                                } }
                            }
                            item { SectionTitle("أصدقائي") }
                            items(friends) { u -> CardBox { Text(u.name(), color = GoldLight); TextButton(onClick = { chatWith = u.optString("username"); navigate("الرسائل") }) { Text("مراسلة") } } }
                        }
                        "الرسائل" -> Box(Modifier.weight(1f)) {
                            if (chatWith.isBlank()) {
                                MessengerInbox(
                                    contacts = friends,
                                    inbox = inboxPreviews,
                                    loading = inboxLoading,
                                    onOpen = { username ->
                                        if (username.isNotBlank()) {
                                            messages = emptyList()
                                            chatText = ""
                                            chatWith = username
                                            task { messages = get("/api/conversation?with=${enc(username)}").arr("messages") }
                                        }
                                    },
                                    onProfile = { username -> openPublicProfile(username) },
                                    onRefresh = { refresh() }
                                )
                            } else {
                                val target = friends.firstOrNull { it.optString("username") == chatWith }
                                    ?: JSONObject().put("username", chatWith)
                                MessengerThread(
                                    person = target, fallbackUsername = chatWith,
                                    myUsername = myUsername, messages = messages,
                                    chatText = chatText, sending = busy,
                                    onTextChange = { chatText = it.take(6000) },
                                    onSend = {
                                        val receiver = chatWith
                                        val content = chatText.trim()
                                        if (content.isNotEmpty() && receiver.isNotBlank() && !busy) {
                                            task {
                                                post("/api/message-send", JSONObject()
                                                    .put("to", receiver).put("text", content))
                                                chatText = ""
                                                if (chatWith == receiver)
                                                    messages = get("/api/conversation?with=${enc(receiver)}").arr("messages")
                                            }
                                        }
                                    },
                                    onPickImage = { chatImagePicker.launch("image/*") },
                                    onPickVideo = { chatVideoPicker.launch("video/*") },
                                    onPickAudio = { chatAudioPicker.launch("audio/*") },
                                    onPickDocument = { chatPdfPicker.launch("application/pdf") },
                                    onProfile = { openPublicProfile(chatWith) },
                                    onVoiceCall = { openCall(chatWith, "audio") },
                                    onVideoCall = { openCall(chatWith, "video") },
                                    onBack = { chatWith = ""; messages = emptyList(); refresh() },
                                    onRefresh = { task { messages = get("/api/conversation?with=${enc(chatWith)}").arr("messages") } }
                                )
                            }
                        }
                        "الإشعارات" -> LazyColumn(Modifier.weight(1f).padding(8.dp)) {
                            item { SectionTitle("الإشعارات") }
                            items(notifications) { n -> CardBox {
                                Text("${n.optString("from")} • ${n.optString("type")}", color = GoldLight)
                                Text(n.optString("text"))
                            } }
                            item { GoldButton("تعليم الكل كمقروء") { task { post("/api/notifications/read", JSONObject()); load(tab) } } }
                        }
                        "المجموعات" -> LazyColumn(Modifier.weight(1f).padding(8.dp)) {
                            item { SectionTitle("المجموعات")
                                OutlinedTextField(groupName, { groupName = it }, label = { Text("اسم المجموعة الجديدة") }, modifier = Modifier.fillMaxWidth())
                                GoldButton("إنشاء مجموعة", groupName.isNotBlank()) { task { post("/api/groups", JSONObject().put("name", groupName).put("members", JSONArray())); groupName = ""; load(tab) } }
                            }
                            items(groups) { g -> CardBox {
                                Text(g.optString("name"), color = GoldLight, fontWeight = FontWeight.Bold)
                                Text("الأعضاء: ${g.optJSONArray("members")?.length() ?: 0}", color = Color.LightGray)
                            } }
                        }
                        "يوتيوب" -> LazyColumn(Modifier.weight(1f).padding(8.dp), state = youtubeListState) {
                            item { SectionTitle("يوتيوب داخل تواصل العطا"); OutlinedTextField(youtubeSearch, { youtubeSearch = it }, label = { Text("ابحث عن فيديو") }, modifier = Modifier.fillMaxWidth()); GoldButton("بحث") { refresh() } }
                            if (youtubeId.isNotBlank()) item { CardBox { YouTubePlayer(youtubeId); TextButton(onClick = { youtubeId = "" }) { Text("إغلاق المشغل") } } }
                            items(youtubeItems) { v -> CardBox {
                                AsyncImage(v.optString("thumbnail"), "صورة الفيديو", Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop)
                                Text(v.optString("title"), color = GoldLight, fontWeight = FontWeight.Bold)
                                Text(v.optString("channel"), color = Color.LightGray)
                                GoldButton("▶ تشغيل داخل التطبيق") {
                                    youtubeId = v.optString("id")
                                }
                            } }
                        }
                        "الملف" -> LazyColumn(Modifier.weight(1f).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item {
                                CardBox {
                                    SectionTitle("ملفي الشخصي")
                                    Spacer(Modifier.height(8.dp))
                                    Box(Modifier.fillMaxWidth().height(174.dp)
                                        .clip(RoundedCornerShape(16.dp)).background(Panel)) {
                                        if (media(profile.optString("cover")).isNotBlank()) {
                                            ProfileMediaImage(profile.optString("cover"), "صورة الغلاف",
                                                Modifier.fillMaxSize(), ContentScale.Crop)
                                        } else {
                                            Box(Modifier.fillMaxSize().background(
                                                Brush.horizontalGradient(listOf(Color(0xFF282014), Color(0xFF090C0F)))))
                                        }
                                        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                                            GoldButton("تغيير الغلاف", !busy) { profileCoverPicker.launch("image/*") }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Box(Modifier.size(83.dp).border(2.dp, Gold, CircleShape)
                                            .padding(3.dp).clip(CircleShape).background(Panel),
                                            contentAlignment = Alignment.Center) {
                                            if (media(profile.optString("avatar")).isNotBlank()) {
                                                ProfileMediaImage(profile.optString("avatar"), "الصورة الشخصية",
                                                    Modifier.fillMaxSize())
                                            } else Text(profile.name().take(1), color = GoldLight, fontSize = 35.sp)
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(profile.name(), color = White,
                                                fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.height(5.dp))
                                            GoldButton("تغيير الصورة", !busy) {
                                                profileAvatarPicker.launch("image/*")
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    OutlinedTextField(profileBio, { profileBio = it },
                                        label = { Text("نبذة عني") }, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(8.dp))
                                    GoldButton("حفظ النبذة", !busy) {
                                        task {
                                            val updated = post("/api/profile", JSONObject().put("bio", profileBio))
                                            profile = updated.optJSONObject("user") ?: profile
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    PremiumAuthOutlineButton("تغيير كلمة السر") { changePasswordDialog = true }
                                }
                            }
                        }
                    }
                    if (tab != "الرسائل" || chatWith.isBlank()) PremiumBottomNav(tab, ::navigate)
                }
                if (token.isNotBlank()) {
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Gold)
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF8B8B), modifier = Modifier.padding(12.dp))
                }
            }
            if (profilePeer.isNotBlank() && token.isNotBlank()) {
                Box(Modifier.fillMaxSize().background(Black).safeDrawingPadding()) {
                    PublicUserProfile(
                        username = profilePeer, profile = publicProfile, recentPosts = publicPosts,
                        isLoading = publicProfileLoading,
                        onBack = { profilePeer = ""; publicProfile = null },
                        onMessage = {
                            val peer = profilePeer
                            profilePeer = ""; publicProfile = null
                            chatWith = peer; chatText = ""; messages = emptyList()
                            navigate("الرسائل")
                            scope.launch { try { messages = get("/api/conversation?with=${enc(peer)}").arr("messages") }
                                catch (e: Exception) { error = e.message ?: "تعذر تحميل المحادثة" } }
                        },
                        onVoiceCall = { openCall(profilePeer, "audio") },
                        onVideoCall = { openCall(profilePeer, "video") }
                    )
                }
            }
            activeCall?.let { session ->
                Box(Modifier.fillMaxSize().background(Black).safeDrawingPadding()) {
                    NativeCallScreen(session, token, myUsername,
                        onClose = { activeCall = null })
                }
            }
            }
        }
    }
}
