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

/** Native ringtone is scoped to the visible incoming-call prompt. */
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
        if (lifecycleOwner == null || lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) alert.start()
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            alert.stop()
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
                    .padding(top = if (minimized) 2.dp else 93.dp, end = 13.dp)
                    .size(width = if (minimized) 1.dp else 116.dp, height = if (minimized) 1.dp else 158.dp)
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
                Box(Modifier.size(37.dp).clip(CircleShape).background(Color(0xFFE83838))
                    .clickable { endCall() }, contentAlignment = Alignment.Center) {
                    SiteIcon("phone-off", tint = CallWhite, size = 20.dp)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onMinimize) {
                        SiteIcon("chevron-left", tint = CallGold, size = 23.dp)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!videoCall) {
                    Column(Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        SiteIcon("user", tint = CallGold, size = 96.dp)
                        Spacer(Modifier.height(14.dp))
                        Text(peerDisplayName, fontSize = 20.sp, color = CallWhite,
                            textAlign = TextAlign.Center, maxLines = 1)
                        Text("مكالمة صوتية", fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, color = CallGold,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    }
                } else {
                    // Video-call chrome: center only the identity; the native remote
                    // video remains full-screen behind it. Keep diagnostic text hidden.
                    Column(Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text(peerDisplayName, fontSize = 20.sp, color = CallWhite,
                            textAlign = TextAlign.Center, maxLines = 1)
                        Text("مكالمة فيديو", fontSize = 16.sp, color = CallGold,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp))
                        if (connectedAt > 0L) {
                            Text("%02d:%02d".format(elapsed / 60, elapsed % 60),
                                color = CallWhite, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp), fontSize = 15.sp)
                        } else {
                            // Keep signaling status visible until the remote peer answers.
                            Text(callStatus, color = CallGold, textAlign = TextAlign.Center,
                                maxLines = 2, fontSize = 15.sp,
                                modifier = Modifier.padding(top = 10.dp, start = 16.dp, end = 16.dp))
                        }
                    }
                }
                if (!videoCall) {
                    Text(callStatus, color = CallGold, textAlign = TextAlign.Center,
                        fontSize = 15.sp, maxLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 8.dp)
                            .background(Color(0xDD050709)))
                    if (connectedAt > 0L) {
                        Text("%02d:%02d".format(elapsed / 60, elapsed % 60),
                            color = CallWhite, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(), fontSize = 15.sp)
                    }
                }
                if (videoCall) Spacer(Modifier.height(58.dp))
                Row(Modifier.fillMaxWidth().height(106.dp).background(CallBackground)
                    .padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    CallControl(if (muted) "mic-off" else "mic", "الميكروفون", active = muted) {
                        muted = engine.toggleMute()
                    }
                    if (videoCall) CallControl(if (cameraOff) "camera-off" else "video", "الكاميرا", active = cameraOff) {
                        cameraOff = engine.toggleCamera()
                    }
                    CallControl("volume", "السماعة", active = speakerOn) {
                        speakerOn = engine.toggleSpeaker()
                    }
                    CallControl("phone-off", "إنهاء", danger = true) { endCall() }
                }
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
            Modifier.size(59.dp).clip(CircleShape)
                .background(if (danger) Color(0xFFE83838) else if (active) Color(0xFF665029) else CallPanel)
                .border(BorderStroke(1.dp, if (danger) Color(0xFFFF6666) else CallGold.copy(alpha = .3f)), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            SiteIcon(icon, tint = CallWhite, size = 27.dp)
        }
        Text(label, color = CallWhite, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
