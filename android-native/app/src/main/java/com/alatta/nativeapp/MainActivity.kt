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
import android.app.PictureInPictureParams
import android.content.res.Configuration
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
import android.os.Build
import android.os.PowerManager
import android.util.Rational
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.flow.collect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

                        @Composable
private fun AttaCommentEntry(entry: JSONObject, nested: Boolean,
    onProfile: (String) -> Unit, onReply: (String, String) -> Unit) {
                            val person = entry.optJSONObject("authorInfo") ?: JSONObject()
                            val name = person.optString("fullName")
                                .ifBlank { person.optString("displayName") }
                                .ifBlank { entry.optString("author") }
                            val author = entry.optString("author")
                                .ifBlank { person.optString("username") }
                            Row(Modifier.fillMaxWidth()
                                .padding(start = if (nested) 28.dp else 0.dp),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.Top) {
                                Box(Modifier.size(if (nested) 33.dp else 40.dp)
                                    .border(1.dp, Gold, CircleShape).clip(CircleShape)
                                    .clickable(enabled = !person.optBoolean("anonymous")) { if (author.isNotBlank()) onProfile(author) },
                                    contentAlignment = Alignment.Center) {
                                    val avatar = person.optString("avatar")
                                    if (avatar.isNotBlank()) ProfileMediaImage(avatar, name, Modifier.fillMaxSize())
                                    else SiteIcon("user", size = 23.dp)
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                    Column(Modifier.fillMaxWidth()
                                        .background(Panel, RoundedCornerShape(14.dp))
                                        .border(.6.dp, Border.copy(alpha = .65f), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 11.dp, vertical = 9.dp),
                                        horizontalAlignment = Alignment.End) {
                                        Text(name, color = GoldLight, fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                                            modifier = Modifier.fillMaxWidth().clickable(enabled = !person.optBoolean("anonymous")) {
                                                if (author.isNotBlank()) onProfile(author)
                                            })
                                        Text(entry.optString("text"), color = White,
                                            textAlign = TextAlign.End, fontSize = 14.sp,
                                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
                                    }
                                    TextButton(onClick = {
                                        if (!nested) onReply(entry.optString("id"), name)
                                    }, enabled = !nested && entry.optString("id").isNotBlank(),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                                        Text(if (nested) "رد" else "رد على التعليق",
                                            color = Muted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

/**
 * Read-only list of users who reacted to a post, like the website's existing
 * interactions panel. The Worker filters blocked/hidden/invisible profiles.
 * This NEVER updates a reaction: it only fetches pages and opens profiles.
 */
@Composable
private fun AttaPostReactorsDialog(
    postId: String,
    onClose: () -> Unit,
    loadPeople: suspend (Int) -> JSONObject,
    onProfile: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var people by remember(postId) { mutableStateOf(emptyList<JSONObject>()) }
    var nextOffset by remember(postId) { mutableStateOf<Int?>(0) }
    var loading by remember(postId) { mutableStateOf(false) }
    var failure by remember(postId) { mutableStateOf("") }
    suspend fun loadNext() {
        val offset = nextOffset ?: return
        if (loading) return
        loading = true
        failure = ""
        try {
            val response = loadPeople(offset)
            val fresh = response.arr("people")
            people = (people + fresh).distinctBy { it.optString("username") }
            nextOffset = if (response.isNull("nextOffset")) null
                         else response.optInt("nextOffset").takeIf { it > offset }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: "تعذر تحميل أسماء المتفاعلين"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(postId) { loadNext() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.72f)
                .padding(horizontal = 10.dp)
                .background(Panel, RoundedCornerShape(20.dp))
                .border(1.dp, Border, RoundedCornerShape(20.dp))
                .padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("المعجبون والمتفاعلون", color = GoldLight,
                        fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { SiteIcon("x", size = 23.dp) }
                }
                HorizontalDivider(color = Border)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(people, key = { it.optString("username") }) { person ->
                        val username = person.optString("username")
                        val label = person.optString("fullName")
                            .ifBlank { person.optString("displayName") }
                            .ifBlank { username }
                        val emoji = when (person.optString("reaction")) {
                            "love" -> "❤️"
                            "haha" -> "😂"
                            "wow" -> "😮"
                            "sad" -> "😢"
                            "angry" -> "😡"
                            "dislike" -> "👎"
                            else -> "👍"
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp)
                            .clickable(enabled = username.isNotBlank() && !person.optBoolean("anonymous")) {
                                if (username.isNotBlank()) onProfile(username)
                            }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(43.dp).clip(CircleShape)
                                .border(.7.dp, Gold, CircleShape),
                                contentAlignment = Alignment.Center) {
                                if (person.optString("avatar").isNotBlank())
                                    ProfileMediaImage(person.optString("avatar"), label, Modifier.fillMaxSize())
                                else SiteIcon("user", size = 23.dp)
                            }
                            Text(label, Modifier.weight(1f), color = White,
                                fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(emoji, fontSize = 22.sp)
                        }
                    }
                }
                if (people.isEmpty() && !loading && failure.isBlank())
                    Text("لا توجد تفاعلات ظاهرة حاليًا", color = Muted,
                        modifier = Modifier.padding(12.dp))
                if (failure.isNotBlank())
                    Text(failure, color = Color(0xFFFFAFAF),
                        modifier = Modifier.padding(horizontal = 10.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
                if (nextOffset != null && !loading)
                    TextButton(onClick = { scope.launch { loadNext() } },
                        modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (failure.isBlank()) "عرض المزيد" else "إعادة المحاولة", color = GoldLight)
                    }
            }
        }
    }
}

/**
 * A single, genuinely RTL comment dialog used by the native feed.
 * Both Arabic and English names are on the RIGHT; English letters retain
 * their normal order. Replies use the server's existing /api/comment-reply.
 */
@Composable
private fun AttaRightAlignedComments(
    postId: String,
    onClose: () -> Unit,
    fetchComments: suspend () -> JSONObject,
    sendComment: suspend (String) -> Unit,
    sendReply: suspend (String, String) -> Unit,
    onProfile: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var comments by remember(postId) { mutableStateOf(emptyList<JSONObject>()) }
    var content by remember(postId) { mutableStateOf("") }
    var replyTo by remember(postId) { mutableStateOf("") }
    var replyName by remember(postId) { mutableStateOf("") }
    var loading by remember(postId) { mutableStateOf(true) }
    var sending by remember(postId) { mutableStateOf(false) }
    var failure by remember(postId) { mutableStateOf("") }
    val commentFocusRequester = remember(postId) { FocusRequester() }
    suspend fun refresh() {
        val result = fetchComments()
        comments = result.arr("comments")
    }
    LaunchedEffect(postId) {
        try { refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { failure = e.message ?: "تعذر تحميل التعليقات" }
        finally { loading = false }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        val dialogView = LocalView.current
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        val keyboard = LocalSoftwareKeyboardController.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        var keyboardCoveredPx by remember { mutableIntStateOf(0) }
        // Some Android vendors keep fullscreen dialogs the same size when the
        // keyboard opens. Measure the actual visible window area and raise
        // only the comment composer by the covered height.
        DisposableEffect(dialogWindow) {
            dialogWindow?.let {
                WindowCompat.setDecorFitsSystemWindows(it, true)
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            }
            onDispose { }
        }
        DisposableEffect(dialogView) {
            val tree = dialogView.viewTreeObserver
            val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                val visible = android.graphics.Rect()
                val screenPoint = IntArray(2)
                dialogView.getWindowVisibleDisplayFrame(visible)
                dialogView.getLocationOnScreen(screenPoint)
                val viewBottomOnScreen = screenPoint[1] + dialogView.height
                val overlap = (viewBottomOnScreen - visible.bottom).coerceAtLeast(0)
                val minKeyboard = (110 * dialogView.resources.displayMetrics.density).toInt()
                val next = if (overlap >= minKeyboard) overlap else 0
                if (keyboardCoveredPx != next) keyboardCoveredPx = next
            }
            tree.addOnGlobalLayoutListener(listener)
            dialogView.post { listener.onGlobalLayout() }
            onDispose { if (tree.isAlive) tree.removeOnGlobalLayoutListener(listener) }
        }
        LaunchedEffect(postId) {
            delay(230)
            commentFocusRequester.requestFocus()
            keyboard?.show()
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            val keyboardLift = with(density) { keyboardCoveredPx.toDp() }
            Column(Modifier.fillMaxSize().background(Black)
                .padding(bottom = keyboardLift).safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp)
                    .border(.5.dp, Border).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("التعليقات", color = White, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center)
                    Text("${comments.size}", color = GoldLight, fontSize = 14.sp)
                    TextButton(onClick = onClose) { SiteIcon("x", size = 22.dp) }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
                if (failure.isNotBlank()) Text(failure, color = Color(0xFFFF9999),
                    modifier = Modifier.padding(12.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(comments, key = { it.optString("id").ifBlank { it.hashCode().toString() } }) { comment ->
                        AttaCommentEntry(comment, false, onProfile) { id, name ->
                            replyTo = id; replyName = name
                            commentFocusRequester.requestFocus()
                            keyboard?.show()
                        }
                        comment.arr("replies").forEach { reply ->
                            AttaCommentEntry(reply, true, onProfile) { _, _ -> }
                        }
                    }
                }
                if (replyTo.isNotBlank()) Row(Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("رد على ${replyName}", color = GoldLight, fontSize = 12.sp,
                        modifier = Modifier.weight(1f), maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { replyTo = ""; replyName = "" }) {
                        Text("إلغاء", color = Muted)
                    }
                }
                // Composer remains directly above the IME; insets are already
                // handled by the dialog container, no extra bottom clearance.
                Row(Modifier.fillMaxWidth().background(Panel)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = content, onValueChange = { content = it },
                        placeholder = { Text("اكتب تعليقًا...", color = Muted) },
                        modifier = Modifier.weight(1f).focusRequester(commentFocusRequester), maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = White, unfocusedTextColor = White,
                            cursorColor = Gold, focusedBorderColor = Gold,
                            unfocusedBorderColor = Border),
                        shape = RoundedCornerShape(22.dp))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val message = content.trim()
                        if (message.isNotBlank() && !sending) scope.launch {
                            sending = true; failure = ""
                            try {
                                if (replyTo.isNotBlank()) sendReply(replyTo, message)
                                else sendComment(message)
                                content = ""; replyTo = ""; replyName = ""
                                refresh()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (e: Exception) { failure = e.message ?: "تعذر إرسال التعليق" }
                            finally { sending = false }
                        }
                    }, enabled = content.isNotBlank() && !sending,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        SiteIcon("send", size = 26.dp, tint = Gold)
                    }
                }
            }
        }
    }
}

internal const val ATTA_API_BASE = "https://tawasol-alatta.omaratta077-4fd.workers.dev"
private const val BASE = ATTA_API_BASE
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
internal class AttaApiException(val statusCode: Int, message: String) : IllegalStateException(message)

internal fun attaApi(path: String, payload: JSONObject? = null): JSONObject {
    val response = AttaHttp.json(BASE + path, payload)
    val status = response.code
    val obj = response.data
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
        throw AttaApiException(status, if (duplicateUsername)
            "اسم المستخدم موجود بالفعل، اختر اسمًا آخر." else serverMessage)
    }
    return obj
}
private fun api(path: String, payload: JSONObject? = null) = attaApi(path, payload)
private fun upload(token: String, mime: String, filename: String, bytes: ByteArray): String {
    val kind = when {
        mime.startsWith("video/") -> "video"
        mime == "application/pdf" -> "file"
        else -> "image"
    }
    val path = "/api/media-upload?token=${enc(token)}&kind=$kind&name=${enc(filename)}"
    val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true
        connectTimeout = 20000; readTimeout = 120000
        setRequestProperty("Content-Type", mime)
        setFixedLengthStreamingMode(bytes.size)
    }
    return try {
        require(bytes.size <= 50 * 1024 * 1024) { "الحد الأقصى 50 ميجابايت" }
        conn.outputStream.use { it.write(bytes) }
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
    if (uri.scheme == "file") {
        val file = java.io.File(requireNotNull(uri.path))
        displayName = file.name
        declaredSize = file.length()
    } else {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            }
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
                copyMediaBounded(input, output, maxBytes)
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
    internal var requestedNotificationEvent by mutableStateOf<String?>(null)
    internal var requestedNotificationTab by mutableStateOf<String?>(null)
        private set
    internal var youtubePipMode by mutableStateOf(false)
        private set
    private var youtubePlaybackActive = false

    internal fun setYoutubePlaybackActive(active: Boolean) {
        youtubePlaybackActive = active
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setAutoEnterEnabled(active)
            }
            setPictureInPictureParams(params.build())
        }
    }

    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && youtubePlaybackActive && !isInPictureInPictureMode) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        youtubePipMode = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore only on an Activity launch, never before validating a cold-start alarm receiver.
        AdhanScheduler.restore(this)
        consumePushIntent(intent)
        setContent { NativeApp() }
    }

    private var prayerLocationJob: kotlinx.coroutines.Job? = null

    override fun onStart() {
        super.onStart()
        if (incomingPush == null) incomingPush = AttaPush.pendingIncoming(this)
        prayerLocationJob = lifecycleScope.launch { PrayerLocation.refreshIfEnabled(this@MainActivity) }
    }

    override fun onStop() {
        prayerLocationJob?.cancel(); prayerLocationJob = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePushIntent(intent)
    }

    private fun consumePushIntent(intent: Intent?) {
        if (intent?.action == AdhanScheduler.ACTION_OPEN) {
            requestedNotificationTab = "الأذكار"
            intent.action = Intent.ACTION_MAIN
            return
        }
        if (intent?.action == AttaPush.ACTION_OPEN_NOTIFICATION) {
            requestedNotificationEvent = intent.getStringExtra("atta_notification_event")
            val destination = intent.getStringExtra("atta_notification_tab")
            if (destination in setOf("الرسائل", "الأصدقاء", "الإشعارات"))
                requestedNotificationTab = destination
            intent.removeExtra("atta_notification_tab")
            return
        }
        if (intent == null) return
        // FCM notification+data: when the phone is locked Android displays the
        // incoming-call notification itself. Tapping it launches our Activity
        // with FCM data extras, not ACTION_OPEN_CALL. Handle both entry points.
        val event = if (intent.action == AttaPush.ACTION_OPEN_CALL) {
            PushCall.fromIntent(intent)
        } else if (intent.getStringExtra("type") == "call-request") {
            val id = intent.getStringExtra("callId").orEmpty()
            val from = (intent.getStringExtra("sender") ?: intent.getStringExtra("from"))
                .orEmpty().trim().lowercase()
            val to = intent.getStringExtra("to").orEmpty().trim().lowercase()
            val created = intent.getStringExtra("createdAt")?.toLongOrNull() ?: 0L
            if (id.length in 8..128 && from.isNotBlank() && to.isNotBlank())
                PushCall(from, to, id,
                    if (intent.getStringExtra("callType") == "video") "video" else "audio",
                    created, AttaPush.ACTION_VIEW, intent.getStringExtra("fromName").orEmpty())
            else null
        } else null
        val pending = event ?: AttaPush.pendingIncoming(this) ?: return
        AttaPush.rememberIncoming(this, pending)
        incomingPush = pending
        AttaPush.markCallAlerted(this, pending.callId)
        AttaPush.dismiss(this, pending.callId, ended = false)
        // Clear one-shot extras so recreation cannot replay an incoming call.
        intent.removeExtra("atta_call_id")
        intent.removeExtra("callId")
        intent.removeExtra("type")
    }

    fun clearIncomingPush() { incomingPush = null }
    fun clearNotificationEvent() { requestedNotificationEvent = null }
    fun clearNotificationNavigation() { requestedNotificationTab = null }
}

