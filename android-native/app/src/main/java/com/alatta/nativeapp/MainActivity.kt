package com.alatta.nativeapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
@Composable private fun YouTubePlayer(id: String) {
    // YouTube's official embeddable iframe is isolated to the video player; all app screens are native Compose.
    AndroidView(factory = { ctx -> WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = WebViewClient()
        loadDataWithBaseURL("https://www.youtube.com", """<html><body style='margin:0;background:black'><iframe width='100%' height='100%' src='https://www.youtube-nocookie.com/embed/$id?playsinline=1' frameborder='0' allow='autoplay; encrypted-media; picture-in-picture' allowfullscreen></iframe></body></html>""", "text/html", "UTF-8", null)
    } }, modifier = Modifier.fillMaxWidth().height(230.dp))
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
    var profileBio by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pickedUri = uri
        pickedMime = uri?.let { ctx.contentResolver.getType(it) } ?: ""
    }
    fun task(block: suspend () -> Unit) {
        scope.launch { busy = true; error = ""; try { block() } catch (e: Exception) { error = e.message ?: "تعذر إتمام العملية" } finally { busy = false } }
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
    LaunchedEffect(token, tab) { if (token.isNotBlank()) { try { load(tab) } catch (e: Exception) { error = e.message ?: "تعذر التحميل" } } }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Black, surface = Panel, onSurface = White, onBackground = White)) {
            Column(Modifier.fillMaxSize().background(Black).safeDrawingPadding()) {
                if (token.isBlank()) {
                    Column(
                        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF0B0D0F), Black)))
                            .border(0.5.dp, Border).padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(painterResource(R.drawable.logo_premium), "شعار تواصل العطا", Modifier.size(74.dp).clip(CircleShape))
                        Text("العطا", color = GoldLight, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                        Text("AL-ATTA", color = GoldLight.copy(alpha = .8f), fontSize = 13.sp, letterSpacing = 3.sp)
                    }
                } else {
                    PremiumHeader(
                        current = tab,
                        unreadNotifications = notifications.count { !it.optBoolean("read", false) },
                        onNavigate = ::navigate
                    )
                    PremiumSearchBar { navigate("استكشاف") }
                }
                if (token.isBlank()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("تسجيل الدخول")
                        OutlinedTextField(username, { username = it }, label = { Text("اسم المستخدم أو البريد أو الهاتف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("كلمة المرور") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        GoldButton("دخول", !busy && username.isNotBlank() && password.isNotBlank()) {
                            task {
                                val result = withContext(Dispatchers.IO) { api("/api/login", JSONObject().put("username", username.trim()).put("password", password)) }
                                token = result.getString("token")
                                val u = result.optJSONObject("user") ?: JSONObject()
                                displayName = u.name().ifBlank { username }
                                myUsername = u.optString("username", username).lowercase()
                                prefs.edit().putString("token", token).putString("name", displayName).putString("username", myUsername).apply()
                                password = ""; tab = "الرئيسية"
                            }
                        }
                    }
                } else {
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
                        "يوتيوب" -> LazyColumn(Modifier.weight(1f).padding(8.dp)) {
                            item { SectionTitle("يوتيوب داخل تواصل العطا"); OutlinedTextField(youtubeSearch, { youtubeSearch = it }, label = { Text("ابحث عن فيديو") }, modifier = Modifier.fillMaxWidth()); GoldButton("بحث") { refresh() } }
                            if (youtubeId.isNotBlank()) item { CardBox { YouTubePlayer(youtubeId); TextButton(onClick = { youtubeId = "" }) { Text("إغلاق المشغل") } } }
                            items(youtubeItems) { v -> CardBox {
                                AsyncImage(v.optString("thumbnail"), "صورة الفيديو", Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop)
                                Text(v.optString("title"), color = GoldLight, fontWeight = FontWeight.Bold)
                                Text(v.optString("channel"), color = Color.LightGray)
                                GoldButton("تشغيل") { youtubeId = v.optString("id") }
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
                                }
                            }
                        }
                    }
                    PremiumBottomNav(tab, ::navigate)
                }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Gold)
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF8B8B), modifier = Modifier.padding(12.dp))
            }
        }
    }
}
