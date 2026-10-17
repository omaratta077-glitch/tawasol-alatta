package com.alatta.nativeapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
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

/**
 * Foreground incoming-call alert. It uses the user's ringtone and respects the
 * ringer switch. A single controller is attached only while the incoming-call
 * dialog exists; accepting, rejecting or leaving the Activity stops everything.
 */
private class IncomingCallAlert(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var started = false

    @Suppress("DEPRECATION")
    fun start() {
        if (started) return
        started = true
        val mode = audio.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(context, uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone?.isLooping = true
                ringtone?.play()
            } catch (_: Exception) { /* Silenced/missing ringtone: call dialog still works. */ }
        }
        val vibrate = mode == AudioManager.RINGER_MODE_VIBRATE ||
            (mode == AudioManager.RINGER_MODE_NORMAL && audio.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER))
        if (vibrate) {
            try {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                if (vibrator?.hasVibrator() == true) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 1000, 700, 2400), 0))
                }
            } catch (_: Exception) { /* Some devices restrict vibration. */ }
        }
    }
    fun stop() {
        started = false
        try { ringtone?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
        ringtone = null
        vibrator = null
    }
}

/** Only an actively displayed incoming invitation is allowed to ring. */
@Composable
internal fun IncomingCallRingtone(callId: String) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(context, lifecycleOwner, callId) {
        AttaPush.foregroundRingCallId = callId
        val alert = IncomingCallAlert(context.applicationContext)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> alert.start()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> alert.stop()
                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        if (lifecycleOwner == null || lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            alert.start()
        }
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            alert.stop()
            if (AttaPush.foregroundRingCallId == callId) AttaPush.foregroundRingCallId = ""
        }
    }
}

/** The caller hears a ringback ONLY after the recipient acknowledged ringing. */
private class OutgoingRingback {
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var running = false
    private val repeat = object : Runnable {
        override fun run() {
            if (!running) return
            try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 180) } catch (_: Exception) {}
            handler.postDelayed({
                if (running) try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 180) } catch (_: Exception) {}
            }, 370)
            handler.postDelayed(this, 3200)
        }
    }
    fun start() {
        if (running) return
        try {
            // Caller-side audible ringback uses media volume, not the recipient's
            // ringtone stream. STREAM_VOICE_CALL can be silent before audio routing
            // is established on some Android devices.
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 78)
            running = true
            handler.post(repeat)
        } catch (_: Exception) { stop() }
    }
    fun stop() {
        running = false
        handler.removeCallbacks(repeat)
        try { tone?.stopTone() } catch (_: Exception) {}
        try { tone?.release() } catch (_: Exception) {}
        tone = null
    }
}

/** Call signalling keeps the private username; only display names go on screen. */
internal data class NativeCallSession(
    val peer: String,
    val type: String,
    val callId: String,
    val incoming: Boolean
)