@Composable
private fun SignupLegalConsent(
    privacyAccepted: Boolean, onPrivacyAccepted: (Boolean) -> Unit,
    termsAccepted: Boolean, onTermsAccepted: (Boolean) -> Unit,
    openDocument: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()
        .border(1.dp, Gold.copy(alpha = .65f), RoundedCornerShape(13.dp))
        .background(Panel, RoundedCornerShape(13.dp))
        .padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("اقرأ السياسات قبل إنشاء الحساب", color = GoldLight,
            fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { openDocument("privacy.html") }, modifier = Modifier.weight(1f)) {
                Text("قراءة سياسة الخصوصية", color = GoldLight, fontSize = 12.sp)
            }
            Checkbox(checked = privacyAccepted, onCheckedChange = onPrivacyAccepted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { openDocument("terms.html") }, modifier = Modifier.weight(1f)) {
                Text("قراءة شروط الاستخدام", color = GoldLight, fontSize = 12.sp)
            }
            Checkbox(checked = termsAccepted, onCheckedChange = onTermsAccepted)
        }
        Text("حدد المربعين بعد القراءة للموافقة والمتابعة.", color = Muted, fontSize = 11.sp)
    }
}

@Composable private fun AttaSignupPrivacyChoice(
    gender: String, onGender: (String) -> Unit,
    hideChoice: Boolean?, onHideChoice: (Boolean?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("النوع (إجباري)", color = GoldLight, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = gender == "male", onClick = { onGender("male"); onHideChoice(false) }, label = { Text("ذكر") })
            FilterChip(selected = gender == "female", onClick = { onGender("female"); onHideChoice(null) }, label = { Text("أنثى") })
        }
        if (gender == "female") {
            Text("هل تريدين إخفاء الحساب من البحث وقوائم الأصدقاء؟", color = GoldLight, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = hideChoice == true, onClick = { onHideChoice(true) }, label = { Text("نعم، إخفاء") })
                FilterChip(selected = hideChoice == false, onClick = { onHideChoice(false) }, label = { Text("لا، إظهار") })
            }
        }
    }
}

@Composable private fun GoldButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black), shape = RoundedCornerShape(12.dp), modifier = Modifier.shadow(7.dp, RoundedCornerShape(12.dp), ambientColor = Gold.copy(alpha = .35f), spotColor = Gold.copy(alpha = .30f)).heightIn(min = 42.dp).clip(RoundedCornerShape(12.dp)).attaGoldShine(enabled)) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}
/** A quiet empty action becomes gold and animated when content is ready. */
@Composable private fun GoldPublishButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Gold, contentColor = Black,
            disabledContainerColor = Gold.copy(alpha = .65f), disabledContentColor = Black.copy(alpha = .75f)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier
            .heightIn(min = 36.dp).clip(RoundedCornerShape(13.dp)).attaGoldShine(enabled)) {
        Text("نشر", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
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
@Composable private fun YouTubePlayer(id: String, onMiniDrag: ((Float, Float) -> Unit)? = null) {
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
    val touchHost = remember(ctx, safeId) {
        MiniPlayerTouchHost(ctx).apply {
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
    // An iframe loading is not proof of playback, but a 20-second timeout
    // is not a player error either. Stop the loading spinner quietly and let
    // the site's existing embedded player display its own controls.
    LaunchedEffect(webView, safeId) {
        kotlinx.coroutines.delay(20000)
        if (!playbackConfirmed) loading = false
    }
    DisposableEffect(webView, chrome) {
        val owner = ctx as? ComponentActivity
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val activity = ctx as? MainActivity
                    val pipVisibleAndInteractive = activity?.isInPictureInPictureMode == true &&
                        activity.getSystemService(PowerManager::class.java)?.isInteractive == true
                    if (!pipVisibleAndInteractive) {
                        webView.evaluateJavascript("document.querySelectorAll('iframe').forEach(function(f){f.contentWindow.postMessage(JSON.stringify({event:'command',func:'pauseVideo',args:[]}), 'https://www.youtube-nocookie.com');f.contentWindow.postMessage(JSON.stringify({event:'command',func:'pauseVideo',args:[]}), 'https://www.youtube.com')})", null)
                        webView.onPause()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> webView.onResume()
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            chrome.release()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }
    @Composable fun PlayerSurface(modifier: Modifier) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(factory = {
                (touchHost.parent as? ViewGroup)?.removeView(touchHost)
                touchHost
            }, update = { it.onDrag = onMiniDrag }, modifier = Modifier.fillMaxSize())
            if (loading) CircularProgressIndicator(color = Gold, modifier = Modifier.size(30.dp))
            // Never cover the real YouTube picture with a WebView diagnostic caption.
        }
    }
    // v1.0.57 playback path intentionally unchanged: the HTTPS site still
    // constructs the sole iframe via the existing site bridge.
    val compact = LocalConfiguration.current.screenHeightDp < 480
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        PlayerSurface(Modifier.fillMaxWidth().height(if (compact) 200.dp else (maxWidth * 9f / 16f).coerceAtLeast(200.dp)))
    }
}

/** Posts use the same site player inline; only the YouTube section owns a floating host. */
@Composable
internal fun YoutubePostMedia(videoId: String, playing: Boolean, onPlay: () -> Unit, onStop: () -> Unit,
    playerContent: @Composable (String) -> Unit = { YouTubePlayer(it) }) {
    if (playing) {
        val currentStop by rememberUpdatedState(onStop)
        DisposableEffect(videoId) { onDispose { currentStop() } }
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp)).background(Color.Black)
            .border(.7.dp, Border.copy(alpha = .7f), RoundedCornerShape(14.dp))) {
            playerContent(videoId)
        }
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp)).background(Color.Black)
            .clickable(onClick = onPlay), contentAlignment = Alignment.Center) {
            AsyncImage("https://i.ytimg.com/vi/$videoId/hqdefault.jpg", "معاينة فيديو يوتيوب",
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)))
            SiteIcon("play-circle", size = 60.dp, tint = GoldLight)
        }
    }
}

