package com.alatta.nativeapp

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.widget.FrameLayout
import android.view.WindowManager
import android.view.ViewGroup
import android.view.View
import android.graphics.Color as AndroidColor
import android.app.Activity
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
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
        if (status !in 200..299 || obj.optBoolean("ok", true) == false) {
            val serverMessage = obj.optString("error").ifBlank { "HTTP $status" }
            val googleSignup = path.startsWith("/api/google-login") &&
                payload?.optString("signupFlow") == "android-signup"
            // Legacy Cloudflare Worker used this generic message exclusively for
            // the `await this.user(username)` conflict (HTTP 409). Recognise it
            // only on the Google signup request; do not disguise other failures.
            val duplicateUsername = status == 409 && googleSignup && (
                obj.optString("code") == "USERNAME_TAKEN" ||
                serverMessage.trim() == "تعذر إنشاء الحساب. تواصل مع الإدارة."
            )
            throw IllegalStateException(if (duplicateUsername)
                "اسم المستخدم موجود بالفعل، اختر اسمًا آخر." else serverMessage)
        }
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
    // Notification taps work for a cold start AND for an existing singleTask Activity.
    internal var incomingPush by mutableStateOf<PushCall?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumePushIntent(intent)
        setContent { NativeApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePushIntent(intent)
    }

    private fun consumePushIntent(intent: Intent?) {
        if (intent?.action != AttaPush.ACTION_OPEN_CALL) return
        val event = PushCall.fromIntent(intent) ?: return
        incomingPush = event
        AttaPush.dismiss(this, event.callId)
        // Clear extras so a normal Activity recreation cannot replay an accepted call.
        intent.removeExtra("atta_call_id")
    }

    fun clearIncomingPush() { incomingPush = null }
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
    private var orientationBeforeYoutubeFullscreen: Int? = null

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
        orientationBeforeYoutubeFullscreen = activity.requestedOrientation
        container.addView(view, android.view.ViewGroup.LayoutParams(-1, -1))
        // Fullscreen requested from inside the YouTube WebView player.
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    override fun onHideCustomView() {
        fullscreenView?.let { root?.removeView(it) }
        owner?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // Return to the device/app orientation that was in effect before fullscreen.
        orientationBeforeYoutubeFullscreen?.let { previous ->
            owner?.requestedOrientation = previous
        }
        orientationBeforeYoutubeFullscreen = null
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

/** Android uses the *site's actual YouTube panel*, not an injected or separate
 * player. The only iframe is created by cloudflare-site/public/youtube-section.js
 * using cloudflare-site/public/youtube-player-shared.js on the HTTPS site origin.
 * The local JS is an interaction/diagnostics bridge and never creates an iframe.
 */
@Composable private fun YouTubePlayer(id: String) {
    val safeId = id.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return
    val ctx = LocalContext.current
    var loading by remember(safeId) { mutableStateOf(true) }
    var frameLoaded by remember(safeId) { mutableStateOf(false) }
    var playbackConfirmed by remember(safeId) { mutableStateOf(false) }
    var error by remember(safeId) { mutableStateOf("") }
    var lastEvent by remember(safeId) { mutableStateOf("بدء التحميل") }

    // A small DOM bridge drives the existing site button and search form.
    // No duplicate iframe implementation is shipped in the Android APK.
    val bridgeJs = remember(ctx) {
        ctx.assets.open("atta-site-youtube-bridge.js").bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    val playOnSite = remember(safeId, bridgeJs) {
        bridgeJs.replace("__ALATTA_VIDEO_ID__", safeId)
    }
    val chrome = remember(ctx, safeId) {
        AttaYoutubeFullscreenChrome(ctx) { event ->
            lastEvent = event.take(140)
            when {
                event == "FRAME_LOADED" -> {
                    frameLoaded = true
                    loading = false
                }
                event == "PLAYING" -> {
                    frameLoaded = true
                    playbackConfirmed = true
                    loading = false
                    error = ""
                }
                event.startsWith("PLAYER_ERROR:") -> {
                    loading = false
                    val code = event.removePrefix("PLAYER_ERROR:")
                    error = when (code) {
                        "153" -> "رفض YouTube التشغيل داخل WebView (153: تعذّر التحقق من مصدر المشغّل)."
                        "101", "150" -> "صاحب الفيديو لا يسمح بالتشغيل داخل التطبيقات (رمز $code)."
                        else -> "مشغّل YouTube أبلغ عن خطأ رقم $code."
                    }
                }
                event.startsWith("ERROR:") -> {
                    loading = false
                    error = "تعذّر تشغيل مشغّل الموقع: " + event.removePrefix("ERROR:").take(120)
                }
            }
        }
    }
    val webView = remember(ctx, safeId) {
        WebView(ctx).apply {
            setBackgroundColor(AndroidColor.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = chrome
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == BASE || url?.startsWith("$BASE/") == true) {
                        // Do not overwrite the site's document: it contains the
                        // real working player event handlers and CSS.
                        view?.evaluateJavascript(playOnSite, null)
                    } else {
                        loading = false
                        error = "لم تُفتح صفحة تواصل العطا الأصلية داخل WebView."
                    }
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, issue: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        loading = false
                        error = "تعذّر تحميل الموقع داخل WebView (${issue?.errorCode})."
                    }
                }
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: android.webkit.WebResourceResponse?) {
                    val code = response?.statusCode ?: return
                    if (code < 400) return
                    if (request?.isForMainFrame == true) {
                        loading = false
                        error = "موقع تواصل العطا أعاد HTTP $code."
                    } else {
                        val host = request?.url?.host.orEmpty()
                        val path = request?.url?.path.orEmpty()
                        if ((host == "www.youtube-nocookie.com" || host == "www.youtube.com") && path.startsWith("/embed/")) {
                            loading = false
                            error = "YouTube رفض طلب المشغّل (HTTP $code)."
                            lastEvent = "EMBED_HTTP_$code"
                        }
                    }
                }
            }
            loadUrl("$BASE/")
        }
    }
    LaunchedEffect(webView, safeId) {
        kotlinx.coroutines.delay(20000)
        if (!playbackConfirmed && error.isBlank()) {
            loading = false
            error = if (frameLoaded)
                "ظهر إطار YouTube، لكن لم يتم تأكيد تشغيل الفيديو داخل Android WebView."
            else "الموقع لم يُكمل فتح مشغّل YouTube داخل Android WebView."
        }
    }
    DisposableEffect(webView, chrome) {
        onDispose {
            chrome.release()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }
    @Composable fun PlayerSurface(modifier: Modifier) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            }, modifier = Modifier.fillMaxSize())
            if (loading) CircularProgressIndicator(color = Gold, modifier = Modifier.size(30.dp))
            // Keep failure details inside the 16:9 area; no toolbar under the player.
            if (error.isNotBlank()) Text(error, color = GoldLight, fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().background(Color.Black.copy(alpha = .88f)).padding(8.dp))
        }
    }
    // v1.0.57 playback path intentionally unchanged: the HTTPS site still
    // constructs the sole iframe via the existing site bridge.
    PlayerSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
}

/** Single row, 39dp-high search shared across page headers. */
@Composable private fun AttaCompactSearch(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(GoldLight),
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = White),
        modifier = modifier.height(39.dp),
        decorationBox = { innerTextField ->
            Row(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(13.dp))
                    .background(Panel)
                    .border(1.dp, Border, RoundedCornerShape(13.dp))
                    .padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isBlank()) Text(hint, color = Muted, fontSize = 12.sp, maxLines = 1)
                    innerTextField()
                }
                IconButton(onClick = onSearch, modifier = Modifier.size(32.dp)) {
                    SiteIcon("search", tint = GoldLight, size = 18.dp)
                }
            }
        }
    )
}