private fun nativeActivityLabel(u: JSONObject): String {
    if (u.optBoolean("isOnline", false)) return "نشط الآن"
    val last = u.optLong("lastSeen", 0L)
    if (last <= 0L) return "غير متصل"
    val minutes = ((System.currentTimeMillis() - last).coerceAtLeast(0L) / 60000L)
    return when {
        minutes < 1L -> "نشط منذ لحظات"
        minutes < 60L -> "نشط منذ $minutes دقيقة"
        minutes < 1440L -> "نشط منذ ${minutes / 60L} ساعة"
        else -> "نشط منذ ${minutes / 1440L} يوم"
    }
}

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
    minimized: Boolean = false,
    modifier: Modifier = Modifier.fillMaxSize(),
    onMinimize: () -> Unit = {},
    onRestore: () -> Unit = {},
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
    var callStatus by remember(session.callId) { mutableStateOf("جارٍ الاتصال...") }
    var connectedAt by remember(session.callId) { mutableLongStateOf(0L) }
    var elapsed by remember(session.callId) { mutableIntStateOf(0) }
    var muted by remember(session.callId) { mutableStateOf(false) }
    var cameraOff by remember(session.callId) { mutableStateOf(false) }
    var speakerOn by remember(session.callId) { mutableStateOf(videoCall) }
    var remoteVideoPlaying by remember(session.callId) { mutableStateOf(false) }
    var localCameraReady by remember(session.callId) { mutableStateOf(false) }
    var videoHint by remember(session.callId) { mutableStateOf("") }
    var videoNeedsGesture by remember(session.callId) { mutableStateOf(false) }
    var peerActivity by remember(session.peer) { mutableStateOf("") }
    var hangingUp by remember(session.callId) { mutableStateOf(false) }

    // Do not play ringback during call setup. The remote device must explicitly
    // acknowledge showing its incoming-call UI before the caller hears ringing.
    DisposableEffect(session.callId, session.incoming, callStatus, connectedAt, hangingUp) {
        val ringback = if (!session.incoming && !hangingUp && connectedAt == 0L &&
            callStatus == "يرن الآن...") OutgoingRingback().also { it.start() } else null
        onDispose { ringback?.stop() }
    }

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
            peerActivity = nativeActivityLabel(user)
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
            // Let Android WebView choose the compositor for HTML5 video. Forcing a
            // hardware layer here can render a black video surface on some phones.
            keepScreenOn = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(object {
                @JavascriptInterface
                fun close() { activity?.runOnUiThread { onClose() } }

                // Use the WebView's actual on-screen controls for video calls.
                // Compose overlays over video surfaces may render a black frame on
                // certain Android/HONOR WebView implementations.
                @JavascriptInterface
                fun minimizeCall() { activity?.runOnUiThread { onMinimize() } }

                @JavascriptInterface
                fun onStatus(message: String) {
                    activity?.runOnUiThread {
                        callStatus = when (message) {
                            "متصل الآن", "مكالمة جارية" -> "متصل"
                            "يرن الآن..." -> "يرن الآن..."
                            else -> message.take(150)
                        }
                        if ((message == "متصل الآن" || message == "مكالمة جارية") && connectedAt == 0L) {
                            connectedAt = System.currentTimeMillis()
                        }
                    }
                }

                @JavascriptInterface
                fun mediaPlaybackBlocked() {
                    activity?.runOnUiThread { videoNeedsGesture = true }
                }

                @JavascriptInterface
                fun mediaPlaybackReady() {
                    activity?.runOnUiThread { videoNeedsGesture = false }
                }

                @JavascriptInterface
                fun cameraReady() {
                    activity?.runOnUiThread { localCameraReady = true }
                }

                @JavascriptInterface
                fun remoteVideoPlaying() {
                    activity?.runOnUiThread {
                        remoteVideoPlaying = true
                        videoHint = ""
                    }
                }

                @JavascriptInterface
                fun videoDiagnostic(message: String) {
                    // A short media-only hint, never user ids, tokens, or SDP.
                    activity?.runOnUiThread { videoHint = message.take(180) }
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
                        if (trusted && permissions.isNotEmpty() && permissions.size == request.resources.size) request.grant(permissions.toTypedArray())
                        else {
                            request.deny()
                            if (trusted) {
                                callStatus = "إذن الكاميرا أو الميكروفون مرفوض. افتح إعدادات التطبيق واسمح باستخدامهما."
                                videoHint = "التحقق من الأذونات: الكاميرا والميكروفون لازم يكونوا مسموحين في إعدادات HONOR."
                            } else {
                                videoHint = "رفض طلب الوسائط من مصدر غير موثوق. تأكد من تحديث Android System WebView."
                            }
                        }
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        activity?.runOnUiThread {
                            callStatus = "تعذر تشغيل محرك الاتصال. حاول مرة أخرى."
                        }
                    }
                }
            }
        }
    }
    // Initialize after the WebView is attached to Compose, not during remember().
    var pageLoaded by remember(webView) { mutableStateOf(false) }
    val loadCallPage: (WebView) -> Unit = { view ->
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
    fun nativeAction(script: String) {
        webView.evaluateJavascript(script, null)
    }
    fun endCall() {
        hangingUp = true  // Stop ringback before waiting for signalling to finish.
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
    BackHandler(enabled = !minimized) { onMinimize() }

    Box(modifier.clip(if (minimized) RoundedCornerShape(15.dp) else RoundedCornerShape(0.dp))
        .background(CallBackground)) {
        // Mount a SINGLE WebView for this call for its full lifetime. Moving a video
        // WebView between different AndroidView nodes on minimize/restore can detach
        // Android's video compositing surface and leave both camera images black.
        // Compose controls are transparent over the live video, not covering it.
        AndroidView(
            factory = { webView },
            modifier = if (videoCall) Modifier.fillMaxSize()
                else Modifier.fillMaxSize().alpha(.01f),
            update = loadCallPage
        )

        if (minimized) {
            Row(
                Modifier.fillMaxSize().border(1.dp, CallGold.copy(alpha = .65f), RoundedCornerShape(15.dp))
                    .background(CallPanel, RoundedCornerShape(15.dp))
                    .clickable { onRestore() }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SiteIcon(if (videoCall) "video" else "phone", tint = CallGold, size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(peerDisplayName, color = CallWhite, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (connectedAt > 0) "مكالمة جارية — عودة" else callStatus,
                        color = CallGold, fontSize = 10.sp, maxLines = 2)
                }
                Box(
                    Modifier.size(37.dp).clip(CircleShape)
                        .background(Color(0xFFE83838)).clickable { endCall() },
                    contentAlignment = Alignment.Center
                ) { SiteIcon("phone-off", tint = CallWhite, size = 20.dp) }
            }
        // Keep native Compose controls for AUDIO calls only. VIDEO calls now
        // render their full header, small local preview, remote video and controls
        // directly inside the same stable WebView, with no full-screen Compose
        // layer painted above the video surface. This is the same layout approach
        // as the working website and avoids a black WebView video compositor.
        } else if (!videoCall) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(CallPanel)
                        .clickable { onMinimize() }, contentAlignment = Alignment.Center
                ) { SiteIcon("chevron-left", tint = CallWhite, size = 23.dp) }
                Text(if (videoCall) "مكالمة فيديو" else "مكالمة صوتية",
                    fontSize = 14.sp, color = CallGold, fontWeight = FontWeight.Medium)
                Spacer(Modifier.size(44.dp))
            }

            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (videoCall) {
                    // The sole video WebView is already behind this transparent
                    // stage. Do not add a second AndroidView or cover camera frames.
                    // The compact status appears at bottom-right under the video tile.
                    if (!remoteVideoPlaying) {
                        Column(
                            Modifier.align(Alignment.BottomStart)
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .background(Color(0xD911161A), RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(peerDisplayName, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = CallWhite, maxLines = 1, textAlign = TextAlign.Right)
                            Text(callStatus, color = CallMuted, fontSize = 12.sp, maxLines = 2,
                                textAlign = TextAlign.Right)
                            if (localCameraReady) Text("معاينة كاميرتك شغالة — ننتظر صورة الطرف الآخر",
                                color = CallGold, fontSize = 11.sp, maxLines = 2,
                                textAlign = TextAlign.Right)
                            if (videoHint.isNotBlank()) Text(videoHint,
                                color = Color(0xFFFFD28A), fontSize = 11.sp, maxLines = 3,
                                textAlign = TextAlign.Right)
                        }
                    } else {
                        Text(peerDisplayName,
                            modifier = Modifier.align(Alignment.TopStart)
                                .padding(top = 12.dp, start = 18.dp)
                                .background(Color(0x9905080B), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = CallWhite, fontSize = 15.sp, maxLines = 1)
                    }
                } else {
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
                        if (connectedAt == 0L && peerActivity.isNotBlank()) {
                            Text(peerActivity, fontSize = 12.sp, color = CallGold,
                                modifier = Modifier.padding(top = 5.dp))
                        }
                        if (videoCall && localCameraReady) {
                            Text("تم تفعيل الكاميرا — انتظار فيديو الطرف الآخر", color = CallGold,
                                fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                        }
                        if (connectedAt > 0) {
                            Spacer(Modifier.height(9.dp))
                            Text("%02d:%02d".format(elapsed / 60, elapsed % 60),
                                color = CallGold, fontSize = 17.sp)
                        }
                    }
                }
            }
            if (videoCall && (connectedAt > 0L || videoNeedsGesture)) {
                TextButton(onClick = {
                    nativeAction("window.__attaResumeMedia && window.__attaResumeMedia()")
                    videoNeedsGesture = false
                }) { Text(if (videoNeedsGesture) "اضغط لتشغيل الفيديو والصوت" else "عرض الفيديو بطريقة بديلة",
                    color = CallGold, fontSize = 13.sp) }
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