@Composable
internal fun MushafPostActions(text: String, onRead: ((IntRange) -> Unit)? = null) {
    val attachment = remember(text) { parseMushafPostAttachment(text, BASE) } ?: return
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onRead != null) TextButton(onClick = { onRead(attachment.pages) }) {
            SiteIcon("book-open", size = 18.dp)
            Text("قراءة الصفحات ${attachment.pages.first}–${attachment.pages.last}", color = GoldLight, fontSize = 12.sp)
        }
        TextButton(onClick = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(attachment.pdfUrl))) }
            catch (_: ActivityNotFoundException) {
                android.widget.Toast.makeText(context,"لا يوجد تطبيق لفتح الملف",android.widget.Toast.LENGTH_SHORT).show()
            }
        }) { Text("ملف الصفحات كاملًا", color = GoldLight, fontSize = 12.sp) }
    }
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
    AttaPrivacyGate { revoke, onSignedIn -> NativeAppContent(revoke, onSignedIn) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NativeAppContent(onPrivacyRevoked: () -> Unit, onSignedIn: () -> Unit) {
    var updateCheckRequest by remember { mutableIntStateOf(0) }
    AppUpdatePrompt(updateCheckRequest)
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("alatta_native", 0) }
    val hasGoogleServices = remember { AttaPush.googleAvailable(ctx) }
    var notificationsEnabled by remember { mutableStateOf(AttaPrivacy.notifications(ctx)) }
    var legalDocument by remember { mutableStateOf<String?>(null) }
    var deletionRequest by remember { mutableStateOf<JSONObject?>(null) }
    var deletionRequests by remember { mutableStateOf(emptyList<JSONObject>()) }
    var adminReports by remember { mutableStateOf(emptyList<JSONObject>()) }
    var reportTarget by remember { mutableStateOf<Pair<String,String>?>(null) }
    var reportReason by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf("login") }
    var unregisteredEmailOffer by remember { mutableStateOf("") }
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
    var googleHideChoice by remember { mutableStateOf<Boolean?>(null) }
    var regPoliciesAccepted by remember { mutableStateOf(false) }
    var regTermsAccepted by remember { mutableStateOf(false) }
    var googlePoliciesAccepted by remember { mutableStateOf(false) }
    var googleTermsAccepted by remember { mutableStateOf(false) }
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
    var adminPasswordAction by remember(token) { mutableStateOf<((String) -> Unit)?>(null) }
    var adminProtectionPassword by remember(token) { mutableStateOf("") }
    var adminEditUser by remember { mutableStateOf("") }
    var adminEditUserName by remember { mutableStateOf("") }
    var adminEditPost by remember { mutableStateOf("") }
    var adminEditPostText by remember { mutableStateOf("") }
    var adminPostSearch by remember { mutableStateOf("") }
    var ownProfileFilter by remember { mutableStateOf("المنشورات") }
    var ownerResetPlan by remember { mutableStateOf<JSONObject?>(null) }
    var ownerResetConfirmation by remember { mutableStateOf("") }
    var ownerResetReport by remember { mutableStateOf("") }
    var regGender by remember { mutableStateOf("") }
    var regHideChoice by remember { mutableStateOf<Boolean?>(null) }
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
    val incomingNotificationTab = (ctx as? MainActivity)?.requestedNotificationTab
    LaunchedEffect(incomingNotificationTab, token) {
        if (token.isNotBlank() && incomingNotificationTab != null) {
            tab = incomingNotificationTab
            (ctx as? MainActivity)?.clearNotificationNavigation()
        }
    }
    val visited = remember { mutableStateListOf<String>() }
    var confirmExit by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var showStoryComposer by remember { mutableStateOf(false) }
    var confirmPostDelete by remember { mutableStateOf("") }
    var confirmAccountUser by remember { mutableStateOf("") }
    var confirmAccountAction by remember { mutableStateOf("suspend") }
    var accountDeleteProgress by remember { mutableStateOf("") }
    var adminSummary by remember { mutableStateOf(JSONObject()) }
    var adminSearch by remember { mutableStateOf("") }
    var adminNextOffset by remember { mutableStateOf(-1) }
    var adminRole by remember { mutableStateOf("") }
    CatalogAutoRefreshEffect(token, myUsername, enabled = adminRole == "super_admin")
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
    var mushafPostDraft by remember { mutableStateOf<MushafPostDraft?>(null) }
    var youtubePostDraftId by remember { mutableStateOf("") }
    var youtubePostDraftTitle by remember { mutableStateOf("") }
    var activeYoutubePostId by remember { mutableStateOf("") }
    // Reload Home without launching scrolling work from an off-screen navigation item.
    var homeRefreshRequest by remember { mutableIntStateOf(0) }
    // Scroll requests are handled only while Home's LazyColumn is composed.
    var homeFirstPostRequest by remember { mutableIntStateOf(0) }
    var homeScrollTarget by remember { mutableIntStateOf(1) }
    var quranPage by remember { mutableIntStateOf(1) }
    var quranReadingRange by remember { mutableStateOf<IntRange?>(null) }
    var posts by remember { mutableStateOf(emptyList<JSONObject>()) }
    var stories by remember { mutableStateOf(emptyList<JSONObject>()) }
    var storyText by remember { mutableStateOf("") }
    var storyMediaUri by remember { mutableStateOf<Uri?>(null) }
    var storyMediaType by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf(emptyList<JSONObject>()) }
    var groupName by remember { mutableStateOf("") }
    var users by remember { mutableStateOf(emptyList<JSONObject>()) }
    var friends by remember { mutableStateOf(emptyList<JSONObject>()) }
    var requests by remember { mutableStateOf(emptyList<JSONObject>()) }
    var notificationTargetPost by remember { mutableStateOf<JSONObject?>(null) }
    var notifications by remember { mutableStateOf(emptyList<JSONObject>()) }
    var profile by remember(token) { mutableStateOf(JSONObject().put("fullName", displayName).put("username", myUsername)) }
    var screenLoading by remember { mutableStateOf(false) }
    var screenLoadGeneration by remember { mutableIntStateOf(0) }
    var homeCursor by remember(token) { mutableStateOf<String?>(null) }
    var ownCursor by remember(token) { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    // Block automatic retries of a failed cursor until Home refreshes.
    var olderPageFailedCursor by remember(token) { mutableStateOf<String?>(null) }
    var myAvatar by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var chatWith by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(emptyList<JSONObject>()) }
    var inboxPreviews by remember { mutableStateOf(emptyList<ChatPreview>()) }
    // The Worker stores message history per conversation, not as a global inbox.
    // Remember peers locally so an existing conversation remains visible even
    // before a friend request is accepted. Never store message bodies locally.
    val recentChatPeers = remember { mutableStateListOf<String>() }
    var hiddenChatPeers by remember { mutableStateOf(emptySet<String>()) }
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
    var footballSearch by remember { mutableStateOf("") }
    var youtubeItems by remember { mutableStateOf(emptyList<JSONObject>()) }
    var youtubeId by remember { mutableStateOf("") }
    // Choosing a suggestion only previews it. The trusted v1.0.57 WebView is
    // created ONLY when the user explicitly presses Play in the top preview.
    var youtubePlayRequested by remember { mutableStateOf(false) }
    var youtubeSelectedThumbnail by remember { mutableStateOf("") }
    val youtubePipMode = (ctx as? MainActivity)?.youtubePipMode == true
    SideEffect {
        (ctx as? MainActivity)?.setYoutubePlaybackActive(
            token.isNotBlank() && youtubePlayRequested && youtubeId.isNotBlank() && activeYoutubePostId.isBlank()
        )
    }
    var youtubeCategory by remember { mutableStateOf("قرآن") }
    val compactYoutubeLayout = LocalConfiguration.current.screenHeightDp < 480
    val youtubeDisplayHeight = if (compactYoutubeLayout) 200.dp else
        (((LocalConfiguration.current.screenWidthDp-16).dp)*9f/16f).coerceAtLeast(200.dp)
    // Keep the screen awake while a YouTube video or video call is visibly active.
    val keepScreenAwake = (token.isNotBlank() && (youtubePlayRequested || activeYoutubePostId.isNotBlank())) || activeCall?.type == "video"
    DisposableEffect(keepScreenAwake) {
        val window = (ctx as? ComponentActivity)?.window
        if (keepScreenAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (keepScreenAwake) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(activeCall?.callId, incomingCall?.callId) {
        if (activeCall != null || incomingCall != null) {
            youtubePlayRequested = false
            activeYoutubePostId = ""
        }
    }
    LaunchedEffect(tab, profilePeer) {
        // A post stops when its screen is left or covered by a profile. It never becomes a mini-player.
        activeYoutubePostId = ""
    }
    val youtubeListState = rememberLazyListState()
    val homeFeedListState = rememberLazyListState()
    val otherFeedListState = rememberLazyListState()
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
        if (uri != null) {
            mushafPostDraft?.discard(); mushafPostDraft = null
            pickedUri = uri
            pickedMime = ctx.contentResolver.getType(uri) ?: ""
        }
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
                error = loadingFailureMessage(e)
            }
            finally { busy = false }
        }
    }
    fun applyAccount(user: JSONObject) {
        profile = user
        val actualUsername = user.optString("username")
        if (actualUsername.isNotBlank() && actualUsername != "null" && actualUsername != myUsername) {
            myUsername = actualUsername.lowercase()
            prefs.edit().putString("username", myUsername).apply()
        }
        myAvatar = user.optString("avatar", myAvatar)
        adminRole = user.optString("role").trim().lowercase()
        val actualName = user.optString("fullName").ifBlank { user.optString("displayName") }
        if (actualName.isNotBlank() && actualName != "null") {
            displayName = actualName
            prefs.edit().putString("name", actualName).apply()
        }
        if (!editProfileDetails) {
            profileBio = user.optString("bio")
            profileCountry = user.optString("country")
            profileCity = user.optString("city")
            profileEducation = user.optString("education")
            profileWork = user.optString("work")
            allowFriendRequests = user.optBoolean("allowFriendRequests", true)
            accountPrivate = user.optBoolean("accountPrivate", false)
        }
    }
    fun applySession(result: JSONObject, fallbackName: String) {
        token = result.getString("token")
        val u = result.optJSONObject("user") ?: JSONObject()
        displayName = u.name().ifBlank { fallbackName }
        myUsername = u.optString("username", fallbackName).lowercase()
        applyAccount(u)
        prefs.edit().putString("token", token).putString("name", displayName).putString("username", myUsername).apply()
        password = ""; regPass = ""; regConfirm = ""; googleIdToken = ""; googlePassword = ""; googlePasswordConfirm = ""
        resetPassword = ""; resetConfirm = ""; authMode = "login"; tab = "الرئيسية"
        onSignedIn()
    }
    fun clearLocalSession() {
        activeCall = null; incomingCall = null; pendingPermissionCall = null
        AttaPush.activeCallId = ""
        NativeRtcCall.clearIceCache()
        chatWith = ""; messages = emptyList(); inboxPreviews = emptyList(); hiddenChatPeers = emptySet(); recentChatPeers.clear()
        posts = emptyList(); ownPosts = emptyList(); publicPosts = emptyList(); archivedPosts = emptyList()
        notificationTargetPost = null
        notifications = emptyList(); friends = emptyList(); publicFriends = emptyList(); requests = emptyList(); users = emptyList(); groups = emptyList()
        adminUsers = emptyList(); adminPosts = emptyList(); adminRole = ""
        adminReports = emptyList(); deletionRequests = emptyList(); deletionRequest = null
        adminSummary = JSONObject(); adminSearch = ""; adminNextOffset = -1
        confirmAccountUser = ""; accountDeleteProgress = ""
        profile = JSONObject(); publicProfile = null; displayName = ""; myUsername = ""; myAvatar = ""
        googleIdToken = ""; googleEmail = ""; ownerResetPlan = null; ownerResetConfirmation = ""
        mushafPostDraft?.discard(); mushafPostDraft = null; pickedUri = null; draft = ""
        youtubePostDraftId = ""; youtubePostDraftTitle = ""; activeYoutubePostId = ""
        youtubePlayRequested = false; youtubeId = ""
        visited.clear(); tab = "الرئيسية"; token = ""; authMode = "login"
        prefs.edit().clear().apply()
    }
    fun chooseGoogle(signup: Boolean) {
        if (busy) return
        if (!hasGoogleServices) { error = "هذا الجهاز لا يدعم خدمات Google؛ استخدم التسجيل أو الدخول بالبريد الإلكتروني"; return }
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
                googleHideChoice = null
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
            AttaPush.activeCallId = next.callId
            activeCall = next
            callMinimized = false
        }
        else {
            if (next != null && AttaPush.activeCallId == next.callId) AttaPush.activeCallId = ""
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
        // Mark the call occupied before asynchronous permission/UI work starts.
        // Delayed FCM for this exact ID must not generate a second ringtone.
        AttaPush.activeCallId = callId
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
        AttaPush.dismiss(ctx, session.callId)
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
    // A push Accept and the queued WebSocket/HTTP call-request can arrive in either
    // order. Both describe the SAME invitation; never reject it as a second call.
    fun acceptIncomingInvite(invitation: NativeCallSession) {
        val id = invitation.callId
        if (activeCall?.callId == id || pendingPermissionCall?.callId == id) {
            knownCalls.add(id)
            if (incomingCall?.callId == id) incomingCall = null
            AttaPush.dismiss(ctx, id)
            return
        }
        // A genuinely different active/pending call may still be busy.
        if (activeCall != null || pendingPermissionCall != null ||
            (incomingCall != null && incomingCall?.callId != id)) {
            knownCalls.add(id)
            if (incomingCall?.callId == id) incomingCall = null
            sendCallResponse(invitation, "busy")
            featureMessage = "توجد مكالمة أخرى جارية"
            return
        }
        // Record the accepted call BEFORE opening WebRTC. Otherwise a delayed
        // copy of the invitation can be processed as a NEW call and send "busy".
        knownCalls.add(id)
        if (incomingCall?.callId == id) incomingCall = null
        AttaPush.dismiss(ctx, id)
        openCall(invitation.peer, invitation.type, true, id)
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
    val storyImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { storyMediaUri = uri; storyMediaType = "image" }
    }
    val storyVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { storyMediaUri = uri; storyMediaType = "video" }
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
            "الرئيسية" -> {
                val page = FeedPages.fetch(::get)
                posts = FeedPages.merge(posts, page)
                notificationTargetPost?.let { previous ->
                    val target = try { get("/api/post?postId=${enc(previous.optString("id"))}").optJSONObject("post") }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    notificationTargetPost = target
                    if (target != null) posts = listOf(target) + posts.filter { it.optString("id") != target.optString("id") }
                }
                homeCursor = page.nextCursor
                olderPageFailedCursor = null
                val accountToken = token
                scope.launch {
                    try { val latest = get("/api/notifications").arr("notifications"); if (token == accountToken) notifications = latest }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
            }
            "استكشاف" -> posts = get("/api/explore").arr("posts")
            "الأصدقاء" -> { users = get("/api/users?q=${enc(search)}").arr("users"); friends = get("/api/friends").arr("friends"); requests = get("/api/friend-requests").arr("requests") }
            "الرسائل" -> {
                if (chatWith.isNotBlank()) {
                    messages = get("/api/conversation?with=${enc(chatWith)}").arr("messages")
                } else {
                    stories = get("/api/stories").arr("stories")
                    inboxLoading = true
                    try {
                        val people = get("/api/friends").arr("friends")
                        friends = people
                        val known = people.map { it.optString("username").lowercase() }
                        val peerIds = (recentChatPeers.toList() + known)
                            .filter { it.isNotBlank() && it != myUsername.lowercase() && !it.startsWith("g_") }
                            .distinct().take(50)
                        inboxPreviews = try {
                            val response = get("/api/inbox?peers=${enc(peerIds.joinToString(","))}")
                            val hidden = response.optJSONArray("hiddenPeers")
                            hiddenChatPeers = (0 until (hidden?.length() ?: 0)).map { hidden!!.optString(it) }.toSet()
                            val summaries = response.arr("summaries")
                            summaries.mapNotNull { summary ->
                                summary.optJSONObject("user")?.let { ChatPreview(it, summary.optJSONObject("lastMessage")) }
                            }
                        } catch (failure: AttaApiException) {
                            if (failure.statusCode != 404) throw failure
                            // Compatibility only while a native update precedes the
                            // backend deployment; never retry auth/server errors as N requests.
                            val byId = people.associateBy { it.optString("username").lowercase() }
                            peerIds.mapNotNull { id ->
                                val person = byId[id] ?: return@mapNotNull null
                                val last = try {
                                    get("/api/conversation?with=${enc(id)}").arr("messages")
                                        .lastOrNull { it.optString("mediaType") != "call-signal" }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { null }
                                ChatPreview(person, last)
                            }
                        }
                    } finally { inboxLoading = false }
                }
            }
            "القائمة" -> {
                // Fail closed: never retain an old owner role if the refresh fails.
                adminRole = ""
                val current = get("/api/me").optJSONObject("user")
                adminRole = current?.optString("role").orEmpty().trim().lowercase()
            }
            "الإشعارات" -> notifications = get("/api/notifications").arr("notifications")
            "المجموعات" -> groups = get("/api/groups").arr("groups")
            "الملف" -> {
                loadAccountScreen(::get, myUsername, account = ::applyAccount,
                    posts = { page -> ownPosts = FeedPages.merge(ownPosts, page); ownCursor = page.nextCursor })
                friends = get("/api/friends").arr("friends")
            }
            "المحفوظات" -> posts = get("/api/saved-posts").arr("posts")
            "الإدارة" -> {
                // Re-check the role from the server whenever this privileged screen loads.
                adminRole = get("/api/me").optJSONObject("user")?.optString("role").orEmpty().trim().lowercase()
                if (adminRole in listOf("super_admin", "moderator")) {
                    adminSummary = get("/api/admin/summary")
                    val accounts = get("/api/admin/users?q=${enc(adminSearch)}")
                    adminUsers = accounts.arr("users")
                    adminNextOffset = if (accounts.isNull("nextOffset")) -1 else accounts.optInt("nextOffset", -1)
                    // During a paused deletion only owner status and account reads are available.
                    if (adminSummary.isNull("deletion")) {
                        adminPosts = get("/api/admin/posts?q=${enc(adminPostSearch)}").arr("posts")
                        adminReports = get("/api/admin/reports").arr("reports")
                        if (adminRole == "super_admin") deletionRequests = get("/api/admin/deletion-requests").arr("requests")
                    }
                } else {
                    adminSummary = JSONObject()
                    adminUsers = emptyList()
                    adminPosts = emptyList()
                    adminReports = emptyList()
                    deletionRequests = emptyList()
                    if (tab == "الإدارة") tab = "القائمة"
                }
            }
            "الخصوصية" -> deletionRequest = get("/api/account-deletion-request").optJSONObject("request")
            "أرشيفي" -> archivedPosts = get("/api/post-archive-list").arr("posts")
            "يوتيوب" -> {
                val recommendation = when (youtubeCategory) {
                    "أحاديث" -> "الشيخ سمير مصطفى دروس ومحاضرات"
                    "الأذكار" -> "أذكار الصباح والمساء حصن المسلم"
                    "رياضة" -> "كرة القدم ملخص مباريات"
                    "أخبار" -> "قناة الجزيرة أخبار اليوم"
                    else -> "الشيخ محمد صديق المنشاوي تلاوة القرآن الكريم"
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
            // Duplicates can arrive from FCM, the socket and the polling queue.
            // In particular, a call already accepted via a background notification
            // MUST NOT receive a second "busy" response from this handler.
            if (activeCall?.callId == callId ||
                pendingPermissionCall?.callId == callId ||
                incomingCall?.callId == callId) {
                knownCalls.add(callId)
                return
            }
            val timestamp = signal.optLong("createdAt", signal.optLong("ts", 0L))
            val age = System.currentTimeMillis() - timestamp
            if (age !in 0..45000L || !knownCalls.add(callId)) return
            val invite = NativeCallSession(sender,
                if (signal.optString("callType") == "video") "video" else "audio", callId, true, invitedAt = timestamp)
            if (activeCall == null && incomingCall == null && pendingPermissionCall == null) {
                incomingCall = invite
                sendCallRinging(invite) // Ringback only after the incoming dialog is created.
            } else sendCallResponse(invite, "busy")
        } else if (kind == "hangup" || kind == "call-reject") {
            val pending = incomingCall
            if (pending?.callId == callId && pending.peer.equals(sender, ignoreCase = true)) {
                AttaPush.dismiss(ctx, callId); incomingCall = null
            }
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
        if (push.to != myUsername.lowercase()) return@LaunchedEffect
        if (System.currentTimeMillis() - push.createdAt !in 0..45000L) {
            AttaPush.dismiss(ctx, push.callId)
            featureMessage = "انتهت المكالمة الواردة. يمكنك الاتصال بالشخص من المحادثات."
            return@LaunchedEffect
        }
        val session = NativeCallSession(push.from, push.type, push.callId, incoming = true,
            displayName = push.displayName, invitedAt = push.createdAt)
        if (push.action == AttaPush.ACTION_ACCEPT) {
            acceptIncomingInvite(session)
        } else if (activeCall?.callId == push.callId ||
            pendingPermissionCall?.callId == push.callId ||
            incomingCall?.callId == push.callId) {
            knownCalls.add(push.callId) // An existing dialog/session already owns this ID.
        } else if (activeCall == null && incomingCall == null &&
            pendingPermissionCall == null && knownCalls.add(push.callId)) {
            incomingCall = session
            sendCallRinging(session)
        }
    }

    // Missed/hung-up calls must not leave an incoming dialog marked busy forever
    // if the screen was asleep when the cancellation signal arrived.
    LaunchedEffect(incomingCall?.callId) {
        val invitation = incomingCall ?: return@LaunchedEffect
        delay(if (invitation.invitedAt > 0L) remainingIncomingCallMillis(invitation.invitedAt) else 45000L)
        if (incomingCall?.callId == invitation.callId) {
            incomingCall = null
            AttaPush.dismiss(ctx, invitation.callId)
        }
    }

    DisposableEffect(token, myUsername) {
        val owner = ctx as ComponentActivity
        var overlayAllowed = CallWindow.enabled(ctx)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && overlayAllowed != CallWindow.enabled(ctx)) {
                overlayAllowed = CallWindow.enabled(ctx)
                AttaPush.register(ctx.applicationContext, token, myUsername)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) AttaPush.register(ctx.applicationContext, token, myUsername) }
    LaunchedEffect(token, myUsername, notificationsEnabled) {
        if (token.isNotBlank() && myUsername.isNotBlank() && notificationsEnabled) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else AttaPush.register(ctx.applicationContext, token, myUsername)
        }
    }
    LaunchedEffect(token) {
        if (token.isNotBlank()) try {
            post("/api/legal-consent", JSONObject().put("version", AttaPrivacy.VERSION).put("accepted", true))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The local choice still applies when the server is temporarily unavailable. */ }
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
    LaunchedEffect(token) {
        if (token.isNotBlank()) NativeRtcCall.prewarmIce(token)
    }
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
                            if (msg.optString("type") in setOf("account-reset", "account-disabled", "account-deleted")) {
                                scope.launch { clearLocalSession() }
                                return
                            }
                            val signal = if (msg.optString("type") == "chat" && msg.optString("mediaType") == "call-signal")
                                try { JSONObject(msg.optString("media")).apply {
                                    if (!has("from")) put("from", msg.optString("from"))
                                    if (!has("to")) put("to", msg.optString("to"))
                                } } catch (_: Exception) { null }
                                else msg
                            if (signal?.optString("type") in setOf("call-request", "call-ringing", "call-accept", "offer", "answer", "ice", "hangup", "call-reject")) {
                                val packet = signal!!
                                scope.launch(Dispatchers.Main) {
                                    NativeRtcCall.deliverSignal(packet)
                                    if (packet.optString("type") in setOf("call-request", "hangup", "call-reject")) handleIncomingCallEvent(packet)
                                    if (packet.optString("type") in setOf("call-request", "hangup", "call-reject") && tab == "الرسائل" && token.isNotBlank()) {
                                        try { load("الرسائل") } catch (_: Exception) { }
                                    }
                                }
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
                            } else if (msg.optString("type") == "chat-deleted") {
                                scope.launch(Dispatchers.Main) {
                                    if (tab == "الرسائل") runCatching { load("الرسائل") }
                                }
                            } else if (msg.optString("type") == "new-post") {
                                scope.launch(Dispatchers.Main) {
                                    runCatching { notifications = get("/api/notifications").arr("notifications") }
                                    android.widget.Toast.makeText(ctx, "نشر ${msg.optString("fromName", "مستخدم")} منشورًا جديدًا", android.widget.Toast.LENGTH_LONG).show()
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
    fun openNativeNotification(n: JSONObject) = task {
        val id = n.optString("id")
        if (id.isNotBlank()) post("/api/notifications/read-one", JSONObject().put("notificationId", id))
        val postId = n.optString("postId")
        if (postId.isNotBlank()) {
            val target = get("/api/post?postId=${enc(postId)}").getJSONObject("post")
            notificationTargetPost = target
            tab = "الرئيسية"
            posts = listOf(target) + posts.filter { it.optString("id") != postId }
            homeScrollTarget = 1; homeFirstPostRequest++
        } else if (n.optString("type") == "friend-request") {
            tab = "الأصدقاء"; load(tab)
        } else if (n.optString("type") in listOf("friend-accepted", "follow")) {
            openPublicProfile(n.optString("from"))
        }
        notifications = notifications.map { if (it.optString("id") == id) JSONObject(it.toString()).put("read", true) else it }
    }
    val notificationEvent = (ctx as? MainActivity)?.requestedNotificationEvent
    LaunchedEffect(notificationEvent, token) {
        if (token.isNotBlank() && notificationEvent != null) {
            try {
                val list = get("/api/notifications").arr("notifications")
                notifications = list
                list.firstOrNull { it.optString("id") == notificationEvent }?.let { openNativeNotification(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { featureMessage = e.message ?: "تعذر فتح الإشعار" }
            finally { (ctx as? MainActivity)?.clearNotificationEvent() }
        }
    }

    fun navigate(destination: String) {
        notificationTargetPost = null
        // Only the verified owner sees or enters admin; server APIs also enforce this.
        if (destination == "الإدارة" && adminRole !in listOf("super_admin", "moderator")) return
        if (destination == "الرئيسية" && token.isNotBlank()) {
            // Home always lands on the FIRST feed post, even when already on Home.
            // The scroll runs in LaunchedEffect after Home is actually composed.
            homeScrollTarget = 1
            homeFirstPostRequest++
            if (tab == destination) {
                homeRefreshRequest++
                return
            }
        }
        if (destination == "القائمة" && tab != "القائمة") {
            // Display owner/moderator controls only after /api/me verifies this session.
            adminRole = ""
        }
        if (tab != destination) { visited.add(tab); tab = destination }
    }
    fun openHomeComposer() {
        // Choosing a Quran/YouTube attachment must leave the write box visible
        // until Publish; explicit Home taps still jump to the first feed post.
        navigate("الرئيسية")
        homeScrollTarget = 0
        homeFirstPostRequest++
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
                AttaPush.unregister(ctx.applicationContext, token)
                clearLocalSession()
            }) { Text("تسجيل الخروج", color = GoldLight) }
        },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("إلغاء", color = White) } }
    )
    legalDocument?.let { LegalDocument(it) { legalDocument = null } }
    reportTarget?.let { target -> AlertDialog(
        onDismissRequest = { if (!busy) reportTarget = null }, containerColor = Panel,
        title = { Text("الإبلاغ عن محتوى أو حساب", color = GoldLight) },
        text = { Column {
            Text("راجع سبب البلاغ. ستطّلع عليه الإدارة لاتخاذ الإجراء المناسب. لا تضع كلمات مرور أو بيانات خاصة.", color = White)
            OutlinedTextField(reportReason, { reportReason = it.take(500) }, label = { Text("سبب الإبلاغ") }, minLines = 3)
        } },
        confirmButton = { TextButton(enabled = !busy && reportReason.trim().length >= 3, onClick = { task {
            val result = post("/api/report", JSONObject().put("targetType", target.first).put("targetId", target.second).put("reason", reportReason.trim()))
            reportTarget = null; reportReason = ""
            featureMessage = if (result.optBoolean("alreadyReported")) "البلاغ موجود بالفعل وقيد المراجعة" else "تم إرسال البلاغ إلى الإدارة"
        } }) { Text("إرسال البلاغ", color = GoldLight) } },
        dismissButton = { TextButton(onClick = { reportTarget = null }) { Text("إلغاء") } }
    ) }
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
        var callerName by remember(invitation.callId) { mutableStateOf(
            invitation.displayName.ifBlank {
                friends.firstOrNull { it.optString("username").equals(invitation.peer, true) }?.name() ?: "مستخدم"
            }) }
        LaunchedEffect(invitation.peer) {
            runCatching { get("/api/profile-public?username=${enc(invitation.peer)}").optJSONObject("user")?.name() }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { callerName = it }
        }
        IncomingCallRingtone(PushCall(invitation.peer, myUsername.lowercase(), invitation.callId,
            invitation.type, System.currentTimeMillis(), AttaPush.ACTION_VIEW, callerName))
        IncomingCallDialog(callerName, invitation.type,
            onClick = { acceptIncomingInvite(invitation) },
            onReject = { sendCallResponse(invitation, "rejected"); incomingCall = null })
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
                Text("شارك نصًا أو صورة أو فيديو", color = Muted, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { storyImagePicker.launch("image/*") }) { Text("صورة", color = GoldLight) }
                    TextButton(enabled = !busy, onClick = { storyVideoPicker.launch("video/*") }) { Text("فيديو", color = GoldLight) }
                    if (storyMediaUri != null) TextButton(enabled = !busy, onClick = { storyMediaUri = null; storyMediaType = "" }) { Text("إزالة", color = Muted) }
                }
                if (storyMediaUri != null) Text(if (storyMediaType == "video") "تم اختيار فيديو" else "تم اختيار صورة", color = GoldLight)
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
                val text = storyText.trim()
                val uri = storyMediaUri
                val type = storyMediaType
                task {
                    val url = if (uri != null) withContext(Dispatchers.IO) {
                        val mime = ctx.contentResolver.getType(uri) ?: if (type == "video") "video/mp4" else "image/jpeg"
                        uploadChatMedia(ctx, token, uri, mime, type)
                    } else ""
                    post("/api/stories", JSONObject().put("text", text).put("media", url).put("mediaType", type))
                    storyText = ""; storyMediaUri = null; storyMediaType = ""
                    showStoryComposer = false
                    load("الرسائل")
                }
            }, enabled = !busy && (storyText.isNotBlank() || storyMediaUri != null)) {
                Text(if (busy) "جارٍ النشر…" else "نشر القصة", color = GoldLight)
            }
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
                        if (st.optString("mediaType") == "video" || storyMedia.contains(".mp4", true)) NativeVideo(storyMedia)
                        else ProfileMediaImage(storyMedia, "القصة", Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedStory = null }) { Text("إغلاق", color = GoldLight) } }
        )
    }
    adminPasswordAction?.let { action ->
        AlertDialog(onDismissRequest = { adminPasswordAction = null; adminProtectionPassword = "" },
            containerColor = Panel, title = { Text("كلمة حماية الإدارة", color = GoldLight) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("كلمة حذف الحسابات المحفوظة في حساب المدير. اكتبها لكل عملية حذف.", color = Muted, fontSize = 12.sp)
                PremiumAuthTextField(adminProtectionPassword, { adminProtectionPassword = it.take(128) }, "كلمة الحماية", password = true)
            } },
            confirmButton = { TextButton(enabled = adminProtectionPassword.isNotBlank(), onClick = {
                val secret = adminProtectionPassword; adminProtectionPassword = ""; adminPasswordAction = null; action(secret)
            }) { Text("تأكيد", color = GoldLight) } },
            dismissButton = { TextButton(onClick = { adminPasswordAction = null; adminProtectionPassword = "" }) { Text("إلغاء") } })
    }
    if (adminEditUser.isNotBlank() || adminEditPost.isNotBlank()) AlertDialog(
        onDismissRequest = { adminEditUser = ""; adminEditPost = "" }, containerColor = Panel,
        title = { Text(if (adminEditUser.isNotBlank()) "تعديل اسم الحساب" else "تعديل المنشور", color = GoldLight) },
        text = { OutlinedTextField(if (adminEditUser.isNotBlank()) adminEditUserName else adminEditPostText,
            { if (adminEditUser.isNotBlank()) adminEditUserName = it.take(60) else adminEditPostText = it.take(50000) },
            modifier = Modifier.fillMaxWidth(), label = { Text("النص الجديد") }) },
        confirmButton = { TextButton(onClick = {
            val username = adminEditUser; val id = adminEditPost; val name = adminEditUserName; val text = adminEditPostText
            adminEditUser = ""; adminEditPost = ""
            task {
                post("/api/admin/action", JSONObject().put("action", if (username.isNotBlank()) "update_user" else "update_post")
                    .put("username", username).put("postId", id).put("displayName", name).put("text", text))
                load("الإدارة")
            }
        }) { Text("حفظ", color = GoldLight) } },
        dismissButton = { TextButton(onClick = { adminEditUser = ""; adminEditPost = "" }) { Text("إلغاء") } }
    )
    if (confirmPostDelete.isNotBlank()) AlertDialog(
        onDismissRequest = { confirmPostDelete = "" }, containerColor = Panel,
        title = { Text("حذف المنشور؟", color = GoldLight) },
        text = { Text("الحذف نهائي ولن تتمكن من استعادة المنشور.", color = White) },
        confirmButton = { TextButton(onClick = {
            val id = confirmPostDelete; confirmPostDelete = ""
            task {
                if (adminRole == "super_admin") post("/api/admin/action", JSONObject().put("action", "delete_post").put("postId", id))
                else post("/api/post-delete", JSONObject().put("postId", id))
                load(tab)
            }
        }) { Text("حذف نهائي", color = Color(0xFFFF8B8B)) } },
        dismissButton = { TextButton(onClick = { confirmPostDelete = "" }) { Text("إلغاء", color = GoldLight) } }
    )
    if (confirmAccountUser.isNotBlank() && adminPasswordAction == null) {
        val permanent = confirmAccountAction == "delete_user"
        val actionLabel = if (permanent) "حذف الحساب نهائيًا" else if (confirmAccountAction == "ban") "حظر الحساب" else "تعطيل الحساب"
        AlertDialog(
            onDismissRequest = { if (!busy) confirmAccountUser = "" }, containerColor = Panel,
            title = { Text(actionLabel, color = GoldLight) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (permanent) "سيُحذف @$confirmAccountUser مع منشوراته ورسائله وملفاته نهائيًا. يمكنه إنشاء حساب جديد بنفس الاسم والإيميل بعد اكتمال الحذف."
                    else "سيُمنع @$confirmAccountUser من الدخول وسيُخفى حسابه ومحتواه حتى إعادة التفعيل.", color = White)
                if (accountDeleteProgress.isNotBlank()) Text(accountDeleteProgress, color = GoldLight, fontSize = 12.sp)
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF8B8B), fontSize = 12.sp)
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val username = confirmAccountUser
                val action = confirmAccountAction
                val perform: (String) -> Unit = { secret -> task {
                    if (permanent) {
                        var done = false
                        while (!done) {
                            val result = post("/api/admin/users/delete", JSONObject().put("username", username).put("confirmation", username).put("adminPassword", secret))
                            done = result.optBoolean("done")
                            accountDeleteProgress = "جاري حذف بيانات الحساب... ملفات محذوفة: ${result.optInt("deletedMedia")}"
                        }
                    } else post("/api/admin/action", JSONObject().put("action", action).put("username", username))
                    confirmAccountUser = ""; accountDeleteProgress = ""
                    load("الإدارة")
                } }
                if (permanent) adminPasswordAction = perform else perform("")
            }) { Text(if (busy) "جاري التنفيذ..." else actionLabel, color = Color(0xFFFF8B8B)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmAccountUser = "" }) { Text("إغلاق", color = GoldLight) } }
        )
    }
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
                if (user != null) applyAccount(user)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* UI can still use the account initial. */ }
        } else { myAvatar = ""; adminRole = "" }
    }
    // Only scroll a composed Home list. The composer is row 0 and the first
    // actual post is row 1. Avoid the old detached-list animateScroll crash.
    LaunchedEffect(token, tab, homeFirstPostRequest) {
        if (token.isNotBlank() && tab == "الرئيسية") {
            withFrameNanos { }
            homeFeedListState.scrollToItem(
                if (homeScrollTarget == 0 || posts.isEmpty()) 0 else 1
            )
        }
    }
    LaunchedEffect(token, tab, homeRefreshRequest) {
        if (token.isNotBlank()) {
            val generation = ++screenLoadGeneration
            screenLoading = true; error = ""
            try { load(tab) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = loadingFailureMessage(e) }
            finally { if (screenLoadGeneration == generation) screenLoading = false }
        }
    }
    // Scroll-to-load uses the SAME cursor endpoint as the former button.
    // Only Home's composed LazyColumn drives it; no duplicate feed or player.
    LaunchedEffect(token, tab) {
        if (token.isBlank() || tab != "الرئيسية") return@LaunchedEffect
        snapshotFlow {
            val layout = homeFeedListState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            val cursor = homeCursor
            if (cursor != null && cursor != olderPageFailedCursor &&
                !screenLoading && !loadingMore && posts.isNotEmpty() &&
                homeFeedListState.firstVisibleItemIndex > 0 &&
                lastVisible >= layout.totalItemsCount - 3) cursor else null
        }.collect { cursor ->
            if (cursor == null) return@collect
            val accountToken = token
            val loadGeneration = screenLoadGeneration
            loadingMore = true
            try {
                val next = FeedPages.fetch(::get, cursor)
                // A pull-to-refresh or account change must not append stale results.
                if (token == accountToken && tab == "الرئيسية" &&
                    homeCursor == cursor && screenLoadGeneration == loadGeneration) {
                    posts = FeedPages.merge(posts, next)
                    homeCursor = next.nextCursor
                    olderPageFailedCursor = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                olderPageFailedCursor = cursor
                error = loadingFailureMessage(failure)
            } finally {
                loadingMore = false
            }
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
        (ctx as ComponentActivity).lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
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
    }
    // Only refresh a visible conversation. Incoming-call/socket handling retains
    // its separate lifetime so background calls continue to work.
    LaunchedEffect(tab, chatWith, token) {
        if (tab == "الرسائل" && chatWith.isNotBlank() && token.isNotBlank()) {
            val other = chatWith
            (ctx as ComponentActivity).lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(6000)
                    try {
                        val latest = get("/api/conversation?with=${enc(other)}").arr("messages")
                        if (chatWith == other && tab == "الرسائل") messages = latest
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Manual refresh remains available. */ }
                }
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
            Column(if (youtubePipMode) Modifier.size(0.dp) else Modifier.fillMaxSize().background(Black).safeDrawingPadding()) {
                if (token.isBlank()) {
                    // Scroll only the authentication screen; Android navigation remains unobstructed.
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp, vertical = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(painterResource(R.drawable.logo_premium), "شعار العطا",
                            Modifier.size(98.dp).clip(CircleShape).attaGoldShine())
                        Text("AL-ATTA", color = GoldLight, fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
                        Spacer(Modifier.height(8.dp))
                        if (authMode == "login") {
                            PremiumAuthTextField(username, { username = it }, "الهاتف أو البريد أو اسم المستخدم")
                            PremiumAuthTextField(password, { password = it }, "كلمة السر", password = true)
                            PremiumAuthGoldButton("تسجيل الدخول", !busy && username.isNotBlank() && password.isNotBlank()) {
                                task {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            api("/api/login", JSONObject().put("username", username.trim()).put("password", password))
                                        }
                                        unregisteredEmailOffer = ""
                                        applySession(result, username.trim())
                                    } catch (e: Exception) {
                                        if (e.message?.contains("EMAIL_NOT_REGISTERED") == true &&
                                            username.trim().contains("@")) {
                                            unregisteredEmailOffer = username.trim()
                                            regEmail = username.trim()
                                        } else throw e
                                    }
                                }
                            }
                            if (unregisteredEmailOffer.isNotBlank()) {
                                Text("هذا البريد غير مسجّل في تواصل العطا. هل تريد إنشاء حساب جديد؟",
                                    color = GoldLight, fontSize = 13.sp)
                                PremiumAuthGoldButton("إنشاء حساب بهذا البريد", !busy) {
                                    regEmail = unregisteredEmailOffer
                                    unregisteredEmailOffer = ""
                                    error = ""
                                    authMode = "register"
                                }
                            }
                            if (hasGoogleServices) {
                            Text("أو اختر حساب Google من داخل التطبيق", color = GoldLight,
                                fontSize = 12.sp, textAlign = TextAlign.Center)
                            PremiumGoogleButton(enabled = !busy, label = "الدخول بحساب Google") {
                                chooseGoogle(false)
                            }
                            if (!googleEnabled) Text("يتطلب خدمات Google Play وتطابق OAuth مع توقيع التطبيق. اضغط لإعادة التحقق.", color = Muted, fontSize = 11.sp)
                            } else Text("هذا الهاتف يستخدم تسجيل الدخول بالبريد أو اسم المستخدم وكلمة المرور.", color = Muted, fontSize = 12.sp)
                            TextButton(onClick = { authMode = "forgot" }) {
                                Text("هل نسيت كلمة السر؟", color = GoldLight, fontWeight = FontWeight.Bold)
                            }
                            PremiumAuthOutlineButton("إنشاء حساب جديد") { authMode = "register"; error = ""; unregisteredEmailOffer = "" }
                        } else if (authMode == "register") {
                            Text("إنشاء حساب جديد", color = GoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            if (hasGoogleServices) {
                            PremiumGoogleButton(enabled = !busy, label = "إنشاء حساب عن طريق جوجل") {
                                chooseGoogle(true)
                            }
                            if (!googleEnabled) Text("اضغط Google للتحقق من الاتصال والإعداد. لو تعذر الاختيار ستظهر رسالة بالسبب.", color = Muted, fontSize = 11.sp)
                            }
                            Text("أو أنشئ حسابًا بالبريد الإلكتروني", color = GoldLight, fontSize = 13.sp)
                            PremiumAuthTextField(regName, { regName = it }, "الاسم الكامل")
                            PremiumAuthTextField(regUsername, { regUsername = it.lowercase().filter { c -> c.isLetterOrDigit() && c.code < 128 || c == '_' }.take(24) }, "اختر اسم مستخدم فريد (مطلوب)")
                            AttaBirthDatePicker(regBirthDate, { regBirthDate = it })
                            PremiumAuthTextField(regPhone, { regPhone = it }, "رقم الهاتف (اختياري)")
                            PremiumAuthTextField(regEmail, { regEmail = it }, "البريد الإلكتروني لاستقبال كود التأكيد")
                            AttaSignupPrivacyChoice(regGender, { regGender = it }, regHideChoice, { regHideChoice = it })
                            PremiumAuthTextField(regCountry, { regCountry = it }, "الدولة (اختياري)")
                            PremiumAuthTextField(regCity, { regCity = it }, "المدينة (اختياري)")
                            PremiumAuthTextField(regPass, { regPass = it }, "كلمة المرور", password = true)
                            PremiumAuthTextField(regConfirm, { regConfirm = it }, "تأكيد كلمة المرور", password = true)
                            SignupLegalConsent(regPoliciesAccepted, { regPoliciesAccepted = it },
                                regTermsAccepted, { regTermsAccepted = it }, { legalDocument = it })
                            if (!birthDateSignup) Text("إنشاء حساب بتاريخ الميلاد يحتاج نشر تحديث Cloudflare v1.0.14 أولًا.", color = Color(0xFFFFC78E), fontSize = 12.sp)
                            PremiumAuthGoldButton("إرسال كود التأكيد", !busy && regPoliciesAccepted && regTermsAccepted && birthDateSignup && regName.trim().length >= 3 && regUsername.matches(Regex("[a-z0-9_]{3,24}")) && attaAgeFromBirthDate(regBirthDate) != null && regEmail.isNotBlank() && regGender in listOf("male", "female") && (regGender != "female" || regHideChoice != null) && regPass.length >= 6 && regPass == regConfirm) {
                                task {
                                    val body = JSONObject()
                                        .put("fullName", regName.trim()).put("username", regUsername.trim())
                                        .put("birthDate", regBirthDate).put("age", attaAgeFromBirthDate(regBirthDate) ?: 0).put("phone", regPhone.trim())
                                        .put("email", regEmail.trim()).put("gender", regGender)
                                        .put("accountHidden", regGender == "female" && regHideChoice == true)
                                        .put("country", regCountry.trim()).put("city", regCity.trim()).put("password", regPass)
                                        .put("confirmPassword", regConfirm)
                                        .put("legalAccepted", true).put("legalVersion", AttaPrivacy.VERSION)
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
                            AttaSignupPrivacyChoice(googleGender, { googleGender = it }, googleHideChoice, { googleHideChoice = it })
                            SignupLegalConsent(googlePoliciesAccepted, { googlePoliciesAccepted = it },
                                googleTermsAccepted, { googleTermsAccepted = it }, { legalDocument = it })
                            if (!nativeUsernameSignup || !birthDateSignup) Text(
                                "خادم Cloudflare الحالي لا يعلن دعمه لإكمال تسجيل Google. يجب نشر تحديث الخادم أولًا؛ لن نُنشئ اسم مستخدم تلقائيًا.",
                                color = Color(0xFFFFC78E), fontSize = 12.sp)
                            PremiumAuthGoldButton("تأكيد إنشاء الحساب", !busy && googleIdToken.isNotBlank() &&
                                googlePoliciesAccepted && googleTermsAccepted) {
                                when {
                                    !nativeUsernameSignup || !birthDateSignup ->
                                        error = "إعداد Cloudflare قديم: يجب نشر تحديث إنشاء حساب Google وتاريخ الميلاد أولًا. لن نرسل كود بريد مع Google."
                                    !googleUsername.matches(Regex("[a-z0-9_]{3,24}")) ->
                                        error = "اختر اسم مستخدم من 3 إلى 24 حرفًا إنجليزيًا أو رقمًا أو _."
                                    googleName.trim().length < 2 -> error = "اكتب اسمك الظاهر (حرفين على الأقل)."
                                    googlePassword.length < 8 -> error = "كلمة المرور يجب أن تتكون من 8 أحرف على الأقل."
                                    googlePassword != googlePasswordConfirm -> error = "تأكيد كلمة المرور غير مطابق."
                                    attaAgeFromBirthDate(googleBirthDate) == null -> error = "اختر تاريخ ميلاد صحيحًا من التقويم."
                                    googleGender !in listOf("male", "female") -> error = "اختيار النوع (ذكر أو أنثى) إجباري."
                                    googleGender == "female" && googleHideChoice == null -> error = "اختاري هل تريدين إخفاء الحساب أم لا."
                                    else -> task {
                                        val data = JSONObject()
                                            .put("credential", googleIdToken)
                                            .put("signupFlow", "android-signup")
                                            .put("fullName", googleName.trim())
                                            .put("username", googleUsername.trim())
                                            .put("birthDate", googleBirthDate)
                                            .put("age", attaAgeFromBirthDate(googleBirthDate))
                                            .put("city", googleCity.trim())
                                            .put("gender", googleGender)
                                            .put("accountHidden", googleGender == "female" && googleHideChoice == true)
                                            .put("password", googlePassword)
                                            .put("confirmPassword", googlePasswordConfirm)
                                            .put("legalAccepted", true).put("legalVersion", AttaPrivacy.VERSION)
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
                    if (tab != "الرئيسية" && tab != "الرسائل" && tab != "القرآن" &&
                        !(compactYoutubeLayout && tab == "يوتيوب" && youtubePlayRequested)) {
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
                            if (tab in setOf("القرآن", "الأحاديث", "الأذكار", "يوتيوب", "الأصدقاء", "الرياضة")) {
                                val isYoutube = tab == "يوتيوب"
                                val isFriends = tab == "الأصدقاء"
                                val isFootball = tab == "الرياضة"
                                AttaCompactSearch(
                                    value = if (isYoutube) youtubeSearch else if (isFriends) search else if (isFootball) footballSearch else knowledgeSearch,
                                    onValueChange = { value ->
                                        when {
                                            isYoutube -> youtubeSearch = value
                                            isFriends -> search = value
                                            isFootball -> footballSearch = value
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
                    if (busy || screenLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Gold)
                    if (error.isNotBlank()) Row(
                        Modifier.fillMaxWidth().background(Color(0xFF271818)).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(error, color = Color(0xFFFFA0A0), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(enabled = !busy && !screenLoading, onClick = { refresh() }) { Text("إعادة المحاولة", color = GoldLight) }
                    }
                    when (tab) {
                        "القرآن" -> QuranReaderScreen(Modifier.weight(1f),
                            onExit = { if (visited.isNotEmpty()) tab = visited.removeAt(visited.lastIndex) else tab = "الرئيسية" },
                            initialPage = quranPage, readingRange = quranReadingRange,
                            onPageChange = { quranPage = it }, onReadAll = { quranReadingRange = null },
                            onStartAudio = { youtubePlayRequested = false },
                            onSharePost = { shared ->
                                val previousTitle = mushafPostDraft?.selection?.title
                                mushafPostDraft?.discard()
                                mushafPostDraft = shared; pickedUri = Uri.fromFile(shared.preview); pickedMime = "image/png"
                                draft = if (draft.isBlank() || draft == previousTitle) shared.selection.title
                                    else draft.trim()+"\n\n"+shared.selection.title
                                openHomeComposer()
                            })
                        "الأحاديث", "الأذكار" -> KnowledgeScreen(tab, knowledgeSearch, Modifier.weight(1f),
                            onClearQuery = { knowledgeSearch = "" },
                            onShareHadithPost = { hadith ->
                                val composed = composeHadithPost(draft, hadith)
                                if (composed == null) {
                                    val kind = if (tab == "الأذكار") "الذكر" else "الحديث"
                                    featureMessage = "$kind مع نص المنشور يتجاوز 50000 حرف. لن يُختصر النص؛ احذف نصًا من المسودة أولًا."
                                } else {
                                    draft = composed
                                    openHomeComposer()
                                }
                            })
                        "الرياضة" -> FootballScreen(footballSearch, Modifier.weight(1f), isAdmin = adminRole == "super_admin", token = token, callInProgress = activeCall != null,
                            onManage = { navigate("الإدارة") }, onBeforePlayback = { youtubePlayRequested = false; activeYoutubePostId = "" },
                            onYoutube = { id ->
                                activeYoutubePostId = ""; youtubeId = id; youtubePlayRequested = true
                                navigate("يوتيوب")
                            })
                        "الرئيسية", "استكشاف", "المحفوظات" -> {
                            PullToRefreshBox(
                                isRefreshing = screenLoading,
                                onRefresh = {
                                    if (!screenLoading && !busy) {
                                        if (tab == "الرئيسية") homeRefreshRequest++
                                        else refresh()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                LazyColumn(
                                    Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                    state = if (tab == "الرئيسية") homeFeedListState else otherFeedListState,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
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

                                        }
                                        Spacer(Modifier.height(8.dp))
                                        // An inset publish action in the composer; keep the existing
                                        // upload/publish callback unchanged.
                                        OutlinedTextField(draft, { draft = it },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
                                            placeholder = { Text("بم تفكر؟", fontSize = 14.sp) },
                                            minLines = 2, shape = RoundedCornerShape(14.dp),
                                            trailingIcon = {
                                            Box(Modifier.padding(horizontal = 6.dp)) {
                                                GoldPublishButton(!busy && (draft.isNotBlank() || pickedUri != null ||
                                                    youtubePostDraftId.isNotBlank() || mushafPostDraft != null)) {
                                                task {
                                                    var image = ""; var video = ""
                                                    var postText = draft
                                                    val sharedMushaf = mushafPostDraft
                                                    val sharedYoutube = youtubePostDraftId
                                                    require(sharedYoutube.isBlank() || (sharedMushaf == null && pickedUri == null)) {
                                                        "أزل المرفق الآخر قبل نشر فيديو يوتيوب"
                                                    }
                                                    if (sharedMushaf != null) require(postText.length <= 4500) { "اكتب وصفًا أقصر من 4500 حرف" }
                                                    if (sharedYoutube.isNotBlank()) postText = attachYoutubeToPost(postText, sharedYoutube)
                                                    pickedUri?.let { uri ->
                                                        val mime = pickedMime.ifBlank { "image/jpeg" }
                                                        val url = withContext(Dispatchers.IO) {
                                                            uploadChatMedia(ctx, token, uri, mime, if (mime.startsWith("video/")) "video" else "image")
                                                        }
                                                        if (mime.startsWith("video/")) {
                                                            video = url
                                                            val coverBytes = runCatching { VideoPostPreview.uploadCover(ctx, uri) }.getOrNull()
                                                            if (coverBytes != null) {
                                                                image = withContext(Dispatchers.IO) {
                                                                    upload(token, "image/jpeg", "video-cover.jpg", coverBytes)
                                                                }
                                                            }
                                                        } else image = url
                                                    }
                                                    if (sharedMushaf != null) {
                                                        val pdfPath = withContext(Dispatchers.IO) {
                                                            uploadChatMedia(ctx,token,Uri.fromFile(sharedMushaf.document),"application/pdf","file")
                                                        }
                                                        postText += "\n\n"+mushafAttachmentLink(BASE,pdfPath,sharedMushaf.selection)
                                                    }
                                                    post("/api/posts", JSONObject().put("text", postText).put("image", image).put("video", video))
                                                    sharedMushaf?.discard(); mushafPostDraft = null
                                                    youtubePostDraftId = ""; youtubePostDraftTitle = ""
                                                    draft = ""; pickedUri = null; load("الرئيسية")
                                                    homeScrollTarget = 1
                                                    homeFirstPostRequest++

                                                }
                                            }
                                        }
                                        })
                                        pickedUri?.let { Text("مرفق: ${it.lastPathSegment ?: "ملف"}", color = GoldLight, fontSize = 11.sp) }
                                        youtubePostDraftId.takeIf { it.isNotBlank() }?.let { videoId ->
                                            Text("فيديو يوتيوب مرفق", color = GoldLight, fontSize = 12.sp)
                                            AsyncImage("https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                                                "معاينة فيديو يوتيوب", Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                                contentScale = ContentScale.Fit)
                                            if (youtubePostDraftTitle.isNotBlank())
                                                Text(youtubePostDraftTitle, color = White, fontSize = 12.sp)
                                            TextButton(onClick = {
                                                youtubePostDraftId = ""; youtubePostDraftTitle = ""
                                            }) { Text("إزالة فيديو يوتيوب", color = Muted) }
                                        }
                                        mushafPostDraft?.let { shared ->
                                            MushafPostGallery(shared.selection.pages, Uri.fromFile(shared.preview), Modifier.fillMaxWidth())
                                            Text("${shared.selection.title} • ${shared.selection.pageCount} صفحة • ملف كامل للقراءة", color = GoldLight, fontSize = 11.sp)
                                            TextButton(onClick = { shared.discard(); mushafPostDraft = null; pickedUri = null }, enabled = !busy) {
                                                Text("إزالة المرفق", color = Muted)
                                            }
                                        }
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
                                var showReactors by remember(postId) { mutableStateOf(false) }
                                var showFullscreenVideo by remember(postId) { mutableStateOf(false) }
                                val remoteLiked = p.optBoolean("likedByMe")
                                val remoteReaction = p.optString("myReaction")
                                val serverReaction = if (remoteReaction.isNotBlank()) remoteReaction
                                    else if (remoteLiked) "like" else ""
                                val reactionCounts = p.optJSONObject("reactionCounts") ?: JSONObject()
                                val reactionTotal = listOf("like", "love", "haha", "wow", "sad", "angry", "dislike")
                                    .sumOf { reactionCounts.optInt(it) }
                                // One user has one reaction: server totals already
                                // deduplicate legacy likes and modern reactions.
                                val baseTotal = p.optInt("reactionCount",
                                    p.optInt("likeCount") + reactionTotal)
                                var selectedReaction by remember(postId, serverReaction) {
                                    mutableStateOf(serverReaction)
                                }
                                val visibleLikeCount = (baseTotal +
                                    (if (selectedReaction.isNotBlank()) 1 else 0) -
                                    (if (serverReaction.isNotBlank()) 1 else 0)).coerceAtLeast(0)
                                CardBox {
                                    var postMenuExpanded by remember(postId) { mutableStateOf(false) }
                                    val isMyPost = p.optString("author").equals(myUsername, ignoreCase = true)
                                    val canModerate = adminRole == "super_admin"
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
                                                    DropdownMenuItem(text = { Text("الإبلاغ عن المنشور أو تعليقاته", color = GoldLight) }, onClick = {
                                                        postMenuExpanded = false; reportReason = ""; reportTarget = "post" to postId
                                                    })
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
                                                        postMenuExpanded = false; confirmAccountUser = p.optString("author")
                                                    })
                                                }
                                            }
                                        }
                                    }
                                    val visiblePostText = visibleYoutubePostText(visibleMushafPostText(p.optString("text"), BASE))
                                    PostTextContent(visiblePostText, modifier = Modifier.padding(vertical = 10.dp))
                                    MushafPostActions(p.optString("text")) { pages ->
                                        quranReadingRange = pages; quranPage = pages.first; navigate("القرآن")
                                    }
                                    youtubePostId(p.optString("text"))?.let { videoId ->
                                        YoutubePostMedia(videoId, activeYoutubePostId == postId,
                                            onPlay = { youtubePlayRequested = false; activeYoutubePostId = postId },
                                            onStop = { if (activeYoutubePostId == postId) activeYoutubePostId = "" })
                                    }
                                    val image = media(p.optString("image")); val video = media(p.optString("video"))
                                    val mushafPages = parseMushafPostAttachment(p.optString("text"), BASE)?.pages
                                    if (mushafPages != null) MushafPostGallery(mushafPages, image.takeIf { it.isNotBlank() })
                                    if (image.isNotBlank() && mushafPages == null && video.isBlank()) ExpandableProfileMedia(image, "صورة المنشور",
                                        Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                            .aspectRatio(1.15f).clip(RoundedCornerShape(11.dp))
                                            .border(.6.dp, Border.copy(alpha = .75f), RoundedCornerShape(11.dp)),
                                        contentScale = ContentScale.Crop)
                                    if (video.isNotBlank()) {
                                        // Open the sole existing Media3 viewer with the full feed
                                        // playlist, so vertical swipes go to the next post video.
                                        AttaExpandableVideo(video, image.takeIf { it.isNotBlank() },
                                            onOpen = { showFullscreenVideo = true })
                                        if (showFullscreenVideo) {
                                            AttaPostMediaViewer(
                                                posts = posts, startId = postId, kind = "video",
                                                onDismiss = { showFullscreenVideo = false },
                                                loadInteractions = { id, kind, offset ->
                                                    get("/api/post-interactions?postId=${enc(id)}&kind=${enc(kind)}&offset=$offset")
                                                },
                                                sendReaction = { id, reaction, remove ->
                                                    post("/api/post-react", JSONObject().put("postId", id)
                                                        .put("reaction", reaction).put("remove", remove))
                                                },
                                                sendComment = { id, message ->
                                                    post("/api/post-comment", JSONObject().put("postId", id)
                                                        .put("text", message))
                                                },
                                                onShare = { id ->
                                                    post("/api/post-share", JSONObject().put("postId", id)
                                                        .put("text", ""))
                                                    showFullscreenVideo = false
                                                    if (tab != "الرئيسية") navigate("الرئيسية")
                                                    load("الرئيسية")
                                                    homeScrollTarget = 1
                                                    homeFirstPostRequest++
                                                },
                                                onOpenProfile = { user ->
                                                    showFullscreenVideo = false
                                                    openPublicProfile(user)
                                                }
                                            )
                                        }
                                    }
                                    p.optJSONObject("sharedPost")?.let { shared ->
                                        val visibleSharedText = visibleYoutubePostText(visibleMushafPostText(shared.optString("text"), BASE))
                                        PostTextContent(visibleSharedText, color = GoldLight)
                                        MushafPostActions(shared.optString("text")) { pages ->
                                            quranReadingRange = pages; quranPage = pages.first; navigate("القرآن")
                                        }
                                        parseMushafPostAttachment(shared.optString("text"), BASE)?.let { attachment ->
                                            MushafPostGallery(attachment.pages, media(shared.optString("image")).takeIf { it.isNotBlank() })
                                        }
                                        youtubePostId(shared.optString("text"))?.let { videoId ->
                                            val sharedYoutubePostId = "shared_" + postId
                                            YoutubePostMedia(videoId, activeYoutubePostId == sharedYoutubePostId,
                                                onPlay = { youtubePlayRequested = false; activeYoutubePostId = sharedYoutubePostId },
                                                onStop = { if (activeYoutubePostId == sharedYoutubePostId) activeYoutubePostId = "" })
                                        }
                                    }
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
                                                onShowPeople = { showReactors = true },
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
                                                task {
                                                    post("/api/post-share", JSONObject().put("postId", postId).put("text", ""))
                                                    if (tab != "الرئيسية") navigate("الرئيسية")
                                                    load("الرئيسية")
                                                    homeScrollTarget = 1
                                                    homeFirstPostRequest++
                                                }
                                            }
                                        }
                                        PremiumPostAction("bookmark",
                                            if (p.optBoolean("savedByMe")) "محفوظ" else "حفظ", GoldLight) {
                                            task { post("/api/post-save", JSONObject().put("postId", postId)); load(tab) }
                                        }
                                    }
                                    if (showReactors) {
                                        AttaPostReactorsDialog(
                                            postId = postId,
                                            onClose = { showReactors = false },
                                            loadPeople = { offset ->
                                                get("/api/post-interactions?postId=${enc(postId)}&kind=reactions&offset=$offset&limit=30")
                                            },
                                            onProfile = { user ->
                                                showReactors = false
                                                openPublicProfile(user)
                                            }
                                        )
                                    }
                                    if (showComments) {
                                        AttaRightAlignedComments(
                                            postId = postId,
                                            onClose = { showComments = false },
                                            fetchComments = { get("/api/post-interactions?postId=${enc(postId)}&kind=comments&limit=50") },
                                            sendComment = { message ->
                                                post("/api/post-comment", JSONObject().put("postId", postId).put("text", message))
                                                load(tab)
                                            },
                                            sendReply = { commentId, message ->
                                                post("/api/comment-reply", JSONObject().put("postId", postId)
                                                    .put("commentId", commentId).put("text", message))
                                                load(tab)
                                            },
                                            onProfile = { user -> showComments = false; openPublicProfile(user) }
                                        )
                                    }
                                }
                            }
                            if (tab == "الرئيسية" && loadingMore) item {
                                Box(Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        color = GoldLight, strokeWidth = 2.dp,
                                        modifier = Modifier.size(26.dp))
                                }
                            }
                            if (posts.isEmpty() && !screenLoading && error.isBlank()) item {
                                Text("لا توجد منشورات ظاهرة حاليًا", color = Muted, modifier = Modifier.padding(16.dp))
                            }
                                }
                            }
                        }
                        "الخصوصية" -> AccountPrivacyScreen(myUsername, busy, notificationsEnabled,
                            when (AttaPush.provider(ctx)) { "hms" -> "Huawei Push Kit"; "fcm" -> "Firebase Cloud Messaging"; else -> "لا توجد خدمة إشعارات مدعومة؛ يبقى التحديث داخل التطبيق عند فتحه" },
                            deletionRequest,
                            onNotifications = { enabled ->
                                notificationsEnabled = enabled; AttaPrivacy.setNotifications(ctx, enabled)
                                if (!enabled) AttaPush.unregister(ctx.applicationContext, token)
                            },
                            onRequestDeletion = { confirmation, reason -> task {
                                deletionRequest = post("/api/account-deletion-request", JSONObject().put("confirmation", confirmation).put("reason", reason)).optJSONObject("request")
                                featureMessage = "تم تسجيل طلب حذف الحساب. يمكنك متابعة حالته أو إلغاؤه من هذه الصفحة."
                            } },
                            onCancelDeletion = { task {
                                post("/api/account-deletion-request", JSONObject().put("action", "cancel"))
                                load("الخصوصية")
                            } }, onRefresh = { task { load("الخصوصية") } },
                            onWithdraw = {
                                val oldSession = token
                                AttaPush.unregister(ctx.applicationContext, oldSession)
                                scope.launch { withContext(Dispatchers.IO) {
                                    runCatching { api("/api/legal-consent", JSONObject().put("token", oldSession).put("version", AttaPrivacy.VERSION).put("accepted", false)) }
                                    runCatching { api("/api/logout", JSONObject().put("token", oldSession)) }
                                } }
                                clearLocalSession(); onPrivacyRevoked()
                            }, modifier = Modifier.weight(1f))
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
                                        // The owner assigns individual moderator accounts server-side.
                                        PremiumMenuEntry("shield", "لوحة الإدارة") { navigate("الإدارة") }
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
                                    PremiumMenuEntry("phone", "ظهور المكالمة فوق التطبيقات") {
                                        runCatching { ctx.startActivity(CallWindow.settings(ctx)) }
                                            .onFailure { featureMessage = "افتح إعدادات الهاتف ثم اسمح للعطا بالظهور فوق التطبيقات" }
                                    }
                                    PremiumMenuEntry("bell", "إظهار المكالمات والأذان على شاشة القفل") {
                                        val action = if (android.os.Build.VERSION.SDK_INT >= 34)
                                            "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT" else android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                        runCatching {
                                            val settings = Intent(action).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                            if (android.os.Build.VERSION.SDK_INT >= 34) settings.data = android.net.Uri.parse("package:" + ctx.packageName)
                                            ctx.startActivity(settings)
                                        }.onFailure { featureMessage = "افتح إعدادات إشعارات العطا واسمح بالظهور على شاشة القفل" }
                                    }
                                    PremiumMenuEntry("check", "فحص إشعارات الخلفية") { task {
                                        AttaPush.register(ctx.applicationContext, token, myUsername)
                                        val provider = AttaPush.provider(ctx)
                                        val providerName = when (provider) { "hms" -> "Huawei Push Kit"; "fcm" -> "Firebase"; else -> "خدمة غير متاحة على هذا الهاتف" }
                                        val status = get("/api/push/status?provider=$provider")
                                        val serverOk = status.optBoolean("serverConfigured") && status.optBoolean("projectMatches")
                                        val saved = status.optInt("registeredDevices", 0)
                                        val registration = prefs.getLong("push_registered_at", 0L)
                                        val registeredUser = prefs.getString("push_registered_user", "").orEmpty()
                                        val registeredHere = registration > 0L && registeredUser == myUsername.lowercase()
                                        val permissionOk = android.os.Build.VERSION.SDK_INT < 33 ||
                                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                        val channelOk = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
                                        val callReport = status.optJSONObject("lastCallPush")
                                        val callResult = if (callReport == null) "لا يوجد اختبار مكالمة مسجّل بعد"
                                            else "آخر محاولة مكالمة: ${callReport.optInt("accepted", 0)} مقبولة من مزودي الإشعارات / " +
                                                "${callReport.optInt("attempted", 0)} محاولة" +
                                                (callReport.optString("error").takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                                        featureMessage = "$providerName بالتطبيق: ${if (AttaPush.ready(ctx)) "جاهز" else "غير مفعّل — يحتاج ربط حساب المطور"}\n" +
                                            "خادم Cloudflare: ${if (serverOk) "جاهز" else "يحتاج ضبط/نشر"}\n" +
                                            "الأجهزة المسجلة: $saved\n" +
                                            "تسجيل هذا الهاتف: ${if (registeredHere) "مؤكد محليًا" else "لم يتأكد بعد"}\n" +
                                            "إذن التنبيهات: ${if (permissionOk && channelOk) "مسموح" else "ممنوع"}\n" +
                                            "$callResult\n" +
                                            "قبول مزود الإشعارات لا يعني ضمان العرض على شاشة القفل."
                                    } }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("bell", "إرسال إشعار تجريبي لجهازي") { task {
                                        val result = post("/api/push/test", JSONObject().put("provider", AttaPush.provider(ctx)))
                                        featureMessage = "قبل مزود الإشعارات إرسال ${result.optInt("acceptedByProvider", result.optInt("acceptedByFirebase", 0))} إشعار إلى الجهاز. راقب شريط الإشعارات؛ قد يقيّد الهاتف العمل في الخلفية."
                                    } }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("check", "فحص تحديث التطبيق") { updateCheckRequest++ }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("lock", "تغيير كلمة السر") { changePasswordDialog = true }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("shield", "الخصوصية وطلب حذف الحساب") { navigate("الخصوصية") }
                                    LegalLinks { legalDocument = it }
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
                            if (adminRole in listOf("super_admin", "moderator")) {
                                item { if (adminRole == "super_admin") AdminOwnerHero(displayName, myAvatar, adminSummary.optString("owner").ifBlank { myUsername }) else SectionTitle("لوحة المشرف • متابعة البلاغات والحسابات") }
                                if (adminRole == "super_admin") item { CardBox { AdminAdhanVoicesPanel(token) } }
                                if (adminRole == "super_admin") item { CardBox { AdminFootballPanel(token, owner = myUsername) } }
                                item {
                                    CardBox {
                                        AdminStatisticsPanel(adminSummary)
                                        GoldButton("تحديث الإحصائيات", !busy) { task { load("الإدارة") } }
                                        adminSummary.optJSONObject("deletion")?.takeIf { adminRole == "super_admin" }?.let { deletion ->
                                            Text("حذف حساب قيد الاستكمال: @${deletion.optString("username")}", color = GoldLight)
                                            GoldButton("استكمال حذف الحساب", !busy) {
                                                confirmAccountAction = "delete_user"; confirmAccountUser = deletion.optString("username")
                                                accountDeleteProgress = ""; error = ""
                                            }
                                        }
                                    }
                                }
                                if (adminRole == "super_admin") item {
                                    CardBox { AdminProtectionPanel(adminSummary.optBoolean("securityConfigured"), busy,
                                        runTask = { task(it) }, request = { path, data -> post(path, data) },
                                        protect = { adminPasswordAction = it }, onRefresh = { refresh() }) }
                                }
                                item {
                                    SectionTitle("إدارة الحسابات")
                                    PremiumAuthTextField(adminSearch, { adminSearch = it }, "ابحث بالاسم أو اسم المستخدم")
                                    GoldButton("بحث", !busy) { task { load("الإدارة") } }
                                }
                                items(adminUsers) { u ->
                                    val username = u.optString("username")
                                    CardBox {
                                        Text(u.optString("displayName").ifBlank { username }, color = White, fontWeight = FontWeight.Bold)
                                        Text("@$username", color = Muted, fontSize = 12.sp)
                                        Text(if (u.optString("role") == "moderator") "مشرف" else if (u.optBoolean("banned")) "محظور" else if (u.optBoolean("disabled")) "معطّل" else "مفعّل", color = GoldLight, fontSize = 12.sp)
                                        if (username != myUsername && u.optString("role") != "super_admin" && (adminRole == "super_admin" || u.optString("role") != "moderator")) {
                                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                                if (u.optBoolean("banned") || u.optBoolean("disabled")) TextButton(enabled = !busy, onClick = { task {
                                                    post("/api/admin/action", JSONObject().put("action", "activate").put("username", username))
                                                    load("الإدارة")
                                                } }) { Text("إعادة تفعيل", color = GoldLight) }
                                                else {
                                                    TextButton(onClick = { confirmAccountAction = "suspend"; confirmAccountUser = username; error = "" }) { Text("تعطيل", color = GoldLight) }
                                                    TextButton(onClick = { confirmAccountAction = "ban"; confirmAccountUser = username; error = "" }) { Text("حظر", color = Color(0xFFFF8B8B)) }
                                                }
                                                if (adminRole == "super_admin") TextButton(onClick = {
                                                    confirmAccountAction = "delete_user"; confirmAccountUser = username
                                                    accountDeleteProgress = ""; error = ""
                                                }) { Text("حذف نهائي", color = Color(0xFFFF8B8B)) }
                                                if (adminRole == "super_admin") {
                                                    TextButton(onClick = { adminEditUser = username; adminEditUserName = u.optString("displayName") }) { Text("تعديل الاسم", color = GoldLight) }
                                                    TextButton(onClick = { task {
                                                        post("/api/admin/action", JSONObject().put("action", if (u.optString("role") == "moderator") "remove_moderator" else "make_moderator")
                                                            .put("username", username)); load("الإدارة")
                                                    } }) {
                                                        SiteIcon("user-plus", size = 18.dp)
                                                        Text(if (u.optString("role") == "moderator") " إزالة الإشراف" else " إضافة مشرف", color = GoldLight)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (adminNextOffset >= 0) item {
                                    GoldButton("عرض المزيد من الحسابات", !busy) { task {
                                        val accounts = get("/api/admin/users?q=${enc(adminSearch)}&offset=$adminNextOffset")
                                        adminUsers = (adminUsers + accounts.arr("users")).distinctBy { it.optString("username") }
                                        adminNextOffset = if (accounts.isNull("nextOffset")) -1 else accounts.optInt("nextOffset", -1)
                                    } }
                                }
                                item { SectionTitle("طلبات حذف الحساب") }
                                if (deletionRequests.isEmpty()) item { Text("لا توجد طلبات حذف معلّقة", color = Muted) }
                                items(deletionRequests, key = { "deletion:" + it.optString("id") }) { request ->
                                    val target = request.optString("username")
                                    CardBox {
                                        Text("طلب حذف @$target", color = GoldLight)
                                        Text(request.optString("reason").ifBlank { "بدون سبب إضافي" }, color = White)
                                        TextButton(onClick = { openPublicProfile(target) }) { Text("مراجعة الحساب", color = GoldLight) }
                                        if (adminRole == "super_admin" && target != adminSummary.optString("owner") && target != myUsername) TextButton(onClick = {
                                            confirmAccountAction = "delete_user"; confirmAccountUser = target
                                            accountDeleteProgress = ""
                                        }) { Text("مراجعة وتنفيذ الحذف", color = Color(0xFFFF8B8B)) }
                                        else Text("حساب المدير الرئيسي محمي دائمًا.", color = Muted)
                                    }
                                }
                                item { SectionTitle("بلاغات المحتوى") }
                                if (adminReports.none { !it.optBoolean("resolved") }) item { Text("لا توجد بلاغات معلّقة", color = Muted) }
                                items(adminReports.filter { !it.optBoolean("resolved") }, key = { "report:" + it.optString("id") }) { report ->
                                    CardBox {
                                        Text("${if (report.optString("targetType") == "post") "منشور" else "حساب"} • ${report.optString("targetId")}", color = GoldLight)
                                        Text(report.optString("reason"), color = White)
                                        Text("مقدّم البلاغ: @${report.optString("reporter")}", color = Muted)
                                        val author = report.optString("targetUsername")
                                        if (author.isNotBlank()) TextButton(onClick = { openPublicProfile(author) }) { Text("مراجعة حساب الناشر", color = GoldLight) }
                                        if (adminRole == "super_admin" && report.optString("targetType") == "post") TextButton(onClick = { confirmPostDelete = report.optString("targetId") }) {
                                            Text("حذف المنشور المخالف", color = Color(0xFFFF8B8B))
                                        }
                                        TextButton(enabled = !busy, onClick = { task {
                                            post("/api/admin/action", JSONObject().put("action", "resolve_report").put("reportId", report.optString("id")))
                                            load("الإدارة")
                                        } }) { Text("تمت مراجعة البلاغ", color = GoldLight) }
                                    }
                                }
                                item {
                                    SectionTitle("إدارة المنشورات")
                                    PremiumAuthTextField(adminPostSearch, { adminPostSearch = it }, "ابحث في نص المنشور أو اسم الناشر")
                                    GoldButton("بحث / تحديث المنشورات", !busy) { task { load("الإدارة") } }
                                }
                                items(adminPosts) { p ->
                                    CardBox {
                                        Text("@${p.optString("author")}", color = GoldLight)
                                        Text(p.optString("text"), color = White)
                                        if (adminRole == "super_admin") TextButton(onClick = { adminEditPost = p.optString("id"); adminEditPostText = p.optString("text") }) { Text("تعديل المنشور", color = GoldLight) }
                                        if (adminRole == "super_admin") TextButton(onClick = { confirmPostDelete = p.optString("id") }) {
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
                                    myUsername = myUsername,
                                    contacts = friends,
                                    inbox = inboxPreviews,
                                    hiddenPeers = hiddenChatPeers,
                                    onDeleteConversation = { peer -> task {
                                        val result = post("/api/conversation-delete", JSONObject().put("with", peer))
                                        val removed = result.optJSONArray("removed")
                                        (0 until (removed?.length() ?: 0)).forEach { AttaPush.cancelMessage(ctx, removed!!.optString(it)) }
                                        hiddenChatPeers = hiddenChatPeers + peer.lowercase()
                                        inboxPreviews = inboxPreviews.filterNot { it.username.equals(peer, ignoreCase = true) }
                                        load("الرسائل")
                                    } },
                                    stories = stories,
                                    onAddStory = { showStoryComposer = true },
                                    onStory = { selectedStory = it },
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
                                    onDeleteMessage = { id, deleteScope ->
                                        val peer = chatWith
                                        task {
                                            post("/api/message-delete", JSONObject().put("with", peer).put("messageId", id).put("scope", deleteScope))
                                            AttaPush.cancelMessage(ctx, id)
                                            if (chatWith == peer) messages = get("/api/conversation?with=${enc(peer)}").arr("messages")
                                        }
                                    },
                                    onVoiceCall = { openCall(chatWith, "audio") },
                                    onVideoCall = { openCall(chatWith, "video") },
                                    onBack = { chatWith = ""; activeChatPerson = null; messages = emptyList(); refresh() },
                                    onRefresh = { task { messages = get("/api/conversation?with=${enc(chatWith)}").arr("messages") } }
                                )
                            }
                        }
                        "الإشعارات" -> LazyColumn(Modifier.weight(1f)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item {
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("الإشعارات", color = GoldLight, fontSize = 23.sp,
                                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    val unread = notifications.count { !it.optBoolean("read", false) }
                                    if (unread > 0) Text("$unread جديد", color = GoldLight,
                                        modifier = Modifier.background(Panel, RoundedCornerShape(12.dp))
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        fontSize = 12.sp)
                                }
                            }
                            if (notifications.isEmpty()) item {
                                CardBox {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 22.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        SiteIcon("bell", size = 29.dp, tint = GoldLight)
                                        Spacer(Modifier.width(12.dp))
                                        Text("لا توجد إشعارات حتى الآن", color = Muted, fontSize = 15.sp)
                                    }
                                }
                            }
                            items(notifications, key = { it.optString("id").ifBlank { it.hashCode().toString() } }) { n ->
                                val unread = !n.optBoolean("read", false)
                                Column(Modifier.fillMaxWidth()
                                    .background(if (unread) Color(0xFF171A19) else Panel,
                                        RoundedCornerShape(16.dp))
                                    .border(if (unread) 1.dp else .5.dp,
                                        if (unread) Gold.copy(alpha = .78f) else Border.copy(alpha = .5f),
                                        RoundedCornerShape(16.dp))
                                    .clickable { openNativeNotification(n) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(37.dp).clip(CircleShape)
                                            .background(Gold.copy(alpha = .14f)),
                                            contentAlignment = Alignment.Center) {
                                            SiteIcon("bell", size = 19.dp, tint = GoldLight)
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(n.optString("fromName").ifBlank { n.optString("from") }.ifBlank { "تواصل العطا" },
                                            color = if (unread) GoldLight else White,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f), maxLines = 1)
                                        if (unread) Box(Modifier.size(8.dp)
                                            .background(Gold, CircleShape))
                                    }
                                    val message = when (n.optString("type")) {
                                        "like" -> "أعجب بمنشورك"
                                        "reaction" -> "تفاعل مع منشورك"
                                        "comment" -> "علّق على منشورك"
                                        "comment-reply" -> "رد على تعليقك"
                                        "friend-request" -> "أرسل إليك طلب صداقة"
                                        "friend-accepted" -> "قبل طلب صداقتك"
                                        "follow" -> "بدأ متابعتك"
                                        "share" -> "شارك منشورك"
                                        "new-post" -> "نشر منشورًا جديدًا"
                                        else -> n.optString("text").ifBlank { "لديك إشعار جديد" }
                                    }
                                    Text(message, color = White, fontSize = 14.sp,
                                        modifier = Modifier.padding(start = 47.dp, top = 6.dp))
                                }
                            }
                            if (notifications.any { !it.optBoolean("read", false) }) item {
                                GoldButton("تحديد الكل كمقروء") {
                                    task { post("/api/notifications/read", JSONObject()); load(tab) }
                                }
                            }
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
                                    // Space for the single persistent host above this list.
                                    Spacer(Modifier.fillMaxWidth().height(youtubeDisplayHeight+28.dp))
                                } else {
                                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black), contentAlignment = Alignment.Center) {
                                        if (youtubeSelectedThumbnail.isNotBlank()) {
                                            AsyncImage(youtubeSelectedThumbnail, "صورة الفيديو المحدد",
                                                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        }
                                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)))
                                        GoldButton("▶ تشغيل الفيديو") { activeYoutubePostId = ""; youtubePlayRequested = true }
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
                                        listOf("قرآن", "أحاديث", "الأذكار", "رياضة", "أخبار").forEach { category ->
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
                            if (youtubeSearch.isBlank()) item {
                                val suggestions = when (youtubeCategory) {
                                    "قرآن" -> listOf(
                                        "محمد صديق المنشاوي", "محمود خليل الحصري", "عبدالباسط عبدالصمد",
                                        "مصطفى إسماعيل", "محمد رفعت", "علي جابر", "بدر التركي",
                                        "ماهر المعيقلي", "خالد الجليل")
                                    "أحاديث" -> listOf("سمير مصطفى", "إسحاق الحويني", "مصطفى العدوي",
                                        "محمد حسين يعقوب", "محمد حسان", "عبدالرزاق البدر")
                                    "الأذكار" -> listOf("أذكار الصباح", "أذكار المساء", "أذكار النوم",
                                        "أذكار بعد الصلاة", "حصن المسلم")
                                    "أخبار" -> listOf("الجزيرة مباشر", "العربية أخبار", "الجزيرة نت")
                                    else -> emptyList()
                                }
                                if (suggestions.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text("اقتراحات $youtubeCategory", color = GoldLight, fontSize = 13.sp)
                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            suggestions.forEach { suggestion ->
                                                OutlinedButton(onClick = {
                                                    youtubeSearch = suggestion
                                                    task { load("يوتيوب") }
                                                }, modifier = Modifier.height(34.dp),
                                                    shape = RoundedCornerShape(18.dp),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                    border = BorderStroke(1.dp, Border)) {
                                                    Text(suggestion, color = GoldLight, fontSize = 12.sp)
                                                }
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
                                        activeYoutubePostId = ""
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
                                if (id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                                    TextButton(onClick = {
                                        youtubePlayRequested = false
                                        activeYoutubePostId = ""
                                        youtubePostDraftId = id
                                        youtubePostDraftTitle = v.optString("title").take(160)
                                        // Selecting a video prepares the existing Home composer.
                                        // The Home feed loads via its composable effect after navigation.
                                        openHomeComposer()
                                    }) {
                                        SiteIcon("repeat", size = 19.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text("مشاركة كمنشور في الرئيسية", color = GoldLight)
                                    }
                                }
                            } }
                        }
                        "الملف" -> LazyColumn(Modifier.weight(1f).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item {
                                CardBox {
                                    ProfileTimelineHeader(profile, friends.size,
                                        onAvatar = { if (!busy) profileAvatarPicker.launch("image/*") },
                                        onCover = { if (!busy) profileCoverPicker.launch("image/*") })
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("المنشورات", "حول", "الأصدقاء", "الصور", "الفيديوهات").forEach { section ->
                                            TextButton(onClick = {
                                                ownProfileFilter = section
                                                // Editing belongs to About only, not posts or photos.
                                                if (section != "حول") editProfileDetails = false
                                            }) { Text(section, color = if (ownProfileFilter == section) GoldLight else Muted) }
                                        }
                                    }
                                    if (ownProfileFilter == "الأصدقاء") {
                                        friends.take(12).chunked(3).forEach { row ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                row.forEach { friend -> Column(Modifier.weight(1f).clickable { openPublicProfile(friend.optString("username")) }) {
                                                    ProfileMediaImage(friend.optString("avatar"), friend.name(), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                                                    Text(friend.name(), color = White, fontSize = 12.sp, maxLines = 2)
                                                } }
                                            }
                                        }
                                    }
                                    if (ownProfileFilter == "حول") {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        SectionTitle("التفاصيل")
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = { editProfileDetails = !editProfileDetails }, enabled = !busy) {
                                            SiteIcon(if (editProfileDetails) "x" else "pencil", size = 17.dp, tint = GoldLight)
                                            Spacer(Modifier.width(5.dp))
                                            Text(if (editProfileDetails) "إلغاء" else "تعديل الملف الشخصي",
                                                color = GoldLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
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
                            }
                            if (ownProfileFilter !in listOf("حول", "الأصدقاء")) item { SectionTitle("منشوراتي") }
                            if (ownPosts.isEmpty() && !screenLoading && error.isBlank()) item { Text("لا توجد منشورات ظاهرة حاليًا", color = White) }
                            items(ownPosts.filter { p -> when (ownProfileFilter) {
                                "الصور" -> p.optString("image").isNotBlank()
                                "الفيديوهات" -> p.optString("video").isNotBlank() || youtubePostId(p.optString("text")) != null
                                "حول", "الأصدقاء" -> false
                                else -> true
                            } }, key = { "own_" + it.optString("id", it.hashCode().toString()) }) { post ->
                                CardBox {
                                    Text(profile.name(), color = GoldLight, fontWeight = FontWeight.Bold)
                                    val ownText = visibleYoutubePostText(visibleMushafPostText(post.optString("text"), BASE))
                                    PostTextContent(ownText)
                                    MushafPostActions(post.optString("text")) { pages ->
                                        quranReadingRange = pages; quranPage = pages.first; navigate("القرآن")
                                    }
                                    youtubePostId(post.optString("text"))?.let { videoId ->
                                        val ownId = "own_" + post.optString("id")
                                        YoutubePostMedia(videoId, activeYoutubePostId == ownId,
                                            onPlay = { youtubePlayRequested = false; activeYoutubePostId = ownId },
                                            onStop = { if (activeYoutubePostId == ownId) activeYoutubePostId = "" })
                                    }
                                    val ownMushafPages = parseMushafPostAttachment(post.optString("text"), BASE)?.pages
                                    if (ownMushafPages != null) MushafPostGallery(ownMushafPages, media(post.optString("image")).takeIf { it.isNotBlank() })
                                    if (post.optString("image").isNotBlank() && ownMushafPages == null)
                                        ExpandableProfileMedia(post.optString("image"), "صورة منشوري",
                                            Modifier.fillMaxWidth().heightIn(max = 320.dp), ContentScale.Fit)
                                    if (post.optString("video").isNotBlank()) AttaExpandableVideo(media(post.optString("video")))
                                }
                            }
                            if (ownCursor != null) item {
                                GoldButton(if (loadingMore) "جارٍ التحميل..." else "تحميل منشوراتي الأقدم", !loadingMore && !screenLoading) {
                                    val cursor = ownCursor
                                    val accountToken = token
                                    task {
                                        loadingMore = true
                                        try {
                                            val page = FeedPages.fetch(::get, cursor, myUsername)
                                            if (token == accountToken && tab == "الملف") {
                                                ownPosts = FeedPages.merge(ownPosts, page); ownCursor = page.nextCursor
                                            }
                                        } finally { loadingMore = false }
                                    }
                                }
                            }
                        }
                    }
                    if (tab != "الرسائل" || chatWith.isBlank()) PremiumBottomNav(tab, ::navigate)
                }

            }
            if (!youtubePipMode && profilePeer.isNotBlank() && token.isNotBlank()) {
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
                        onVideoCall = { openCall(profilePeer, "video") },
                        onReport = { kind, id -> reportReason = ""; reportTarget = kind to id }
                    )
                }
            }
            // Keep the existing site WebView at one stable composition location.
            // Only section playback reaches this host; post playback stays inside its card.
            // Only its bounds change between the YouTube page and a visible mini-player.
            if (token.isNotBlank() && youtubePlayRequested && youtubeId.isNotBlank() && activeYoutubePostId.isBlank()) {
                val expandedYoutube = tab == "يوتيوب" && profilePeer.isBlank()
                FloatingVideoContainer(expandedYoutube, youtubePipMode,
                    if (compactYoutubeLayout) 0.dp else 47.dp) { hostModifier, onMiniDrag ->
                    val currentMiniDrag by rememberUpdatedState(onMiniDrag)
                    Column(hostModifier.clip(if (youtubePipMode) RectangleShape else RoundedCornerShape(10.dp)).background(Black)
                        .then(if (youtubePipMode) Modifier else Modifier.border(.7.dp,Border,RoundedCornerShape(10.dp)))) {
                        if (!youtubePipMode) {
                            Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("يوتيوب", color = GoldLight, fontSize = 10.sp,
                                    modifier = Modifier.weight(1f).clickable { navigate("يوتيوب") })
                                if (onMiniDrag != null) Box(Modifier.size(width = 42.dp, height = 28.dp)
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            currentMiniDrag?.invoke(dragAmount.x, dragAmount.y)
                                        }
                                    }, contentAlignment = Alignment.Center) {
                                    Text("⠿", color = GoldLight, fontSize = 18.sp, lineHeight = 20.sp)
                                }
                                IconButton(onClick = { youtubePlayRequested = false; activeYoutubePostId = "" }, modifier = Modifier.size(28.dp)) {
                                    SiteIcon("x", size = 16.dp)
                                }
                            }
                        }
                        YouTubePlayer(youtubeId, onMiniDrag = onMiniDrag)
                    }
                }
            }
            activeCall?.let { session ->
                // Keep the native WebRTC peer and its Compose screen mounted while minimized.
                // The separate native camera/renderer lifecycle owns the call.
                NativeCallScreen(
                    session = session, token = token, myUsername = myUsername,
                    minimized = callMinimized,
                    modifier = if (callMinimized) {
                        // Full-width ongoing-call banner while browsing inside the app.
                        // Keep the native WebRTC peer mounted so media is not interrupted.
                        Modifier.align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(top = 72.dp, start = 10.dp, end = 10.dp)
                            .height(86.dp)
                    } else Modifier.fillMaxSize().safeDrawingPadding(),
                    onMinimize = { callMinimized = true },
                    onRestore = { callMinimized = false },
                    onClose = {
                        callMinimized = false; activeCall = null
                        if (AttaPush.activeCallId == session.callId) AttaPush.activeCallId = ""
                        if (tab == "الرسائل" && token.isNotBlank()) scope.launch {
                            try { load("الرسائل") } catch (_: Exception) { }
                        }
                    }
                )
            }
            }
        }
    }
}


/** Owner-only premium header; permissions come from the server, not local flags. */
@Composable
private fun AdminOwnerHero(accountName: String, avatarUrl: String, username: String) {
    Column(Modifier.fillMaxWidth()
        .background(Brush.linearGradient(listOf(Color(0xFF17150E), Color(0xFF090D10))),
            RoundedCornerShape(20.dp))
        .border(1.dp, Gold.copy(alpha = .68f), RoundedCornerShape(20.dp))
        .padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(62.dp).border(2.dp, GoldLight, CircleShape)
                .clip(CircleShape), contentAlignment = Alignment.Center) {
                if (avatarUrl.isNotBlank())
                    ProfileMediaImage(avatarUrl, "صورة المدير", Modifier.fillMaxSize())
                else SiteIcon("shield", size = 36.dp, tint = GoldLight)
            }
            Column(Modifier.weight(1f)) {
                Text("لوحة القيادة", color = GoldLight,
                    fontWeight = FontWeight.ExtraBold, fontSize = 23.sp)
                Text("تواصل العطا • الإدارة الرئيسية",
                    color = Color(0xFFD6C8A4), fontSize = 12.sp)
            }
        }
        HorizontalDivider(color = Gold.copy(alpha = .28f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(accountName.ifBlank { username }, color = White,
                    fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@$username", color = Muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.background(Gold.copy(alpha = .14f), RoundedCornerShape(12.dp))
                .border(1.dp, Gold.copy(alpha = .48f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("👑 المدير الرئيسي", color = GoldLight,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("وصول معتمد", color = Color(0xFFDBCBA9), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun AdminStatisticsPanel(summary: JSONObject) {
    val counts = summary.optJSONObject("counts") ?: JSONObject()
    val metrics = listOf(
        "المستخدمون" to "users", "المنشورات" to "posts",
        "مستخدمون جدد اليوم" to "usersToday", "منشورات اليوم" to "postsToday",
        "جدد خلال ٧ أيام" to "users7Days", "منشورات خلال ٧ أيام" to "posts7Days",
        "حسابات مفعّلة" to "active", "متصلون الآن" to "online",
        "حسابات محظورة" to "banned", "حسابات معطّلة" to "disabled",
        "بلاغات مفتوحة" to "reports", "منشورات مؤرشفة" to "archivedPosts"
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SiteIcon("chart", size = 21.dp, tint = GoldLight)
        Spacer(Modifier.width(8.dp))
        Text("نظرة عامة على المنصة", color = GoldLight, fontSize = 18.sp,
            fontWeight = FontWeight.Bold)
    }
    metrics.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            pair.forEach { (label, key) ->
                Column(Modifier.weight(1f).padding(vertical = 4.dp)
                    .background(Color(0xFF11171A), RoundedCornerShape(14.dp))
                    .border(.8.dp, Gold.copy(alpha = .25f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(counts.optInt(key).toString(), color = GoldLight,
                        fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                    Text(label, color = Color(0xFFBFC4C6), fontSize = 12.sp, maxLines = 2)
                }
            }
        }
    }
    Text("آخر ٧ أيام • بتوقيت الرياض", color = GoldLight, fontSize = 13.sp)
    Text("ذهبي: مستخدمون جدد   •   أزرق: منشورات", color = Muted, fontSize = 11.sp)
    val daily = summary.arr("daily")
    val maximum = daily.maxOfOrNull { maxOf(it.optInt("users"), it.optInt("posts")) }?.coerceAtLeast(1) ?: 1
    daily.forEach { day ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(day.optString("date").takeLast(5), color = Muted, fontSize = 11.sp, modifier = Modifier.width(46.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF242A2D), RoundedCornerShape(4.dp))) {
                    if (day.optInt("users") > 0) Box(Modifier.fillMaxWidth(day.optInt("users").toFloat() / maximum).height(6.dp).background(GoldLight, RoundedCornerShape(4.dp)))
                }
                Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF242A2D), RoundedCornerShape(4.dp))) {
                    if (day.optInt("posts") > 0) Box(Modifier.fillMaxWidth(day.optInt("posts").toFloat() / maximum).height(6.dp).background(Color(0xFF6DB8E7), RoundedCornerShape(4.dp)))
                }
            }
            Text("${day.optInt("users")} / ${day.optInt("posts")}", color = White, fontSize = 11.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.Center)
        }
    }
}
