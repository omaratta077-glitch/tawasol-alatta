package com.alatta.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import android.widget.VideoView
import android.widget.MediaController
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val BASE = "https://tawasol-alatta.omaratta077-4fd.workers.dev"
private val Gold = Color(0xFFD8AD51)
private val Black = Color(0xFF101010)
private val Panel = Color(0xFF1C1C1C)

private fun request(path: String, body: JSONObject? = null): JSONObject {
    val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
        requestMethod = if (body == null) "GET" else "POST"
        connectTimeout = 15000
        readTimeout = 15000
        setRequestProperty("Accept", "application/json")
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
    }
    return try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val result = JSONObject(stream?.bufferedReader()?.use { it.readText() } ?: "{}")
        if (code !in 200..299) throw Exception(result.optString("error", "HTTP $code"))
        result
    } finally { connection.disconnect() }
}

private data class Post(val author: String, val text: String, val image: String, val video: String)

private fun mediaUrl(value: String): String = when {
    value.startsWith("/media/") -> BASE + value
    value.startsWith("https://") -> value
    value.startsWith("data:image/") -> value
    else -> ""
}

@Composable
private fun PostMedia(post: Post) {
    val image = mediaUrl(post.image)
    val video = mediaUrl(post.video)
    if (image.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        AsyncImage(
            model = image,
            contentDescription = "صورة المنشور",
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp),
            contentScale = ContentScale.Fit
        )
    }
    if (video.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setMediaController(MediaController(context))
                    setVideoPath(video)
                    seekTo(100)
                }
            },
            update = { view -> if (view.tag != video) {
                view.tag = video
                view.setVideoPath(video)
                view.seekTo(100)
            } },
            modifier = Modifier.fillMaxWidth().height(240.dp)
        )
    } else if (post.video.isNotBlank()) {
        Text("هذا الفيديو بصيغة قديمة غير مدعومة حاليًا", color = Color.LightGray)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AlAttaApp() }
    }
}

@Composable
private fun AlAttaApp() {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var posts by remember { mutableStateOf(emptyList<Post>()) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (token.isBlank()) return
        scope.launch {
            busy = true; error = ""
            try {
                val result = withContext(Dispatchers.IO) {
                    request("/api/posts?token=${java.net.URLEncoder.encode(token, "UTF-8")}&limit=20")
                }
                val arr = result.optJSONArray("posts")
                posts = (0 until (arr?.length() ?: 0)).map { i ->
                    val obj = arr!!.optJSONObject(i) ?: JSONObject()
                    Post(obj.optJSONObject("authorInfo")?.optString("displayName")
                        ?.takeIf { it.isNotBlank() } ?: obj.optString("author", "مستخدم"), obj.optString("text"), obj.optString("image"), obj.optString("video"))
                }
            } catch (e: Exception) { error = e.message ?: "تعذر تحميل المنشورات" }
            finally { busy = false }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Black, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
            Column(Modifier.fillMaxSize().background(Black).padding(16.dp)) {
                Text("تواصل العطا", color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text("AL-ATTA • Android Native", color = Color.LightGray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                if (token.isBlank()) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("اسم المستخدم أو البريد أو الهاتف") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("كلمة المرور") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            busy = true; error = ""
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    request("/api/login", JSONObject().put("username", username.trim()).put("password", password))
                                }
                                token = result.getString("token")
                                displayName = result.optJSONObject("user")?.optString("fullName")?.takeIf { it.isNotBlank() } ?: username
                                password = ""
                                refresh()
                            } catch (e: Exception) { error = e.message ?: "تعذر تسجيل الدخول" }
                            finally { busy = false }
                        }
                    }, enabled = !busy && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("تسجيل الدخول") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("مرحبًا، $displayName", color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { token = ""; posts = emptyList(); error = "" }) { Text("خروج") }
                    }
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("بم تفكر؟") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        scope.launch {
                            busy = true; error = ""
                            try {
                                withContext(Dispatchers.IO) { request("/api/posts", JSONObject().put("token", token).put("text", draft)) }
                                draft = ""; refresh()
                            } catch (e: Exception) { error = e.message ?: "تعذر النشر" }
                            finally { busy = false }
                        }
                    }, enabled = !busy && draft.isNotBlank()) { Text("نشر") }
                    TextButton(onClick = { refresh() }, enabled = !busy) { Text("تحديث المنشورات") }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(posts) { post ->
                            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(16.dp)) {
                                Text(post.author, color = Gold, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                if (post.text.isNotBlank()) Text(post.text, color = Color.White)
                                PostMedia(post)
                            }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF7777))
            }
        }
    }
}