@Composable private fun Avatar(url: String, label: String, onClick: (() -> Unit)? = null) {
    if (media(url).isNotBlank()) ProfileMediaImage(value = url, description = label, modifier = Modifier.size(42.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).border(1.dp, Gold, CircleShape).clip(CircleShape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(42.dp).background(Gold, CircleShape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) { Text(label.take(1), color = Black, fontWeight = FontWeight.Bold) }
}

@Composable private fun NativeApp() {
    var updateCheckRequest by remember { mutableIntStateOf(0) }
    AppUpdatePrompt(updateCheckRequest)
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
    var googlePassword by remember { mutableStateOf("") }
    var googlePasswordConfirm by remember { mutableStateOf("") }
    var googleGender by remember { mutableStateOf("") }
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
    var confirmPostDelete by remember { mutableStateOf("") }
    var confirmBanUser by remember { mutableStateOf("") }
    var adminRole by remember { mutableStateOf("") }
    var adminUsers by remember { mutableStateOf(emptyList<JSONObject>()) }
    var adminPosts by remember { mutableStateOf(emptyList<JSONObject>()) }
    var archivedPosts by remember { mutableStateOf(emptyList<JSONObject>()) }
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
    // The Worker stores message history per conversation, not as a global inbox.
    // Remember peers locally so an existing conversation remains visible even
    // before a friend request is accepted. Never store message bodies locally.
    val recentChatPeers = remember { mutableStateListOf<String>() }
    var inboxLoading by remember { mutableStateOf(false) }
    var activeChatPerson by remember { mutableStateOf<JSONObject?>(null) }
    var ownPosts by remember { mutableStateOf(emptyList<JSONObject>()) }
    var chatText by remember { mutableStateOf("") }
    var activeCall by remember { mutableStateOf<NativeCallSession?>(null) }
    // Keep a call connected when the user returns to chats or browses the app.
    var callMinimized by remember { mutableStateOf(false) }
    var incomingCall by remember { mutableStateOf<NativeCallSession?>(null) }
    var pendingPermissionCall by remember { mutableStateOf<NativeCallSession?>(null) }
    val knownCalls = remember(token) { mutableSetOf<String>() }
    var profilePeer by remember { mutableStateOf("") }
    var publicProfile by remember { mutableStateOf<JSONObject?>(null) }
    var publicPosts by remember { mutableStateOf(emptyList<JSONObject>()) }
    var publicFriends by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var publicProfileLoading by remember { mutableStateOf(false) }
    var youtubeSearch by remember { mutableStateOf("") }
    var knowledgeSearch by remember { mutableStateOf("") }
    var youtubeItems by remember { mutableStateOf(emptyList<JSONObject>()) }
    var youtubeId by remember { mutableStateOf("") }
    // Choosing a suggestion only previews it. The trusted v1.0.57 WebView is
    // created ONLY when the user explicitly presses Play in the top preview.
    var youtubePlayRequested by remember { mutableStateOf(false) }
    var youtubeSelectedThumbnail by remember { mutableStateOf("") }
    var youtubeCategory by remember { mutableStateOf("قرآن") }
    val youtubeListState = rememberLazyListState()
    var profileBio by remember { mutableStateOf("") }
    var profileCountry by remember { mutableStateOf("") }
    var profileCity by remember { mutableStateOf("") }
    var profileEducation by remember { mutableStateOf("") }
    var profileWork by remember { mutableStateOf("") }
    var editProfileDetails by remember { mutableStateOf(false) }
    var allowFriendRequests by remember { mutableStateOf(true) }
    var accountPrivate by remember { mutableStateOf(false) }
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
            catch (e: Exception) {
                // Do not rewrite login errors as registration errors. The old
                // substring check matched the word "المستخدم" in a normal
                // invalid-password response and showed a false "username taken" error.
                // Only api() maps the explicitly identified Google-signup conflict.
                error = e.message ?: "تعذر إتمام العملية"
            }
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
        password = ""; regPass = ""; regConfirm = ""; googleIdToken = ""; googlePassword = ""; googlePasswordConfirm = ""
        resetPassword = ""; resetConfirm = ""; authMode = "login"; tab = "الرئيسية"
    }
    fun chooseGoogle(signup: Boolean) {
        if (busy) return
        task {
            // The sign-in button must never be a silent, permanently disabled control.
            // Retry the public configuration at tap time (e.g. after temporary network loss).
            var clientId = googleClientId
            if (!googleEnabled || clientId.isBlank()) {
                val conf = withContext(Dispatchers.IO) { api("/api/google-config") }
                googleEnabled = conf.optBoolean("enabled", false)
                googleClientId = conf.optString("clientId")
                nativeUsernameSignup = conf.optBoolean("nativeUsernameSignup", false)
                birthDateSignup = conf.optBoolean("birthDateSignup", false)
                clientId = googleClientId
            }
            require(googleEnabled && clientId.endsWith(".apps.googleusercontent.com")) {
                "Google غير مفعّل على الخادم. راجع GOOGLE_CLIENT_ID وإعداد OAuth ثم أعد المحاولة."
            }
            val pickedToken = GoogleNativeSignIn.pickAccount(ctx as ComponentActivity, clientId)
                ?: return@task
            val claims = googleTokenDetails(pickedToken) // Hints only: server validates the token.
            fun showGoogleProfile() {
                googleIdToken = pickedToken
                googleEmail = claims.optString("email")
                googleName = claims.optString("name").ifBlank { claims.optString("given_name") }
                googleBirthDate = ""
                googleCity = ""
                googleUsername = ""
                googlePassword = ""
                googlePasswordConfirm = ""
                googleGender = ""
                authMode = "googleProfile"
            }
            // One validated server request decides whether this Google identity already
            // owns an account. Existing users log in; new users complete required fields.
            // New-account registration MUST NOT go through the email OTP endpoints.
            try {
                val result = withContext(Dispatchers.IO) {
                    api("/api/google-login", JSONObject()
                        .put("credential", pickedToken)
                        .put("signupFlow", if (signup) "android-signup-check" else "android-login"))
                }
                applySession(result, googleName.ifBlank { "Google" })
            } catch (e: IllegalStateException) {
                val message = e.message.orEmpty()
                val needsProfile = message.contains("USERNAME_REQUIRED") ||
                    message.contains("اختر اسم مستخدم") ||
                    message.contains("لا يوجد حساب Google سابق")
                if (needsProfile) showGoogleProfile() else throw e
            }
        }
    }
    fun rememberChatPeer(peer: String) {
        val key = peer.trim().lowercase()
        if (key.isBlank() || key == myUsername.lowercase() || key.startsWith("g_")) return
        recentChatPeers.remove(key)
        recentChatPeers.add(0, key)
        while (recentChatPeers.size > 45) recentChatPeers.removeAt(recentChatPeers.lastIndex)
        prefs.edit().putString("recent_chats_${myUsername.lowercase()}",
            JSONArray(recentChatPeers).toString()).apply()
    }
    suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) { api("$path${if ('?' in path) "&" else "?"}token=${enc(token)}") }
    suspend fun post(path: String, data: JSONObject): JSONObject = withContext(Dispatchers.IO) { api(path, data.put("token", token)) }
    val callPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val next = pendingPermissionCall
        pendingPermissionCall = null
        if (next != null && granted.values.all { it }) {
            activeCall = next
            callMinimized = false
        }
        else {
            featureMessage = "يجب السماح بالميكروفون والكاميرا عند مكالمة الفيديو. لو لم يظهر طلب الإذن، افتح إعدادات التطبيق ← الأذونات وفعّل الكاميرا والميكروفون."
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
        if (needed.isEmpty()) { activeCall = session; callMinimized = false }
        else { pendingPermissionCall = session; callPermissions.launch(needed.toTypedArray()) }
    }
    fun openPublicProfile(other: String) {
        if (other.isBlank() || token.isBlank()) return
        profilePeer = other
        publicProfile = null; publicPosts = emptyList(); publicFriends = emptyList(); publicProfileLoading = true
        scope.launch {
            try {
                val detail = get("/api/profile-public?username=${enc(other)}")
                // Only the recent public posts returned by the existing API are shown.
                val latest = get("/api/posts?limit=50").arr("posts").filter {
                    it.optString("author").equals(other, ignoreCase = true)
                }
                val friendsOfUser = try { get("/api/friends?username=${enc(other)}").arr("friends") } catch (_: Exception) { emptyList() }
                if (profilePeer == other) { publicProfile = detail; publicPosts = latest; publicFriends = friendsOfUser }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (profilePeer == other) featureMessage = e.message ?: "تعذر فتح صفحة المستخدم" }
            finally { if (profilePeer == other) publicProfileLoading = false }
        }
    }
    fun sendCallRinging(session: NativeCallSession) {
        // The remote device really received the invite and displayed its incoming dialog.
        // Do NOT signal "ringing" merely because the caller initiated a request.
        scope.launch {
            try {
                val data = JSONObject().put("type", "call-ringing").put("to", session.peer)
                    .put("from", myUsername).put("callId", session.callId)
                    .put("callType", session.type)
                    .put("signalId", UUID.randomUUID().toString())
                    .put("createdAt", System.currentTimeMillis())
                post("/api/message-send", JSONObject().put("to", session.peer)
                    .put("text", "").put("media", data.toString())
                    .put("mediaType", "call-signal"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* If signal delivery fails, caller stays at "جارٍ الاتصال". */ }
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
                rememberChatPeer(receiver)
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
                        // The Worker provides conversations by peer, not a unified inbox.
                        // Include previously contacted peers alongside accepted friends.
                        val known = people.map { it.optString("username").lowercase() }
                        val peerIds = (recentChatPeers.toList() + known)
                            .filter { it.isNotBlank() && it != myUsername.lowercase() && !it.startsWith("g_") }
                            .distinct().take(50)
                        val byId = people.associateBy { it.optString("username").lowercase() }
                        val limiter = Semaphore(5)
                        val cached = coroutineScope {
                            peerIds.map { id ->
                                async {
                                    limiter.acquire()
                                    try {
                                        val person = byId[id] ?: try {
                                            get("/api/profile-public?username=${enc(id)}").optJSONObject("user")
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { null } ?: JSONObject().put("username", id)
                                        val last = try {
                                            get("/api/conversation?with=${enc(id)}").arr("messages")
                                                .lastOrNull { it.optString("mediaType") != "call-signal" }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { null }
                                        ChatPreview(person, last)
                                    } finally { limiter.release() }
                                }
                            }.awaitAll()
                        }
                        inboxPreviews = cached
                    } finally { inboxLoading = false }
                }
            }
            "القائمة" -> {
                // Fetch current server-granted role whenever the menu is opened.
                val current = get("/api/me").optJSONObject("user")
                adminRole = current?.optString("role").orEmpty().trim().lowercase()
            }
            "الإشعارات" -> notifications = get("/api/notifications").arr("notifications")
            "المجموعات" -> groups = get("/api/groups").arr("groups")
            "الملف" -> {
                ownPosts = get("/api/posts?limit=100").arr("posts").filter { it.optString("author").equals(myUsername, ignoreCase = true) }
                profile = get("/api/me").optJSONObject("user") ?: JSONObject()
                profileBio = profile.optString("bio")
                profileCountry = profile.optString("country")
                profileCity = profile.optString("city")
                profileEducation = profile.optString("education")
                profileWork = profile.optString("work")
                allowFriendRequests = profile.optBoolean("allowFriendRequests", true)
                accountPrivate = profile.optBoolean("accountPrivate", false)
                myAvatar = profile.optString("avatar")
            }
            "المحفوظات" -> posts = get("/api/saved-posts").arr("posts")
            "الإدارة" -> {
                if (adminRole == "super_admin" || adminRole == "moderator") {
                    adminUsers = get("/api/admin/users").arr("users")
                    adminPosts = get("/api/admin/posts").arr("posts")
                }
            }
            "أرشيفي" -> archivedPosts = get("/api/post-archive-list").arr("posts")
            "يوتيوب" -> {
                val recommendation = when (youtubeCategory) {
                    "أحاديث" -> "أحاديث نبوية صحيحة شرح"
                    "رياضة" -> "كرة القدم ملخص مباريات"
                    "أخبار" -> "أخبار الجزيرة اليوم"
                    else -> "تلاوة القرآن الكريم"
                }
                val q = youtubeSearch.trim().ifBlank { recommendation }
                youtubeItems = withContext(Dispatchers.IO) {
                    api("/api/youtube?q=${enc(q)}")
                }.arr("items")
            }
        }
    }

    // The same invitation processor is used by realtime WebSocket and authenticated
    // HTTP polling. A transient socket disconnection must not lose incoming calls.
    fun handleIncomingCallEvent(signal: JSONObject) {
        val kind = signal.optString("type")
        val sender = signal.optString("from").trim().lowercase()
        val callId = signal.optString("callId").trim()
        if (sender.isBlank() || sender == myUsername.lowercase() || callId.isBlank()) return
        if (kind == "call-request") {
            val timestamp = signal.optLong("createdAt", signal.optLong("ts", 0L))
            val age = System.currentTimeMillis() - timestamp
            if (age !in 0..45000L || !knownCalls.add(callId)) return
            val invite = NativeCallSession(sender,
                if (signal.optString("callType") == "video") "video" else "audio", callId, true)
            if (activeCall == null && incomingCall == null) {
                incomingCall = invite
                sendCallRinging(invite) // Ringback only after the incoming dialog is created.
            } else sendCallResponse(invite, "busy")
        } else if (kind == "hangup" || kind == "call-reject") {
            val pending = incomingCall
            if (pending?.callId == callId && pending.peer.equals(sender, ignoreCase = true))
                incomingCall = null
        }
    }

    // A push notification is a wake-up hint, never a bypass around our logged-in
    // identity, call expiry, or friendship/signaling authorization.
    val activity = ctx as? MainActivity
    val incomingPush = activity?.incomingPush
    LaunchedEffect(incomingPush, token, myUsername) {
        val push = incomingPush ?: return@LaunchedEffect
        if (token.isBlank() || myUsername.isBlank()) return@LaunchedEffect
        activity.clearIncomingPush()
        if (push.to != myUsername.lowercase() ||
            System.currentTimeMillis() - push.createdAt !in 0..45000L) return@LaunchedEffect
        val session = NativeCallSession(push.from, push.type, push.callId, incoming = true)
        if (push.action == AttaPush.ACTION_ACCEPT) {
            if (activeCall == null) openCall(push.from, push.type, true, push.callId)
        } else if (activeCall == null && incomingCall == null && knownCalls.add(push.callId)) {
            incomingCall = session
            sendCallRinging(session)
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* A denied permission means no heads-up notification; foreground calls remain. */ }
    LaunchedEffect(token, myUsername) {
        if (token.isNotBlank() && myUsername.isNotBlank()) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            AttaPush.register(ctx.applicationContext, token, myUsername)
        }
    }

    // Foreground fallback: poll only small call invitations every three seconds.
    // The backend's queue is authenticated, one-time, and scoped to this user.
    // Background delivery is handled separately by FirebaseMessagingService when configured.
    LaunchedEffect(token, myUsername) {
        if (token.isBlank() || myUsername.isBlank()) return@LaunchedEffect
        while (isActive) {
            if ((ctx as ComponentActivity).lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                try {
                    get("/api/call-signals").arr("signals").forEach { handleIncomingCallEvent(it) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* WebSocket is still the primary transport. */ }
            }
            delay(3000L)
        }
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
                            if (signal?.optString("type") in setOf("call-request", "hangup", "call-reject")) {
                                val packet = signal!!
                                scope.launch(Dispatchers.Main) { handleIncomingCallEvent(packet) }
                            } else if (msg.optString("type") == "chat" && msg.optString("mediaType") != "call-signal") {
                                val from = msg.optString("from").lowercase()
                                val to = msg.optString("to").lowercase()
                                scope.launch(Dispatchers.Main) {
                                    val peer = if (from == myUsername.lowercase()) to else from
                                    if (peer.isNotBlank()) rememberChatPeer(peer)
                                    val opened = chatWith.lowercase()
                                    if (tab == "الرسائل" && opened.isNotBlank() && peer == opened) {
                                        try { messages = get("/api/conversation?with=${enc(opened)}").arr("messages") }
                                        catch (_: Exception) { }
                                    } else if (tab == "الرسائل" && chatWith.isBlank()) {
                                        try { load("الرسائل") } catch (_: Exception) { }
                                    }
                                }
                            } else if (msg.optString("type") in setOf("friend-request", "friend-accepted", "friends-changed")) {
                                scope.launch(Dispatchers.Main) {
                                    try {
                                        friends = get("/api/friends").arr("friends")
                                        requests = get("/api/friend-requests").arr("requests")
                                        notifications = get("/api/notifications").arr("notifications")
                                        if (tab == "الأصدقاء") users = get("/api/users?q=${enc(search)}").arr("users")
                                        if (tab == "الرسائل" && chatWith.isBlank()) load("الرسائل")
                                        if (profilePeer.isNotBlank()) publicProfile = get("/api/profile-public?username=${enc(profilePeer)}")
                                    } catch (_: Exception) { }
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
    LaunchedEffect(tab, search, token) {
        if ((tab == "استكشاف" || tab == "الأصدقاء") && token.isNotBlank()) {
            delay(320)
            try { users = get("/api/users?q=${enc(search.trim())}").arr("users") }
            catch (_: Exception) { users = emptyList() }
        }
    }
    fun refresh() = task { load(tab) }
    fun navigate(destination: String) {
        if (tab != destination) { visited.add(tab); tab = destination }
    }
    fun openChat(peer: String) {
        val id = peer.trim().lowercase()
        if (id.isBlank() || id == myUsername.lowercase()) return
        rememberChatPeer(id)
        chatWith = id
        chatText = ""
        messages = emptyList()
        activeChatPerson = friends.firstOrNull { it.optString("username").equals(id, true) }
            ?: inboxPreviews.firstOrNull { it.username.equals(id, true) }?.user
        navigate("الرسائل")
        scope.launch {
            try {
                val response = get("/api/conversation?with=${enc(id)}").arr("messages")
                if (chatWith == id) messages = response
                if (activeChatPerson == null || activeChatPerson?.optString("fullName").isNullOrBlank()) {
                    val person = get("/api/profile-public?username=${enc(id)}").optJSONObject("user")
                    if (chatWith == id && person != null) activeChatPerson = person
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (chatWith == id) error = e.message ?: "تعذر تحميل المحادثة" }
        }
    }
    fun updateFriend(target: String, action: String) {
        val id = target.trim().lowercase()
        if (id.isBlank() || id == myUsername.lowercase()) return
        task {
            if (action == "request") post("/api/friend-request", JSONObject().put("username", id))
            else post("/api/friend-action", JSONObject().put("username", id).put("action", action))
            friends = get("/api/friends").arr("friends")
            requests = get("/api/friend-requests").arr("requests")
            if (tab == "الأصدقاء") users = get("/api/users?q=${enc(search)}").arr("users")
            if (profilePeer == id) publicProfile = get("/api/profile-public?username=${enc(id)}")
            if (tab == "الرسائل" && chatWith.isBlank()) load("الرسائل")
        }
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
                AttaPush.unregister(ctx.applicationContext, token)
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
        // Start the device's ringtone and optional vibration while this dialog
        // is on screen; accepting or rejecting disposes and silences the alert.
        IncomingCallRingtone()
        AlertDialog(
            onDismissRequest = { sendCallResponse(invitation, "rejected"); incomingCall = null },
            containerColor = Color(0xFF13181C),
            titleContentColor = GoldLight,
            textContentColor = White,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(25.dp),
            title = { Text(if (invitation.type == "video") "مكالمة فيديو واردة" else "مكالمة صوتية واردة", fontWeight = FontWeight.Bold, color = GoldLight) },
            text = { Text("مكالمة واردة من مستخدم تواصل العطا", color = White) },
            confirmButton = { Button(
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black),
                onClick = {
                    incomingCall = null
                    openCall(invitation.peer, invitation.type, true, invitation.callId)
                }
            ) { Text("رد", fontWeight = FontWeight.Bold) } },
            dismissButton = { OutlinedButton(
                border = BorderStroke(1.dp, Border),
                onClick = { sendCallResponse(invitation, "rejected"); incomingCall = null }
            ) { Text("رفض", color = White) } }
        )
    }
    if (featureMessage.isNotBlank()) AlertDialog(
        onDismissRequest = { featureMessage = "" },
        title = { Text("معلومة") }, text = { Text(featureMessage) },
        confirmButton = {
            if (featureMessage.contains("الأذونات")) TextButton(onClick = {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}"))
                ctx.startActivity(intent)
                featureMessage = ""
            }) { Text("فتح إعدادات الأذونات") }
            else TextButton(onClick = { featureMessage = "" }) { Text("حسنًا") }
        },
        dismissButton = { TextButton(onClick = { featureMessage = "" }) { Text("إغلاق") } }
    )
    if (showStoryComposer) AlertDialog(
        onDismissRequest = { showStoryComposer = false },
        containerColor = Panel,
        titleContentColor = GoldLight,
        textContentColor = White,
        shape = RoundedCornerShape(24.dp),
        title = { Text("إضافة قصة ✨", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("شارك أصدقاءك لحظة جميلة", color = Muted, fontSize = 14.sp)
                OutlinedTextField(
                    value = storyText, onValueChange = { storyText = it },
                    label = { Text("اكتب قصتك", color = Muted) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 18.sp),
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Gold, unfocusedBorderColor = Border,
                        focusedTextColor = White, unfocusedTextColor = White, cursorColor = Gold,
                        focusedLabelColor = GoldLight, unfocusedLabelColor = Muted)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                showStoryComposer = false
                if (storyText.isNotBlank()) task {
                    post("/api/stories", JSONObject().put("text", storyText))
                    storyText = ""; load("الرئيسية")
                }
            }, enabled = storyText.isNotBlank()) { Text("نشر القصة", color = if (storyText.isNotBlank()) GoldLight else Muted) }
        },
        dismissButton = { TextButton(onClick = { showStoryComposer = false }) { Text("إلغاء", color = Muted) } }
    )
    selectedStory?.let { st ->
        AlertDialog(
            onDismissRequest = { selectedStory = null },
            containerColor = Panel, titleContentColor = GoldLight,
            textContentColor = White, shape = RoundedCornerShape(24.dp),
            title = { Text(st.optJSONObject("authorInfo")?.name() ?: "قصة", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(st.optString("text"), color = White, fontSize = 18.sp, lineHeight = 28.sp)
                    val storyMedia = media(st.optString("media"))
                    if (storyMedia.isNotBlank()) {
                        if (storyMedia.contains(".mp4", true)) NativeVideo(storyMedia)
                        else ProfileMediaImage(storyMedia, "القصة", Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedStory = null }) { Text("إغلاق", color = GoldLight) } }
        )
    }
    if (confirmPostDelete.isNotBlank()) AlertDialog(
        onDismissRequest = { confirmPostDelete = "" }, containerColor = Panel,
        title = { Text("حذف المنشور؟", color = GoldLight) },
        text = { Text("الحذف نهائي ولن تتمكن من استعادة المنشور.", color = White) },
        confirmButton = { TextButton(onClick = {
            val id = confirmPostDelete; confirmPostDelete = ""
            task {
                if (adminRole == "super_admin" || adminRole == "moderator") {
                    post("/api/admin/action", JSONObject().put("action", "delete_post").put("postId", id))
                } else post("/api/post-delete", JSONObject().put("postId", id))
                load(tab)
            }
        }) { Text("حذف نهائي", color = Color(0xFFFF8B8B)) } },
        dismissButton = { TextButton(onClick = { confirmPostDelete = "" }) { Text("إلغاء", color = GoldLight) } }
    )
    if (confirmBanUser.isNotBlank()) AlertDialog(
        onDismissRequest = { confirmBanUser = "" }, containerColor = Panel,
        title = { Text("إيقاف الحساب؟", color = GoldLight) },
        text = { Text("سيُمنع هذا المستخدم من تسجيل الدخول حتى إعادة تفعيل حسابه.", color = White) },
        confirmButton = { TextButton(onClick = {
            val username = confirmBanUser; confirmBanUser = ""
            task {
                post("/api/admin/action", JSONObject().put("action", "ban").put("username", username))
                adminUsers = get("/api/admin/users").arr("users")
            }
        }) { Text("إيقاف الحساب", color = Color(0xFFFF8B8B)) } },
        dismissButton = { TextButton(onClick = { confirmBanUser = "" }) { Text("إلغاء", color = GoldLight) } }
    )
    LaunchedEffect(token, myUsername) {
        recentChatPeers.clear()
        if (token.isNotBlank() && myUsername.isNotBlank()) {
            try {
                val stored = JSONArray(prefs.getString("recent_chats_${myUsername.lowercase()}", "[]"))
                for (i in 0 until stored.length().coerceAtMost(45)) {
                    val peer = stored.optString(i).lowercase()
                    if (peer.isNotBlank() && peer != myUsername.lowercase() && peer !in recentChatPeers)
                        recentChatPeers.add(peer)
                }
            } catch (_: Exception) { }
        }
    }
    // Fetch our actual profile photo after login, even when opening the home feed first.
    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            try {
                val user = get("/api/me").optJSONObject("user")
                myAvatar = user?.optString("avatar").orEmpty()
                adminRole = user?.optString("role").orEmpty().trim().lowercase()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* UI can still use the account initial. */ }
        } else { myAvatar = ""; adminRole = "" }
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

    // The existing backend sends realtime socket events. Periodic reconciliation
    // covers short disconnections and restores changes made from the website.
    LaunchedEffect(tab, chatWith, token) {
        if (token.isBlank()) return@LaunchedEffect
        if (tab == "الأصدقاء") while (isActive) {
            delay(18000)
            try {
                friends = get("/api/friends").arr("friends")
                requests = get("/api/friend-requests").arr("requests")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        if (tab == "الرسائل" && chatWith.isBlank()) while (isActive) {
            delay(25000)
            try { load("الرسائل") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
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
            youtubeListState.animateScrollToItem(0)
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
                            PremiumGoogleButton(enabled = !busy, label = "الدخول بحساب Google") {
                                chooseGoogle(false)
                            }
                            if (!googleEnabled) Text("يتطلب خدمات Google Play وتطابق OAuth مع توقيع التطبيق. اضغط لإعادة التحقق.", color = Muted, fontSize = 11.sp)
                            TextButton(onClick = { authMode = "forgot" }) {
                                Text("هل نسيت كلمة السر؟", color = GoldLight, fontWeight = FontWeight.Bold)
                            }
                            PremiumAuthOutlineButton("إنشاء حساب جديد") { authMode = "register"; error = "" }
                        } else if (authMode == "register") {
                            Text("إنشاء حساب جديد", color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            PremiumGoogleButton(enabled = !busy, label = "إنشاء حساب عن طريق جوجل") {
                                chooseGoogle(true)
                            }
                            if (!googleEnabled) Text("اضغط Google للتحقق من الاتصال والإعداد. لو تعذر الاختيار ستظهر رسالة بالسبب.", color = Muted, fontSize = 11.sp)
                            Text("أو أنشئ حسابًا بالبريد الإلكتروني", color = GoldLight, fontSize = 13.sp)
                            PremiumAuthTextField(regName, { regName = it }, "الاسم الكامل")
                            PremiumAuthTextField(regUsername, { regUsername = it.lowercase().filter { c -> c.isLetterOrDigit() && c.code < 128 || c == '_' }.take(24) }, "اختر اسم مستخدم فريد (مطلوب)")
                            AttaBirthDatePicker(regBirthDate, { regBirthDate = it })
                            PremiumAuthTextField(regPhone, { regPhone = it }, "رقم الهاتف (اختياري)")
                            PremiumAuthTextField(regEmail, { regEmail = it }, "البريد الإلكتروني لاستقبال كود التأكيد")
                            PremiumAuthTextField(regGender, { regGender = it }, "النوع (اختياري)")
                            PremiumAuthTextField(regCountry, { regCountry = it }, "الدولة (اختياري)")
                            PremiumAuthTextField(regCity, { regCity = it }, "المدينة (اختياري)")
                            PremiumAuthTextField(regPass, { regPass = it }, "كلمة المرور", password = true)
                            PremiumAuthTextField(regConfirm, { regConfirm = it }, "تأكيد كلمة المرور", password = true)
                            if (!birthDateSignup) Text("إنشاء حساب بتاريخ الميلاد يحتاج نشر تحديث Cloudflare v1.0.14 أولًا.", color = Color(0xFFFFC78E), fontSize = 12.sp)
                            PremiumAuthGoldButton("إرسال كود التأكيد", !busy && birthDateSignup && regName.trim().length >= 3 && regUsername.matches(Regex("[a-z0-9_]{3,24}")) && attaAgeFromBirthDate(regBirthDate) != null && regEmail.isNotBlank() && regPass.length >= 6 && regPass == regConfirm) {
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
                            Box(Modifier.fillMaxWidth().border(1.dp, Gold, RoundedCornerShape(16.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFF4F3713), Color(0xFF241909))), RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
                                Text("إنشاء حساب عن طريق Google", color = GoldLight, fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            }
                            Text(googleEmail, color = Muted, textAlign = TextAlign.Center, fontSize = 13.sp)
                            PremiumAuthTextField(googleName, { googleName = it }, "الاسم الكامل")
                            PremiumAuthTextField(googleUsername,
                                { googleUsername = it.lowercase().filter { c -> (c in 'a'..'z') || (c in '0'..'9') || c == '_' }.take(24) },
                                "اسم المستخدم")
                            PremiumAuthTextField(googlePassword, { googlePassword = it }, "كلمة المرور (6 أحرف على الأقل)", password = true)
                            PremiumAuthTextField(googlePasswordConfirm, { googlePasswordConfirm = it }, "تأكيد كلمة المرور", password = true)
                            AttaBirthDatePicker(googleBirthDate, { googleBirthDate = it })
                            PremiumAuthTextField(googleCity, { googleCity = it.take(80) }, "المدينة (اختياري)")
                            PremiumAuthTextField(googleGender, { googleGender = it.take(20) }, "النوع: ذكر أو أنثى")
                            if (!nativeUsernameSignup || !birthDateSignup) Text(
                                "خادم Cloudflare الحالي لا يعلن دعمه لإكمال تسجيل Google. يجب نشر تحديث الخادم أولًا؛ لن نُنشئ اسم مستخدم تلقائيًا.",
                                color = Color(0xFFFFC78E), fontSize = 12.sp)
                            PremiumAuthGoldButton("تأكيد إنشاء الحساب", !busy && googleIdToken.isNotBlank()) {
                                when {
                                    !nativeUsernameSignup || !birthDateSignup ->
                                        error = "إعداد Cloudflare قديم: يجب نشر تحديث إنشاء حساب Google وتاريخ الميلاد أولًا. لن نرسل كود بريد مع Google."
                                    !googleUsername.matches(Regex("[a-z0-9_]{3,24}")) ->
                                        error = "اختر اسم مستخدم من 3 إلى 24 حرفًا إنجليزيًا أو رقمًا أو _."
                                    googleName.trim().length < 2 -> error = "اكتب اسمك الظاهر (حرفين على الأقل)."
                                    googlePassword.length < 8 -> error = "كلمة المرور يجب أن تتكون من 8 أحرف على الأقل."
                                    googlePassword != googlePasswordConfirm -> error = "تأكيد كلمة المرور غير مطابق."
                                    attaAgeFromBirthDate(googleBirthDate) == null -> error = "اختر تاريخ ميلاد صحيحًا من التقويم."
                                    else -> task {
                                        val data = JSONObject()
                                            .put("credential", googleIdToken)
                                            .put("signupFlow", "android-signup")
                                            .put("fullName", googleName.trim())
                                            .put("username", googleUsername.trim())
                                            .put("birthDate", googleBirthDate)
                                            .put("age", attaAgeFromBirthDate(googleBirthDate))
                                            .put("city", googleCity.trim())
                                            .put("gender", googleGender.trim())
                                            .put("password", googlePassword)
                                            .put("confirmPassword", googlePasswordConfirm)
                                        val result = withContext(Dispatchers.IO) { api("/api/google-login", data) }
                                        val actualUsername = result.optJSONObject("user")?.optString("username").orEmpty()
                                        require(actualUsername == googleUsername.trim()) {
                                            "الحساب مرتبط بالفعل باسم مختلف؛ ادخل بواسطة Google بدل إنشاء حساب جديد."
                                        }
                                        applySession(result, googleName)
                                    }
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
                    if (tab == "الرئيسية") PremiumHeader(
                        current = tab,
                        unreadNotifications = notifications.count { !it.optBoolean("read", false) },
                        onNavigate = ::navigate
                    )
                    if (tab == "الرئيسية" || tab == "استكشاف") {
                        if (tab == "استكشاف") {
                            OutlinedTextField(
                                value = search,
                                onValueChange = { search = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 4.dp),
                                singleLine = true,
                                placeholder = { Text("ابحث عن الأشخاص أو المنشورات...", color = Muted) },
                                leadingIcon = { SiteIcon("search", tint = GoldLight, size = 23.dp) },
                                shape = RoundedCornerShape(22.dp)
                            )
                        } // Home uses the search icon in the single-line header; no second search bar.
                    }
                    if (tab != "الرئيسية" && tab != "الرسائل") {
                        Row(Modifier.fillMaxWidth().height(47.dp)
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            val pageTitle = when (tab) {
                                "القرآن" -> "القرآن الكريم"
                                "الأحاديث" -> "الأحاديث النبوية"
                                "الرياضة" -> "كرة القدم"
                                else -> tab
                            }
                            Text(pageTitle, color = GoldLight, fontWeight = FontWeight.Bold,
                                fontSize = 15.sp, maxLines = 1,
                                modifier = Modifier.weight(1f))
                            if (tab in setOf("القرآن", "الأحاديث", "الأذكار", "يوتيوب", "الأصدقاء")) {
                                val isYoutube = tab == "يوتيوب"
                                val isFriends = tab == "الأصدقاء"
                                AttaCompactSearch(
                                    value = if (isYoutube) youtubeSearch else if (isFriends) search else knowledgeSearch,
                                    onValueChange = { value ->
                                        when {
                                            isYoutube -> youtubeSearch = value
                                            isFriends -> search = value
                                            else -> knowledgeSearch = value
                                        }
                                    },
                                    hint = if (isFriends) "بحث عن صديق" else "بحث",
                                    onSearch = { if (isYoutube || isFriends) refresh() },
                                    modifier = Modifier.width(184.dp)
                                )
                            }
                        }
                    }
                    when (tab) {
                        "القرآن", "الأحاديث", "الأذكار" -> KnowledgeScreen(tab, knowledgeSearch, Modifier.weight(1f))
                        "الرياضة" -> FootballScreen(Modifier.weight(1f))
                        "الرئيسية", "استكشاف", "المحفوظات" -> LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (tab == "استكشاف") {
                                item { SectionTitle("الأشخاص") }
                                if (users.isEmpty()) item { Text("لا يوجد أشخاص مطابقون للبحث", color = Muted, modifier = Modifier.padding(12.dp)) }
                                items(users.filter { u -> search.isBlank() || u.name().contains(search.trim(), ignoreCase = true) || u.optString("username").contains(search.trim(), ignoreCase = true) }) { u ->
                                    CardBox {
                                        Row(Modifier.fillMaxWidth().clickable { openPublicProfile(u.optString("username")) }, verticalAlignment = Alignment.CenterVertically) {
                                            Avatar(u.optString("avatar"), u.name(), onClick = { openPublicProfile(u.optString("username")) })
                                            Spacer(Modifier.width(10.dp))
                                            Text(u.name(), color = White, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                                item { SectionTitle("المنشورات") }
                            }
                            if (tab == "الرئيسية") {
                                item {
                                    CardBox {
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                            Avatar(myAvatar, displayName.ifBlank { myUsername }, onClick = { navigate("الملف") })
                                            Text(displayName.ifBlank { myUsername }, color = White,
                                                fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                                modifier = Modifier.weight(1f).clickable { navigate("الملف") }, maxLines = 1)
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
                            items(if (tab == "استكشاف" && search.isNotBlank()) posts.filter { p ->
                                p.optString("text").contains(search.trim(), ignoreCase = true) ||
                                p.optString("author").contains(search.trim(), ignoreCase = true) ||
                                (p.optJSONObject("authorInfo")?.name() ?: "").contains(search.trim(), ignoreCase = true)
                            } else posts, key = { it.optString("id", it.hashCode().toString()) }) { p ->
                                val author = p.optJSONObject("authorInfo") ?: JSONObject()
                                val postId = p.optString("id")
                                var comment by remember(postId) { mutableStateOf("") }
                                var showComments by remember(postId) { mutableStateOf(false) }
                                val remoteLiked = p.optBoolean("likedByMe")
                                val remoteReaction = p.optString("myReaction")
                                val serverReaction = if (remoteReaction.isNotBlank()) remoteReaction
                                    else if (remoteLiked) "like" else ""
                                val reactionCounts = p.optJSONObject("reactionCounts") ?: JSONObject()
                                val reactionTotal = listOf("like", "love", "haha", "wow", "sad", "angry", "dislike")
                                    .sumOf { reactionCounts.optInt(it) }
                                val baseTotal = p.optInt("likeCount") + reactionTotal
                                var selectedReaction by remember(postId, serverReaction) {
                                    mutableStateOf(serverReaction)
                                }
                                val visibleLikeCount = (baseTotal +
                                    (if (selectedReaction.isNotBlank()) 1 else 0) -
                                    (if (serverReaction.isNotBlank()) 1 else 0)).coerceAtLeast(0)
                                CardBox {
                                    var postMenuExpanded by remember(postId) { mutableStateOf(false) }
                                    val isMyPost = p.optString("author").equals(myUsername, ignoreCase = true)
                                    val canModerate = adminRole == "super_admin" || adminRole == "moderator"
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Avatar(author.optString("avatar"), author.name(), onClick = { openPublicProfile(p.optString("author")) })
                                        Text(author.name().ifBlank { p.optString("author") }, color = GoldLight,
                                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).clickable { openPublicProfile(p.optString("author")) })
                                        Box {
                                            IconButton(onClick = { postMenuExpanded = true }) {
                                                Text("⋮", color = GoldLight, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                            }
                                            DropdownMenu(expanded = postMenuExpanded, onDismissRequest = { postMenuExpanded = false },
                                                modifier = Modifier.background(Panel).border(1.dp, Border, RoundedCornerShape(10.dp))) {
                                                if (isMyPost) {
                                                    DropdownMenuItem(text = { Text("أرشفة المنشور", color = GoldLight) }, onClick = {
                                                        postMenuExpanded = false
                                                        task { post("/api/post-archive", JSONObject().put("postId", postId).put("archived", true)); load(tab) }
                                                    })
                                                }
                                                if (isMyPost || canModerate) {
                                                    DropdownMenuItem(text = { Text("حذف المنشور", color = Color(0xFFFF8B8B)) }, onClick = {
                                                        postMenuExpanded = false; confirmPostDelete = postId
                                                    })
                                                }
                                                if (!isMyPost) {
                                                    DropdownMenuItem(text = { Text("حظر صاحب المنشور", color = White) }, onClick = {
                                                        postMenuExpanded = false
                                                        task { post("/api/personal-block", JSONObject().put("username", p.optString("author")).put("action", "block")); load(tab) }
                                                    })
                                                    DropdownMenuItem(text = { Text("إلغاء الصداقة", color = White) }, onClick = {
                                                        postMenuExpanded = false
                                                        task { post("/api/friend-action", JSONObject().put("username", p.optString("author")).put("action", "remove")); load(tab) }
                                                    })
                                                }
                                                if (!isMyPost && canModerate) {
                                                    DropdownMenuItem(text = { Text("إيقاف حساب الناشر", color = Color(0xFFFF8B8B)) }, onClick = {
                                                        postMenuExpanded = false; confirmBanUser = p.optString("author")
                                                    })
                                                }
                                            }
                                        }
                                    }
                                    if (p.optString("text").isNotBlank()) Text(p.optString("text"), color = White, modifier = Modifier.padding(vertical = 10.dp))
                                    val image = media(p.optString("image")); val video = media(p.optString("video"))
                                    if (image.isNotBlank()) ExpandableProfileMedia(image, "صورة المنشور",
                                        Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                            .aspectRatio(1.15f).clip(RoundedCornerShape(11.dp))
                                            .border(.6.dp, Border.copy(alpha = .75f), RoundedCornerShape(11.dp)),
                                        contentScale = ContentScale.Crop)
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
                                                liked = selectedReaction == "like",
                                                selectedReaction = selectedReaction,
                                                onLike = {
                                                    val previous = selectedReaction
                                                    val next = if (previous == "like") "" else "like"
                                                    selectedReaction = next
                                                    task {
                                                        try {
                                                            if (previous == "like" && !remoteLiked) {
                                                                post("/api/post-react", JSONObject().put("postId", postId)
                                                                    .put("reaction", "like").put("remove", true))
                                                            } else {
                                                                post("/api/post-like", JSONObject().put("postId", postId))
                                                            }
                                                        } catch (e: Exception) {
                                                            selectedReaction = previous
                                                            throw e
                                                        }
                                                    }
                                                },
                                                onReact = { reaction ->
                                                    val previous = selectedReaction
                                                    selectedReaction = reaction
                                                    task {
                                                        try {
                                                            post("/api/post-react", JSONObject()
                                                                .put("postId", postId).put("reaction", reaction))
                                                        } catch (e: Exception) {
                                                            selectedReaction = previous
                                                            throw e
                                                        }
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
                                        p.arr("comments").takeLast(15).forEach { c ->
                                            val person = c.optJSONObject("authorInfo") ?: JSONObject()
                                            val label = person.optString("fullName").ifBlank { person.optString("displayName") }.ifBlank { c.optString("fullName") }.ifBlank { c.optString("displayName") }.ifBlank { c.optString("author") }
                                            Text("$label: ${c.optString("text")}", color = White, modifier = Modifier.padding(4.dp))
                                        }
                                        OutlinedTextField(comment, { comment = it }, label = { Text("اكتب تعليقًا") }, modifier = Modifier.fillMaxWidth())
                                        GoldButton("إرسال التعليق", !busy && comment.isNotBlank()) { task { post("/api/post-comment", JSONObject().put("postId", postId).put("text", comment)); comment = ""; load(tab) } }
                                    }
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
                                    if (adminRole == "super_admin" || adminRole == "moderator") {
                                        PremiumMenuEntry("shield", "لوحة الإدارة") { navigate("الإدارة") }
                                        HorizontalDivider(color = Border.copy(alpha = .5f))
                                    }
                                    if (adminRole != "super_admin" && adminRole != "moderator") {
                                        Text("صلاحية الإدارة غير مفعّلة لهذا الحساب على الخادم", color = Muted, fontSize = 12.sp)
                                        TextButton(onClick = { task {
                                            try { load("القائمة") }
                                            catch (e: Exception) { error = e.message ?: "تعذر فحص الصلاحية" }
                                        } }) { Text("تحديث صلاحية المدير", color = GoldLight) }
                                        HorizontalDivider(color = Border.copy(alpha = .5f))
                                    }
                                    PremiumMenuEntry("bookmark", "أرشيف منشوراتي") { navigate("أرشيفي") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("user", "الملف الشخصي") { navigate("الملف") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("bookmark", "المنشورات المحفوظة") { navigate("المحفوظات") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("users", "المجموعات") { navigate("المجموعات") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("bell", "الإشعارات") { navigate("الإشعارات") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("check", "فحص تحديث التطبيق") { updateCheckRequest++ }
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
                        "أرشيفي" -> LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item { SectionTitle("منشوراتي المؤرشفة") }
                            items(archivedPosts) { p ->
                                CardBox {
                                    Text(p.optString("text").ifBlank { "منشور بدون نص" }, color = White)
                                    TextButton(onClick = { task {
                                        post("/api/post-archive", JSONObject().put("postId", p.optString("id")).put("archived", false))
                                        archivedPosts = get("/api/post-archive-list").arr("posts")
                                    } }) { Text("استعادة المنشور", color = GoldLight) }
                                }
                            }
                        }
                        "الإدارة" -> LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item { SectionTitle("لوحة الإدارة • ${if (adminRole == "super_admin") "المدير الرئيسي" else "مشرف"}") }
                            if (adminRole == "super_admin" || adminRole == "moderator") {
                                item { SectionTitle("إدارة الحسابات") }
                                items(adminUsers) { u ->
                                    val username = u.optString("username")
                                    CardBox {
                                        Text(u.optString("displayName").ifBlank { username }, color = White, fontWeight = FontWeight.Bold)
                                        Text("@$username", color = Muted, fontSize = 12.sp)
                                        Row {
                                            if (u.optBoolean("banned")) TextButton(onClick = { task {
                                                post("/api/admin/action", JSONObject().put("action", "unban").put("username", username))
                                                adminUsers = get("/api/admin/users").arr("users")
                                            } }) { Text("إعادة تفعيل", color = GoldLight) }
                                            else if (username != myUsername && u.optString("role") != "super_admin") TextButton(onClick = { confirmBanUser = username }) { Text("إيقاف الحساب", color = Color(0xFFFF8B8B)) }
                                        }
                                    }
                                }
                                item { SectionTitle("إدارة المنشورات") }
                                items(adminPosts) { p ->
                                    CardBox {
                                        Text("@${p.optString("author")}", color = GoldLight)
                                        Text(p.optString("text"), color = White)
                                        TextButton(onClick = { confirmPostDelete = p.optString("id") }) {
                                            Text("حذف المنشور", color = Color(0xFFFF8B8B))
                                        }
                                    }
                                }
                            } else item { Text("هذه الصفحة متاحة للإدارة فقط", color = Muted) }
                        }
                        "الأصدقاء" -> LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            if (requests.isNotEmpty()) item { SectionTitle("طلبات الصداقة (${requests.size})") }
                            items(requests, key = { "request_" + it.optJSONObject("user")?.optString("username").orEmpty() }) { r ->
                                val u = r.optJSONObject("user") ?: JSONObject()
                                val peer = u.optString("username")
                                CardBox {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Avatar(u.optString("avatar"), u.name(), onClick = { openPublicProfile(peer) })
                                        Spacer(Modifier.width(8.dp))
                                        Text(u.name(), color = GoldLight, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        GoldButton("قبول", !busy) { updateFriend(peer, "accept") }
                                        TextButton(onClick = { updateFriend(peer, "decline") }, enabled = !busy) { Text("رفض") }
                                    }
                                }
                            }
                            if (users.any { u -> !u.optString("username").equals(myUsername, true) && u.optString("friendState") != "friends" }) {
                                item { SectionTitle(if (search.isBlank()) "اقتراحات الأصدقاء" else "نتائج البحث") }
                            }
                            items(users.filter { !it.optString("username").equals(myUsername, true) && it.optString("friendState") != "friends" },
                                key = { "user_" + it.optString("username") }) { u ->
                                val peer = u.optString("username")
                                val state = u.optString("friendState")
                                CardBox {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Avatar(u.optString("avatar"), u.name(), onClick = { openPublicProfile(peer) })
                                        Text(u.name(), color = White, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f).clickable { openPublicProfile(peer) },
                                            maxLines = 1)
                                        when (state) {
                                            "incoming" -> TextButton(onClick = { updateFriend(peer, "accept") }, enabled = !busy) {
                                                Text("قبول", color = GoldLight, fontSize = 12.sp)
                                            }
                                            "outgoing" -> TextButton(onClick = { updateFriend(peer, "cancel") }, enabled = !busy) {
                                                Text("إلغاء الطلب", color = Muted, fontSize = 12.sp)
                                            }
                                            else -> if (u.optBoolean("allowFriendRequests", true))
                                                TextButton(onClick = { updateFriend(peer, "request") }, enabled = !busy) {
                                                    Text("+ إضافة صديق", color = GoldLight, fontSize = 12.sp)
                                                }
                                        }
                                    }
                                }
                            }
                            item { SectionTitle("أصدقائي (${friends.size})") }
                            if (friends.isEmpty()) item {
                                Text("لم تضف أصدقاء بعد. ابحث عن شخص واضغط إضافة صديق.", color = Muted,
                                    modifier = Modifier.padding(12.dp))
                            }
                            items(friends, key = { "friend_" + it.optString("username") }) { u ->
                                val peer = u.optString("username")
                                CardBox {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Avatar(u.optString("avatar"), u.name(), onClick = { openPublicProfile(peer) })
                                        Text(u.name(), color = White, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f).clickable { openPublicProfile(peer) },
                                            maxLines = 1)
                                        IconButton(onClick = { openChat(peer) }, modifier = Modifier.size(38.dp)) {
                                            SiteIcon("message-circle", tint = GoldLight, size = 19.dp)
                                        }
                                        IconButton(onClick = { openCall(peer, "audio") }, modifier = Modifier.size(38.dp)) {
                                            SiteIcon("phone", tint = GoldLight, size = 19.dp)
                                        }
                                        IconButton(onClick = { openCall(peer, "video") }, modifier = Modifier.size(38.dp)) {
                                            SiteIcon("video", tint = GoldLight, size = 19.dp)
                                        }
                                    }
                                }
                            }
                        }
                        "الرسائل" -> Box(Modifier.weight(1f)) {
                            if (chatWith.isBlank()) {
                                MessengerInbox(
                                    contacts = friends,
                                    inbox = inboxPreviews,
                                    loading = inboxLoading,
                                    onOpen = { username -> openChat(username) },
                                    onProfile = { username -> openPublicProfile(username) },
                                    onRefresh = { refresh() }
                                )
                            } else {
                                val target = activeChatPerson?.takeIf { it.optString("username") == chatWith }
                                    ?: friends.firstOrNull { it.optString("username") == chatWith }
                                    ?: groups.firstOrNull { it.optString("id") == chatWith || it.optString("groupId") == chatWith }
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
                                                rememberChatPeer(receiver)
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
                                    onBack = { chatWith = ""; activeChatPerson = null; messages = emptyList(); refresh() },
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
                        "يوتيوب" -> LazyColumn(Modifier.weight(1f).padding(8.dp), state = youtubeListState,
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (youtubeId.isNotBlank()) item(key = "selected_youtube_video") {
                                if (youtubePlayRequested) {
                                    // Do not change YouTubePlayer's working v1.0.57 site WebView.
                                    YouTubePlayer(youtubeId)
                                } else {
                                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black), contentAlignment = Alignment.Center) {
                                        if (youtubeSelectedThumbnail.isNotBlank()) {
                                            AsyncImage(youtubeSelectedThumbnail, "صورة الفيديو المحدد",
                                                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        }
                                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)))
                                        GoldButton("▶ تشغيل الفيديو") { youtubePlayRequested = true }
                                    }
                                }
                            }
                            item(key = "youtube_recommendation_topics") {
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("اقتراحات", color = GoldLight,
                                        fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        listOf("قرآن", "أحاديث", "رياضة", "أخبار").forEach { category ->
                                            val selected = category == youtubeCategory && youtubeSearch.isBlank()
                                            OutlinedButton(onClick = {
                                                youtubeCategory = category
                                                youtubeSearch = ""
                                                // Leave the selected video untouched while browsing topics.
                                                task { load("يوتيوب") }
                                            }, shape = RoundedCornerShape(18.dp),
                                                border = BorderStroke(1.dp, if (selected) Gold else Border),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    containerColor = if (selected) Color(0xFF3D2D13) else Panel,
                                                    contentColor = if (selected) GoldLight else Muted),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                modifier = Modifier.height(34.dp)) {
                                                Text(category, fontSize = 12.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                            items(youtubeItems) { v -> CardBox {
                                val id = v.optString("id")
                                val pickVideo = {
                                    if (id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                                        youtubeId = id
                                        youtubeSelectedThumbnail = v.optString("thumbnail")
                                        youtubePlayRequested = false
                                    }
                                }
                                AsyncImage(v.optString("thumbnail"), "اختيار الفيديو وعرضه بالأعلى",
                                    Modifier.fillMaxWidth().height(165.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(onClick = pickVideo),
                                    contentScale = ContentScale.Fit)
                                Text(v.optString("title"), color = GoldLight, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable(onClick = pickVideo))
                                Text(v.optString("channel"), color = Color.LightGray, fontSize = 12.sp)
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
                                            ExpandableProfileMedia(profile.optString("cover"), "صورة الغلاف",
                                                Modifier.fillMaxSize(), ContentScale.Crop)
                                        } else {
                                            Box(Modifier.fillMaxSize().background(
                                                Brush.horizontalGradient(listOf(Color(0xFF282014), Color(0xFF090C0F)))))
                                        }
                                        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                                            Box(Modifier.size(29.dp).clip(CircleShape)
                                                .background(Gold).clickable(enabled = !busy) {
                                                    profileCoverPicker.launch("image/*")
                                                }, contentAlignment = Alignment.Center) {
                                                SiteIcon("camera", size = 15.dp, tint = Black)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Box(Modifier.size(100.dp)) {
                                            Box(Modifier.size(87.dp).align(Alignment.TopStart)
                                                .border(2.dp, Gold, CircleShape).padding(3.dp)
                                                .clip(CircleShape).background(Panel),
                                                contentAlignment = Alignment.Center) {
                                                if (media(profile.optString("avatar")).isNotBlank()) {
                                                    ExpandableProfileMedia(profile.optString("avatar"), "الصورة الشخصية",
                                                        Modifier.fillMaxSize())
                                                } else Text(profile.name().take(1), color = GoldLight, fontSize = 35.sp)
                                            }
                                            Box(Modifier.align(Alignment.BottomEnd).size(25.dp)
                                                .border(2.dp, Black, CircleShape).clip(CircleShape)
                                                .background(Gold).clickable(enabled = !busy) {
                                                    profileAvatarPicker.launch("image/*")
                                                }, contentAlignment = Alignment.Center) {
                                                SiteIcon("camera", size = 13.dp, tint = Black)
                                            }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(profile.name(), color = White,
                                                fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    SectionTitle("معلومات عني")
                                    PremiumAuthOutlineButton(if (editProfileDetails) "إلغاء التعديل" else "تعديل المعلومات") {
                                        editProfileDetails = !editProfileDetails
                                    }
                                    if (editProfileDetails) {
                                    OutlinedTextField(profileBio, { profileBio = it.take(300) },
                                        label = { Text("نبذة عني") }, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(profileCountry, { profileCountry = it.take(60) },
                                        label = { Text("البلد") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(profileCity, { profileCity = it.take(60) },
                                        label = { Text("المدينة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(profileEducation, { profileEducation = it.take(100) },
                                        label = { Text("التعليم / الجامعة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(profileWork, { profileWork = it.take(100) },
                                        label = { Text("العمل") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(checked = allowFriendRequests, onCheckedChange = { allowFriendRequests = it })
                                        Text(" استقبال طلبات الصداقة", color = GoldLight)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(checked = accountPrivate, onCheckedChange = { accountPrivate = it })
                                        Text(" حساب خاص (المنشورات للأصدقاء فقط)", color = GoldLight)
                                    }
                                    GoldButton("حفظ المعلومات", !busy) {
                                        task {
                                            val updated = post("/api/profile", JSONObject()
                                                .put("bio", profileBio)
                                                .put("country", profileCountry)
                                                .put("city", profileCity)
                                                .put("education", profileEducation)
                                                .put("work", profileWork)
                                                .put("allowFriendRequests", allowFriendRequests)
                                                .put("accountPrivate", accountPrivate))
                                            profile = updated.optJSONObject("user") ?: profile
                                            editProfileDetails = false
                                            featureMessage = "تم حفظ معلومات الملف الشخصي"
                                        }
                                    }
                                    } else {
                                        listOf("الدولة" to profileCountry, "المدينة" to profileCity,
                                            "التعليم" to profileEducation, "العمل" to profileWork).forEach { (label, value) ->
                                            if (value.isNotBlank()) Text("$label: $value", color = White)
                                        }
                                        Text("طلبات الصداقة: ${if (allowFriendRequests) "مسموحة" else "مخفية"}", color = Muted)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    PremiumAuthOutlineButton("تغيير كلمة السر") { changePasswordDialog = true }
                                }
                            }
                            item { SectionTitle("منشوراتي") }
                            if (ownPosts.isEmpty()) item { Text("لا توجد منشورات ظاهرة حاليًا", color = White) }
                            items(ownPosts, key = { "own_" + it.optString("id", it.hashCode().toString()) }) { post ->
                                CardBox {
                                    Text(profile.name(), color = GoldLight, fontWeight = FontWeight.Bold)
                                    if (post.optString("text").isNotBlank()) Text(post.optString("text"), color = White)
                                    if (post.optString("image").isNotBlank()) ExpandableProfileMedia(post.optString("image"), "صورة منشوري", Modifier.fillMaxWidth().heightIn(max = 320.dp), ContentScale.Fit)
                                    if (post.optString("video").isNotBlank()) Text("منشور فيديو", color = GoldLight)
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
                            openChat(peer)
                        },
                        friendUsers = publicFriends,
                        onOpenFriend = { friend -> openPublicProfile(friend) },
                        onFriendAction = { action -> updateFriend(profilePeer, action) },
                        onVoiceCall = { openCall(profilePeer, "audio") },
                        onVideoCall = { openCall(profilePeer, "video") }
                    )
                }
            }
            activeCall?.let { session ->
                // Keep the same Composable and WebView mounted while minimized.
                // Removing/reattaching the WebView would destroy the WebRTC connection.
                NativeCallScreen(
                    session = session, token = token, myUsername = myUsername,
                    minimized = callMinimized,
                    modifier = if (callMinimized) {
                        // Full-width ongoing-call banner while browsing inside the app.
                        // Keep the WebRTC composable mounted so media is not interrupted.
                        Modifier.align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(top = 72.dp, start = 10.dp, end = 10.dp)
                            .height(86.dp)
                    } else Modifier.fillMaxSize().safeDrawingPadding(),
                    onMinimize = { callMinimized = true },
                    onRestore = { callMinimized = false },
                    onClose = { callMinimized = false; activeCall = null }
                )
            }
            }
        }
    }
}
