package com.alatta.nativeapp

import android.content.Intent
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContrac
private fun attaYouTubeIframeHtml(videoId: String): String = """
    <!doctype html><html><head><meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <meta name="referrer" content="strict-origin-when-cross-origin" />
    <style>
      html, body { margin:0; padding:0; overflow:hidden; width:100%; height:100%; background:#000; }
      iframe { display:block; width:100%; height:100%; border:0; background:#000; }
    </style></head><body>
    <iframe src="https://www.youtube.com/embed/$videoId?playsinline=1&amp;controls=1&amp;rel=0&amp;fs=1&amp;origin=$ATTA_YT_ORIGIN"
      title="YouTube video" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; fullscreen"
      allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe>
    </body></html>
""".trimIndent()

@Composable
internal fun AlattaFixedYouTubePlayer(id: String) {
    if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) return
    val context = LocalContext.current
    val activity = context as? Activity
    // One WebView and one Chrome client per video; never move the WebView between Compose dialogs.
    var embedMode by remember(id) { mutableStateOf(false) }
    var reloadKey by remember(id) { mutableIntStateOf(0) }
    var zoom by remember(id) { mutableStateOf(1f) }
    var error by remember(id, embedMode, reloadKey) { mutableStateOf("") }
    var loaded by remember(id, embedMode, reloadKey) { mutableStateOf(false) }

    val chrome = remember(activity, id, embedMode, reloadKey) { AttaYouTubeChrome(activity) }
    val webView = remember(context, id, embedMode, reloadKey) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.BLACK)
            // No forced software/hardware WebView layer: let Chromium choose video composition.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            webChromeClient = chrome
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { loaded = true }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, problem: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        loaded = true
                        error = "تعذّر تحميل مشغّل يوتيوب. جرّب طريقة العرض الأخرى."
                    }
                }
            }
            if (embedMode) {
                // Real HTTPS base URL gives YouTube a useful Referer / origin.
                loadDataWithBaseURL("$ATTA_YT_ORIGIN/", attaYouTubeIframeHtml(id), "text/html", "UTF-8", null)
            } else {
                loadUrl("$ATTA_YT_ORIGIN/native-youtube-player.html?id=$id")
            }
        }
    }
    DisposableEffect(webView, chrome) {
        onDispose {
            chrome.leaveFullscreen()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
    BackHandler(enabled = chrome.fullscreen) { chrome.leaveFullscreen() }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier.fillMaxWidth().background(attaPanel, RoundedCornerShape(13.dp))
                .border(1.dp, attaGold.copy(alpha = .38f), RoundedCornerShape(13.dp))
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxWidth(zoom).aspectRatio(16f / 9f)
            )
            if (!loaded) CircularProgressIndicator(
                color = attaGold, modifier = Modifier.size(26.dp)
            )
            if (error.isNotBlank()) Text(
                error, color = attaGold, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .94f)).padding(12.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { zoom = (zoom - .1f).coerceAtLeast(.8f) }) {
                Text("−", color = attaGold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text("حجم الفيديو ${(zoom * 100).toInt()}%", color = attaGold, fontSize = 12.sp)
            TextButton(onClick = { zoom = (zoom + .1f).coerceAtMost(1f) }) {
                Text("+", color = attaGold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                // YouTube requests fullscreen from its own player; WebChromeClient rotates then.
                webView.evaluateJavascript("(function(){var f=document.querySelector('iframe'); var e=f||document.documentElement; if(e.requestFullscreen)e.requestFullscreen();})();", null)
            }) { Text("ملء الشاشة ↗", color = attaGold, fontSize = 12.sp) }
            TextButton(onClick = {
                embedMode = !embedMode
            }) { Text("طريقة عرض أخرى", color = attaGold, fontSize = 12.sp) }
            TextButton(onClick = { reloadKey++ }) {
                Text("إعادة تحميل", color = attaGold, fontSize = 12.sp)
            }
        }
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id"))) }
                .onFailure { error = "لا يوجد تطبيق قادر على فتح رابط يوتيوب." }
        }) { Text("إذا استمرت الصورة البيضاء: فتح الفيديو في يوتيوب", color = Color(0xFFC9D0D4), fontSize = 11.sp) }
    }
}
ts
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
private fun media(s: String): String = when {
    s.startsWith("/media/") -> BASE + s
    s.startsWith("https://") -> s
    (s.startsWith("data:image/") || s.startsWith("data:video/")) -> s
    else -> ""
}
private fun JSONObject.arr(name: String): List<JSONObject> {
    val a = optJSONArray(name) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
private fun JSONObject.name(): String = optString("fullName").ifBlank { optString("displayName").ifBlank { optString("username", "مستخدم") } }
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
@Composable private fun NativeVideo(url: String) {
    val ctx = LocalContext.current
    val player = remember(url) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxWidth().height(240.dp))
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

@Composable private fun YouTubePlayer(id: String) {
    // The player document is served by OUR Cloudflare domain, exactly as on the website.
    // YouTube receives a real HTTPS referrer, unlike loadDataWithBaseURL/inline HTML.
    val safeId = id.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return
    val ctx = LocalContext.current
    val player = remember(safeId) {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            loadUrl("$BASE/native-youtube-player.html?id=$safeId")
        }
    }
    DisposableEffect(player) {
        onDispose { player.stopLoading(); player.loadUrl("about:blank"); player.destroy() }
    }
    Column {
        AndroidView(factory = { player }, modifier = Modifier.fillMaxWidth().height(235.dp))
        Text("المشغّل الرسمي داخل تواصل العطا. اضغط تشغيل على الفيديو.", color = Muted, fontSize = 11.sp)
        TextButton(onClick = { openYouTube(ctx, safeId) }) {
            Text("فتح على يوتيوب فقط إذا رفض صاحب الفيديو تضمينه", color = GoldLight, fontSize = 12.sp)
        }
    }
}
@Composable private fun Avatar(url: String, label: String) {
    if (media(url).isNotBlank()) AsyncImage(model = media(url), contentDescription = label, modifier = Modifier.size(42.dp).border(1.dp, Gold, CircleShape).clip(CircleShape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(42.dp).background(Gold, CircleShape), contentAlignment = Alignment.Center) { Text(label.take(1), color = Black, fontWeight = FontWeight.Bold) }
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
    var regAge by remember { mutableStateOf("") }
    var regPhone by remember { mutableStateOf("") }
    var regEmail by remember { mutableStateOf("") }
    var regCountry by remember { mutableStateOf("") }
    var regCity by remember { mutableStateOf("") }
    var googleIdToken by remember { mutableStateOf("") }
    var googleEmail by remember { mutableStateOf("") }
    var googleName by remember { mutableStateOf("") }
    var googleAge by remember { mutableStateOf("") }
    var googleCity by remember { mutableStateOf("") }
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
    var search by remember { mutableStateOf("") }
    var chatWith by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(emptyList<JSONObject>()) }
    var chatText by remember { mutableStateOf("") }
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
        prefs.edit().putString("token", token).putString("name", displayName).putString("username", myUsername).apply()
        password = ""; regPass = ""; regConfirm = ""; googleIdToken = ""
        resetPassword = ""; resetConfirm = ""; authMode = "login"; tab = "الرئيسية"
    }
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
            if (signup) {
                val claims = googleTokenDetails(pickedToken) // UI preview only; server verifies.
                googleIdToken = pickedToken
                googleEmail = claims.optString("email")
                googleName = claims.optString("name").ifBlank { claims.optString("given_name") }
                googleAge = ""
                googleCity = ""
                authMode = "googleProfile"
            } else {
                val result = withContext(Dispatchers.IO) {
                    api("/api/google-login", JSONObject()
                        .put("credential", pickedToken)
                        .put("signupFlow", "android-login"))
                }
                applySession(result, "Google")
            }
        }
    }
    suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) { api("$path${if ('?' in path) "&" else "?"}token=${enc(token)}") }
    suspend fun post(path: String, data: JSONObject): JSONObject = withContext(Dispatchers.IO) { api(path, data.put("token", token)) }
    suspend fun load(which: String) {
        when (which) {
            "الرئيسية" -> { posts = get("/api/posts?limit=40").arr("posts"); stories = get("/api/stories").arr("stories"); try { notifications = get("/api/notifications").arr("notifications") } catch (_: Exception) { } }
            "استكشاف" -> posts = get("/api/explore").arr("posts")
            "الأصدقاء" -> { users = get("/api/users?q=${enc(search)}").arr("users"); friends = get("/api/friends").arr("friends"); requests = get("/api/friend-requests").arr("requests") }
            "الرسائل" -> { friends = get("/api/friends").arr("friends"); if (chatWith.isNotBlank()) messages = get("/api/conversation?with=${enc(chatWith)}").arr("messages") }
            "الإشعارات" -> notifications = get("/api/notifications").arr("notifications")
            "المجموعات" -> groups = get("/api/groups").arr("groups")
            "الملف" -> { profile = get("/api/me").optJSONObject("user") ?: JSONObject(); profileBio = profile.optString("bio") }
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
            tab == "الرسائل" && chatWith.isNotBlank() -> chatWith = ""
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
            title = { Text(st.optString("author", "قصة")) },
            text = {
                Column {
                    Text(st.optString("text"))
                    val storyMedia = media(st.optString("media"))
                    if (storyMedia.isNotBlank()) {
                        if (storyMedia.contains(".mp4", true)) NativeVideo(storyMedia)
                        else AsyncImage(storyMedia, "القصة", Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedStory = null }) { Text("إغلاق") } }
        )
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
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { googleEnabled = false }
    }

    LaunchedEffect(youtubeId, tab) {
        if (tab == "يوتيوب" && youtubeId.isNotBlank()) {
            youtubeListState.animateScrollToItem(1)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Black, surface = Panel, onSurface = White, onBackground = White)) {
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
                            PremiumAuthTextField(username, { username = it }, "رقم الهاتف أو البريد الإلكتروني أو اسم المستخدم")
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
                            PremiumAuthTextField(regUsername, { regUsername = it }, "اسم المستخدم")
                            PremiumAuthTextField(regAge, { regAge = it }, "العمر")
                            PremiumAuthTextField(regPhone, { regPhone = it }, "رقم الهاتف (اختياري)")
                            PremiumAuthTextField(regEmail, { regEmail = it }, "البريد الإلكتروني لاستقبال كود التأكيد")
                            PremiumAuthTextField(regGender, { regGender = it }, "النوع (اختياري)")
                            PremiumAuthTextField(regCountry, { regCountry = it }, "الدولة (اختياري)")
                            PremiumAuthTextField(regCity, { regCity = it }, "المدينة (اختياري)")
                            PremiumAuthTextField(regPass, { regPass = it }, "كلمة المرور", password = true)
                            PremiumAuthTextField(regConfirm, { regConfirm = it }, "تأكيد كلمة المرور", password = true)
                            PremiumAuthGoldButton("إرسال كود التأكيد", !busy && regName.isNotBlank() && regUsername.isNotBlank() && regEmail.isNotBlank() && regPass.isNotBlank()) {
                                task {
                                    val body = JSONObject()
                                        .put("fullName", regName.trim()).put("username", regUsername.trim())
                                        .put("age", regAge.toIntOrNull() ?: 0).put("phone", regPhone.trim())
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
                            PremiumAuthTextField(googleAge, { googleAge = it.filter(Char::isDigit).take(3) }, "العمر (مطلوب)")
                            PremiumAuthTextField(googleCity, { googleCity = it.take(80) }, "المدينة (اختياري)")
                            Text("الحسابات الحالية بواسطة Google ستفتح مباشرة دون تغيير بياناتها", color = Muted, fontSize = 11.sp)
                            PremiumAuthGoldButton("تأكيد إنشاء حساب Google", !busy && googleIdToken.isNotBlank() &&
                                googleName.trim().length >= 2 && (googleAge.toIntOrNull() ?: 0) in 13..120) {
                                task {
                                    val data = JSONObject()
                                        .put("credential", googleIdToken)
                                        .put("signupFlow", "android-signup")
                                        .put("fullName", googleName.trim())
                                        .put("age", googleAge.toIntOrNull())
                                        .put("city", googleCity.trim())
                                    val result = withContext(Dispatchers.IO) { api("/api/google-login", data) }
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
                    PremiumHeader(
                        current = tab,
                        unreadNotifications = notifications.count { !it.optBoolean("read", false) },
                        onNavigate = ::navigate
                    )
                    if (tab == "الرئيسية" || tab == "استكشاف") {
                        PremiumSearchBar { navigate("استكشاف") }
                    }
                    if (tab != "الرئيسية") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tab, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { refresh() }) { Text("تحديث", fontSize = 12.sp) }
                            TextButton(onClick = { token = ""; posts = emptyList(); prefs.edit().clear().apply() }) { Text("خروج", fontSize = 12.sp) }
                        }
                    }
                    when (tab) {
                        "الرئيسية", "استكشاف", "المحفوظات" -> LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (tab == "الرئيسية") {
                                item {
                                    CardBox {
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                            Image(painterResource(R.drawable.logo_premium), "صورة المستخدم",
                                                Modifier.size(39.dp).clip(CircleShape))
                                            Text(myUsername.ifBlank { displayName }, color = White,
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
                                                StoryRing(st.optString("author", "مستخدم"), storyPhoto, false) {
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
                                CardBox {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Avatar(author.optString("avatar"), author.name())
                                        Column { Text(author.name().ifBlank { p.optString("author") }, color = GoldLight, fontWeight = FontWeight.Bold); Text(p.optString("author"), fontSize = 11.sp, color = Color.LightGray) }
                                    }
                                    if (p.optString("text").isNotBlank()) Text(p.optString("text"), color = White, modifier = Modifier.padding(vertical = 10.dp))
                                    val image = media(p.optString("image")); val video = media(p.optString("video"))
                                    if (image.isNotBlank()) AsyncImage(image, "صورة المنشور", Modifier.fillMaxWidth().padding(vertical = 6.dp).heightIn(min = 120.dp, max = 450.dp).clip(RoundedCornerShape(11.dp)).border(.6.dp, Border.copy(alpha = .75f), RoundedCornerShape(11.dp)), contentScale = ContentScale.Fit)
                                    if (video.isNotBlank()) NativeVideo(video)
                                    p.optJSONObject("sharedPost")?.let { shared -> Text("مشاركة: ${shared.optString("text")}", color = GoldLight) }
                                    HorizontalDivider(color = Border.copy(alpha = .5f), modifier = Modifier.padding(vertical = 7.dp))
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            PremiumPostAction("heart", p.optInt("likeCount").toString(),
                                                color = if (p.optBoolean("likedByMe")) GoldLight else Muted) {
                                                task { post("/api/post-like", JSONObject().put("postId", postId)); load(tab) }
                                            }
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
                                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                                        listOf("love" to "❤️", "haha" to "😂", "wow" to "😮", "sad" to "😢", "angry" to "😡").forEach { (reaction, emoji) ->
                                            TextButton(onClick = { task { post("/api/post-react", JSONObject().put("postId", postId).put("reaction", reaction)); load(tab) } }) { Text(emoji) }
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
                                    Avatar(u.optString("avatar"), u.name()); Spacer(Modifier.width(8.dp)); Text(u.name(), modifier = Modifier.weight(1f))
                                    val state = u.optString("friendState")
                                    TextButton(onClick = { task { if (state == "incoming") post("/api/friend-action", JSONObject().put("username", u.optString("username")).put("action", "accept")) else post("/api/friend-request", JSONObject().put("username", u.optString("username"))); load(tab) } }, enabled = state != "friends" && state != "outgoing" && u.optString("username") != myUsername) { Text(when (state) { "friends" -> "صديق"; "outgoing" -> "مرسل"; "incoming" -> "قبول"; else -> "إضافة" }) }
                                } }
                            }
                            item { SectionTitle("أصدقائي") }
                            items(friends) { u -> CardBox { Text(u.name(), color = GoldLight); TextButton(onClick = { chatWith = u.optString("username"); navigate("الرسائل") }) { Text("مراسلة") } } }
                        }
                        "الرسائل" -> Column(Modifier.weight(1f).padding(8.dp)) {
                            if (chatWith.isBlank()) {
                                SectionTitle("اختر صديقًا للمراسلة")
                                LazyColumn { items(friends) { u -> CardBox { TextButton(onClick = { chatWith = u.optString("username"); refresh() }) { Text(u.name()) } } } }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { chatWith = "" }) { Text("رجوع") }; Text(chatWith, color = GoldLight) }
                                LazyColumn(Modifier.weight(1f)) { items(messages) { m -> CardBox { Text(m.optString("from"), color = GoldLight, fontSize = 12.sp); Text(m.optString("text")) } } }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(chatText, { chatText = it }, modifier = Modifier.weight(1f), label = { Text("رسالة") })
                                    GoldButton("إرسال", chatText.isNotBlank()) { task { post("/api/message-send", JSONObject().put("to", chatWith).put("text", chatText)); chatText = ""; load(tab) } }
                                }
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
                        "الملف" -> LazyColumn(Modifier.weight(1f).padding(8.dp)) {
                            item {
                                CardBox {
                                    SectionTitle(profile.name())
                                    if (media(profile.optString("cover")).isNotBlank()) AsyncImage(media(profile.optString("cover")), "الغلاف", Modifier.fillMaxWidth().height(140.dp), contentScale = ContentScale.Crop)
                                    Avatar(profile.optString("avatar"), profile.name())
                                    Text("@${profile.optString("username")}")
                                    OutlinedTextField(profileBio, { profileBio = it }, label = { Text("نبذة عني") }, modifier = Modifier.fillMaxWidth())
                                    GoldButton("حفظ النبذة") { task { post("/api/profile", JSONObject().put("bio", profileBio)); load(tab) } }
                                    PremiumAuthOutlineButton("تغيير كلمة السر") { changePasswordDialog = true }
                                }
                            }
                        }
                    }
                    PremiumBottomNav(tab, ::navigate)
                }
                if (token.isNotBlank()) {
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Gold)
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF8B8B), modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}
