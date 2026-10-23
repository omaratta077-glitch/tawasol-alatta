package com.alatta.nativeapp

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private class ForegroundCallAlertFallback(private val context: Context) {
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
                ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone?.isLooping = true
                ringtone?.play()
            } catch (_: Exception) {}
        }
        val vibrate = mode == AudioManager.RINGER_MODE_VIBRATE ||
            (mode == AudioManager.RINGER_MODE_NORMAL && audio.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER))
        if (vibrate) try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vibrator?.hasVibrator() == true)
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 1000, 700, 2400), 0))
        } catch (_: Exception) {}
    }
    fun stop() {
        started = false
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        ringtone = null
        vibrator = null
    }
}

/** Keep one OS notification/ringtone across lock screen, launcher and call dialog. */
@Composable
internal fun IncomingCallRingtone(call: PushCall) {
    val context = LocalContext.current
    val callId = call.callId
    DisposableEffect(context, callId) {
        AttaPush.foregroundRingCallId = callId
        // Only use local audio if Android notifications cannot produce an alert.
        // It never runs alongside an OS-owned call notification.
        val fallback = if (AttaPush.canShowCallNotification(context)) null else ForegroundCallAlertFallback(context.applicationContext)
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> fallback?.start()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> fallback?.stop()
                else -> Unit
            }
        }
        if (fallback == null) AttaPush.incoming(context, call, foreground = true)
        else {
            lifecycle?.addObserver(observer)
            if (lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) fallback.start()
        }
        onDispose {
            lifecycle?.removeObserver(observer)
            fallback?.stop()
            AttaPush.dismiss(context, callId)
            if (AttaPush.foregroundRingCallId == callId) AttaPush.foregroundRingCallId = ""
        }
    }
}

private class OutgoingRingback {
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var playing = false
    private val pulse = object : Runnable {
        override fun run() {
            if (!playing) return
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 180) }
            handler.postDelayed(this, 3100L)
        }
    }
    fun start() {
        if (playing) return
        runCatching {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 78)
            playing = true
            handler.post(pulse)
        }
    }
    fun stop() {
        playing = false
        handler.removeCallbacks(pulse)
        runCatching { tone?.stopTone() }
        runCatching { tone?.release() }
        tone = null
    }
}

