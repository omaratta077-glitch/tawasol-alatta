package com.alatta.nativeapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Call signalling keeps the private username; only display names go on screen. */
internal data class NativeCallSession(
    val peer: String,
    val type: String,
    val callId: String,
    val incoming: Boolean
)

private val CallGold = Color(0xFFE6B94E)
private val CallBackground = Color(0xFF050709)
private val CallPanel = Color(0xFF1A1F23)
private val CallWhite = Color(0xFFF7F7F7)
private val CallMuted = Color(0xFFBEC3C6)

@Composable
internal fun NativeCallScreen(
    session: NativeCallSession,
    token: String,
    myUsername: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val oldMode = remember(audioManager) { audioManager.mode }
    @Suppress("DEPRECATION")
    val oldSpeaker = remember(audioManager) { audioManager.isSpeakerphoneOn }
    val videoCall = session.type == "video"

    var peerDisplayName by remember(session.peer) { mutableStateOf("جهة الاتصال") }
    var peerAvatar by remember(session.peer) { mutableStateOf("") }
    var callStatus by remember(session.callId) { mutableStateOf("جاري تجهيز الاتصال...") }
    var connectedAt by remember(session.callId) { mutableLongStateOf(0L) }
    var elapsed by remember(session.callId) { mutableIntStateOf(0) }
    var muted by remember(session.callId) { mutableStateOf(false) }
    var cameraOff by remember(session.callId) { mutableStateOf(false) }
    var speakerOn by remember(session.callId) { mutableStateOf(videoCall) }
    var remoteVideoPlaying by remember(session.callId) { mutableStateOf(false) }

    // Load the real display name and avatar independently of the media engine.
    LaunchedEffect(session.peer, token) {
        try {
            val info = withContext(Dispatchers.IO) {
                val base = "https://tawasol-alatta.omaratta077-4fd.workers.dev"
                val url = URL("$base/api/profile-public?username=${URLEncoder.encode(session.peer, "UTF-8")}&token=${URLEncoder.encode(token, "UTF-8")}")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 7000
                    conn.readTimeout = 7000
                    conn.setRequestProperty("Accept", "application/json")
                    if (conn.responseCode !in 200..299) JSONObject()
                    else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                } finally { conn.disconnect() }
            }
            val user = info.optJSONObject("user") ?: info
            val name = listOf("fullName", "displayName", "name")
                .map { user.optString(it).trim() }.firstOrNull { it.isNotEmpty() && it != "null" }
            if (!name.isNullOrBlank()) peerDisplayName = name
            peerAvatar = user.optString("avatar").takeUnless { it == "null" }.orEmpty()
        } catch (_: Exception) {
            // Never expose a private signalling ID as the visible name.
        }
    }
    LaunchedEffect(connectedAt) {
        while (connectedAt > 0L) {
            elapsed = ((System.currentTimeMillis() - connectedAt) / 1000L).toInt().coerceAtLeast(0)
            delay(1000)
        }
    }

    // WebRTC stays inside the existing trusted page; the visible interface is native
    // Compose and can never disappear if WebView fails to paint its HTML.
    val webView = remember(session.callId, token) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(5, 7, 9))
            keepScreenOn = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(object {
                @JavascriptInterface
                fun close() { activity?.runOnUiThread { onClose() } }

                @JavascriptInterface
                fun onStatus(message: String) {
                    activity?.runOnUiThread {
                        callStatus = message.take(150)
                        if ((message == "متصل الآن" || message == "مكالمة جارية") && connectedAt == 0L) {
                            connectedAt = System.currentTimeMillis()
                        }
                    }
                }

                @JavascriptInterface
                fun remoteVideoPlaying() {
                    activity?.runOnUiThread { remoteVideoPlaying = true }
                }

                @JavascriptInterface
                fun setSpeaker(enabled: Boolean) {
                    activity?.runOnUiThread {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = enabled
                        speakerOn = enabled
                    }
                }

                @JavascriptInterface
                fun mediaReady() {
                    activity?.runOnUiThread {
                        // Never claim the mic is ready until WebRTC says capture succeeded.
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = videoCall
                    }
                }
            }, "AttaBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    activity?.runOnUiThread {
                        val trusted = request.origin.scheme == "https" &&
                            request.origin.host == "tawasol-alatta.omaratta077-4fd.workers.dev"
                        val permissions = request.resources.filter { resource ->
                            (resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ||
                            (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE && videoCall &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                        }
                        if (trusted && permissions.isNotEmpty()) request.grant(permissions.toTypedArray())
                        else request.deny()
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        callStatus = "تعذر تشغيل محرك الاتصال. حاول مرة أخرى."
                    }
                }
            }
        }
    }
    // Initialize after the WebView is attached to Compose, not during remember().
    var pageLoaded by remember(webView) { mutableStateOf(false) }
    fun nativeAction(script: String) {
        webView.evaluateJavascript(script, null)
    }
    fun endCall() {
        nativeAction("window.__attaEndCall && window.__attaEndCall()")
        // The media layer may have failed to start: allow the native hangup to exit.
        Handler(Looper.getMainLooper()).postDelayed({ onClose() }, 1700L)
    }
    DisposableEffect(webView) {
        onDispose {
            runCatching { webView.loadUrl("about:blank") }
            runCatching { webView.removeJavascriptInterface("AttaBridge") }
            runCatching { webView.destroy() }
            audioManager.mode = oldMode
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = oldSpeaker
        }
    }
    BackHandler { endCall() }

    Box(Modifier.fillMaxSize().background(CallBackground)) {
        // For voice, WebRTC runs in a tiny attached view. For video, the same
        // view occupies the stage; its remote stream becomes visible on play.
        AndroidView(
            factory = { webView },
            modifier = if (videoCall) Modifier.fillMaxWidth().fillMaxHeight(.78f)
                .align(Alignment.Center).alpha(if (remoteVideoPlaying) 1f else .01f)
            else Modifier.fillMaxSize().alpha(.01f),
            update = { view ->
                if (!pageLoaded) {
                    pageLoaded = true
                    val cfg = JSONObject().put("peer", session.peer).put("type", session.type)
                        .put("callId", session.callId).put("incoming", session.incoming)
                        .put("token", token).put("me", myUsername)
                    try {
                        val html = context.assets.open("atta-call.html").bufferedReader().use { it.readText() }
                            .replace("__CONFIG_JSON__", cfg.toString().replace("</", "<\\/"))
                        view.post {
                            view.loadDataWithBaseURL(
                                "https://tawasol-alatta.omaratta077-4fd.workers.dev/",
                                html, "text/html", "UTF-8", null
                            )
                        }
                    } catch (_: Exception) {
                        callStatus = "تعذر تحميل محرك المكالمة. حاول إعادة الاتصال."
                    }
                }
            }
        )

        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(CallPanel)
                        .clickable { endCall() }, contentAlignment = Alignment.Center
                ) { SiteIcon("chevron-left", tint = CallWhite, size = 23.dp) }
                Text(if (videoCall) "مكالمة فيديو" else "مكالمة صوتية",
                    fontSize = 14.sp, color = CallGold, fontWeight = FontWeight.Medium)
                Spacer(Modifier.size(44.dp))
            }

            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (!videoCall || !remoteVideoPlaying) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Box(
                            Modifier.size(180.dp)
                                .border(3.dp, CallGold, CircleShape)
                                .background(Brush.radialGradient(listOf(Color(0xFF423016), CallBackground)), CircleShape)
                                .padding(6.dp).clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (peerAvatar.isNotBlank()) {
                                ProfileMediaImage(peerAvatar, "صورة المتصل", Modifier.fillMaxSize(), ContentScale.Crop)
                            } else {
                                SiteIcon("user", tint = CallGold, size = 82.dp)
                            }
                        }
                        Spacer(Modifier.height(25.dp))
                        Text(peerDisplayName, fontWeight = FontWeight.Bold, fontSize = 29.sp,
                            color = CallWhite, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(11.dp))
                        Text(callStatus, fontSize = 16.sp, color = CallMuted, maxLines = 3,
                            lineHeight = 23.sp)
                        if (connectedAt > 0) {
                            Spacer(Modifier.height(9.dp))
                            Text("%02d:%02d".format(elapsed / 60, elapsed % 60),
                                color = CallGold, fontSize = 17.sp)
                        }
                    }
                } else {
                    // Video occupies the middle of the screen. Controls stay native.
                    Text(peerDisplayName, modifier = Modifier.align(Alignment.TopCenter)
                        .padding(top = 12.dp).background(Color(0x9905080B), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = CallWhite, fontSize = 19.sp, maxLines = 1)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 21.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallControl(if (muted) "mic-off" else "mic", "كتم الصوت", active = muted) {
                    muted = !muted
                    nativeAction("toggleMute()")
                }
                if (videoCall) {
                    CallControl(if (cameraOff) "camera-off" else "video", "الكاميرا",
                        active = cameraOff) {
                        cameraOff = !cameraOff
                        nativeAction("toggleCamera()")
                    }
                }
                CallControl("volume", "السماعة", active = speakerOn) {
                    speakerOn = !speakerOn
                    nativeAction("toggleSpeaker()")
                }
                CallControl("phone-off", "إنهاء", danger = true) { endCall() }
            }
        }
    }
}

@Composable
private fun CallControl(
    icon: String,
    label: String,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            modifier = Modifier.size(68.dp).clip(CircleShape)
                .background(if (danger) Color(0xFFE83838) else if (active) Color(0xFF665029) else CallPanel)
                .border(BorderStroke(1.dp, if (danger) Color(0xFFFF6666) else CallGold.copy(alpha = .26f)), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { SiteIcon(icon, tint = CallWhite, size = 30.dp) }
        Text(label, color = CallWhite, fontSize = 11.sp, maxLines = 1)
    }
}