/** The same call identity and signaling format used by the website. */
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
    minimized: Boolean = false,
    modifier: Modifier = Modifier.fillMaxSize(),
    onMinimize: () -> Unit = {},
    onRestore: () -> Unit = {},
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val latestClose by rememberUpdatedState(onClose)
    val videoCall = session.type == "video"
    var callStatus by remember(session.callId) { mutableStateOf("جارٍ بدء المكالمة...") }
    var peerDisplayName by remember(session.peer) { mutableStateOf("جهة الاتصال") }
    var peerAvatar by remember(session.peer) { mutableStateOf("") }
    LaunchedEffect(session.peer, token) {
        try {
            val result = withContext(Dispatchers.IO) {
                val addr = "https://tawasol-alatta.omaratta077-4fd.workers.dev/api/profile-public?username=${URLEncoder.encode(session.peer, "UTF-8")}&token=${URLEncoder.encode(token, "UTF-8")}"
                val conn = URL(addr).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 7000
                    conn.readTimeout = 7000
                    if (conn.responseCode in 200..299) JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    else JSONObject()
                } finally { conn.disconnect() }
            }
            val user = result.optJSONObject("user") ?: result
            val name = listOf("fullName", "displayName", "name")
                .map { user.optString(it).trim() }
                .firstOrNull { it.isNotBlank() && it != "null" }
            if (!name.isNullOrBlank()) peerDisplayName = name
            peerAvatar = user.optString("avatar")
        } catch (_: Exception) { /* Keep the friendly fallback, never reveal an internal username. */ }
    }
    var localReady by remember(session.callId) { mutableStateOf(false) }
    var remoteReady by remember(session.callId) { mutableStateOf(false) }
    var connectedAt by remember(session.callId) { mutableLongStateOf(0L) }
    var elapsed by remember(session.callId) { mutableIntStateOf(0) }
    var muted by remember(session.callId) { mutableStateOf(false) }
    var cameraOff by remember(session.callId) { mutableStateOf(false) }
    var speakerOn by remember(session.callId) { mutableStateOf(videoCall) }
    var hangingUp by remember(session.callId) { mutableStateOf(false) }
    // Both caller and callee hide the central video labels as soon as media
    // connects; a received frame also covers devices missing CONNECTED callbacks.
    val videoEstablished = connectedAt > 0L || remoteReady

    // A single native PeerConnectionFactory, native camera capture, and native
    // SurfaceViewRenderers are owned by this session. The old call HTML and
    // its Canvas/WebView overlays have been removed, not duplicated.
    val engine = remember(session.callId, token) {
        NativeRtcCall(
            context.applicationContext, session, token, myUsername,
            report = { callStatus = it },
            localFrame = { localReady = true },
            remoteFrame = { remoteReady = true },
            connected = { if (connectedAt == 0L) connectedAt = System.currentTimeMillis() },
            finished = { latestClose() }
        )
    }

    val localRenderer = remember(engine) { SurfaceViewRenderer(context) }
    val remoteRenderer = remember(engine) { SurfaceViewRenderer(context) }
    // SurfaceViewRenderer has its own EGL surface; initialize once, outside the
    // Compose view factory so minimizing never destroys the media connection.
    DisposableEffect(engine) {
        remoteRenderer.init(engine.eglContext(), null)
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        remoteRenderer.setEnableHardwareScaler(true)
        localRenderer.init(engine.eglContext(), null)
        localRenderer.setMirror(true)
        localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localRenderer.setZOrderMediaOverlay(true)
        localRenderer.setEnableHardwareScaler(true)
        engine.attachRenderers(localRenderer, remoteRenderer)
        engine.start()
        onDispose {
            engine.stop()
            runCatching { localRenderer.release() }
            runCatching { remoteRenderer.release() }
            engine.disposeEgl()
        }
    }
    LaunchedEffect(connectedAt) {
        while (connectedAt > 0L) {
            elapsed = ((System.currentTimeMillis() - connectedAt) / 1000L).toInt().coerceAtLeast(0)
            kotlinx.coroutines.delay(1000L)
        }
    }
    DisposableEffect(session.callId, callStatus, connectedAt, hangingUp) {
        val ring = if (!session.incoming && !hangingUp && connectedAt == 0L && callStatus == "يرن الآن...")
            OutgoingRingback().also { it.start() } else null
        onDispose { ring?.stop() }
    }
    fun endCall() {
        if (hangingUp) return
        hangingUp = true
        engine.hangup()
    }

    BackHandler(enabled = !minimized) { onMinimize() }
    Box(modifier.clip(if (minimized) RoundedCornerShape(15.dp) else RoundedCornerShape(0.dp))
        .background(CallBackground)) {
        if (videoCall) {
            // The large view is the remote decoded frames, NOT an iframe/WebView.
            AndroidView(factory = { remoteRenderer }, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(top = if (minimized) 2.dp else 40.dp, end = 13.dp)
                    .size(width = if (minimized) 1.dp else 96.dp, height = if (minimized) 1.dp else 128.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(CallPanel)
                    .border(2.dp, CallGold.copy(alpha = .85f), RoundedCornerShape(13.dp))
            ) {
                AndroidView(factory = { localRenderer }, modifier = Modifier.fillMaxSize())
                if (!localReady) {
                    Text("جارٍ تشغيل كاميرتك...", modifier = Modifier.align(Alignment.Center)
                        .padding(5.dp), textAlign = TextAlign.Center,
                        color = CallGold, fontSize = 10.sp)
                }
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp)
                    .clip(CircleShape).background(Color(0xB0050709))
                    .semantics { contentDescription = "تبديل الكاميرا" }
                    .clickable { engine.switchCamera { front -> localRenderer.post { localRenderer.setMirror(front) } } },
                    contentAlignment = Alignment.Center) {
                    SiteIcon("repeat", tint = CallWhite, size = 22.dp)
                }
                Text("أنت", color = CallWhite, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .background(Color(0xB0050709)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }

        if (minimized) {
            Row(
                Modifier.fillMaxSize()
                    .background(CallPanel.copy(alpha = .96f), RoundedCornerShape(15.dp))
                    .border(1.dp, CallGold.copy(alpha = .65f), RoundedCornerShape(15.dp))
                    .clickable { onRestore() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SiteIcon(if (videoCall) "video" else "phone", tint = CallGold, size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(peerDisplayName, color = CallWhite, fontSize = 13.sp, maxLines = 1)
                    Text(if (connectedAt > 0) "مكالمة جارية — عودة" else callStatus,
                        color = CallGold, fontSize = 10.sp, maxLines = 2)
                }
                Box(Modifier.size(42.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                    .background(Color(0xFFDC2736)).border(1.dp, CallGold, CircleShape)
                    .clickable { endCall() }, contentAlignment = Alignment.Center) {
                    SiteIcon("phone", modifier = Modifier.rotate(135f), tint = CallWhite, size = 23.dp)
                }
            }
        } else {
            // Audio-call identity and live status are centered in one balanced
            // area. Video keeps decoded remote frames behind a compact overlay.
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onMinimize) {
                        SiteIcon("chevron-left", tint = CallGold, size = 23.dp)
                    }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (!videoCall || !remoteReady) {
                        Box(Modifier.size(126.dp).offset(y = if (videoCall && !remoteReady) 12.dp else 0.dp)
                            .border(2.dp, CallGold, CircleShape)
                            .background(Color(0xFF15120D), CircleShape),
                            contentAlignment = Alignment.Center) {
                            if (peerAvatar.isNotBlank()) {
                                ProfileMediaImage(peerAvatar, peerDisplayName,
                                    Modifier.fillMaxSize().clip(CircleShape))
                            } else {
                                SiteIcon("user", tint = CallGold, size = 80.dp)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                    // During an established video call, leave the remote picture
                    // unobstructed. Audio calls keep their existing identity UI.
                    if (!videoCall || !videoEstablished) {
                        Text(peerDisplayName, fontSize = 24.sp,
                            fontWeight = FontWeight.Bold, color = CallWhite,
                            textAlign = TextAlign.Center, maxLines = 1)
                        Spacer(Modifier.height(6.dp))
                        Text(if (videoCall) "مكالمة فيديو" else "مكالمة صوتية",
                            color = CallMuted, fontSize = 16.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(30.dp))
                    }
                    // Decorative, non-interactive audio wave; does not replace WebRTC.
                    if (!videoCall) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            listOf(6, 12, 18, 12, 25, 43, 22, 12, 7).forEach { height ->
                                Box(Modifier.width(4.dp).height(height.dp)
                                    .background(CallGold, CircleShape))
                            }
                        }
                        Spacer(Modifier.height(30.dp))
                    }
                    if (!videoCall || !videoEstablished) {
                        Text(
                            if (connectedAt > 0L) "%02d:%02d".format(elapsed / 60, elapsed % 60)
                            else callStatus,
                            color = if (connectedAt > 0L) CallWhite else CallGold,
                            fontSize = 17.sp, textAlign = TextAlign.Center, maxLines = 2
                        )
                    }
                }
                // Bottom third, rather than overlapping Android's navigation bar.
                Row(Modifier.fillMaxWidth().height(92.dp)
                    .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (videoCall) 12.dp else 20.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically) {
                    // RTL row: first item is at the far right in audio and video calls.
                    CallControl("phone", "إنهاء", danger = true) { endCall() }
                    CallControl(if (muted) "mic-off" else "mic", "الميكروفون", active = muted) {
                        muted = engine.toggleMute()
                    }
                    if (videoCall) {
                        CallControl(if (cameraOff) "camera-off" else "video", "الكاميرا", active = cameraOff) {
                            cameraOff = engine.toggleCamera()
                        }
                    }
                    CallControl("volume", "السماعة", active = speakerOn) {
                        speakerOn = engine.toggleSpeaker()
                    }
                }
                Spacer(Modifier.height(44.dp))
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(if (danger) 64.dp else 59.dp).shadow(if (danger) 6.dp else 0.dp, CircleShape).clip(CircleShape)
                .background(if (danger) Color(0xFFDC2736) else if (active) Color(0xFF665029) else CallPanel)
                .border(BorderStroke(1.dp, if (danger) CallGold.copy(alpha = .92f) else CallGold.copy(alpha = .3f)), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            SiteIcon(icon, modifier = if (danger) Modifier.rotate(135f) else Modifier, tint = CallWhite, size = if (danger) 30.dp else 27.dp)
        }
        Text(label, color = CallWhite, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
