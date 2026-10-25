package com.alatta.nativeapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

private const val SPORTS_LIVE_CHANNEL = "sports_screen_live"

internal class SportsScreenLiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(SPORTS_LIVE_CHANNEL, "بث الرياضة المباشر", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 92, Intent(this, SportsScreenLiveService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(this, SPORTS_LIVE_CHANNEL).setSmallIcon(R.drawable.atta_notification_icon)
            .setContentTitle("بث رياضي مباشر").setContentText("مشاركة الشاشة جارية")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "إيقاف", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(8192, notice, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(8192, notice)
        return START_NOT_STICKY
    }
}

private suspend fun sportsApi(token: String, payload: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
    val path = "/api/sports-broadcast" + if (payload == null) "?token=${java.net.URLEncoder.encode(token, "UTF-8")}" else ""
    attaApi(path, payload?.put("token", token))
}

private suspend fun sportsIceServers(token: String): List<PeerConnection.IceServer> = withContext(Dispatchers.IO) {
    try {
        val encoded = java.net.URLEncoder.encode(token, "UTF-8")
        parseRtcIceServers(attaApi("/api/turn-credentials?token=$encoded"))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { fallbackRtcIceServers() }
}

private suspend fun createSportsDescription(peer: PeerConnection, offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
    val observer = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) { if (sdp != null) cont.resume(sdp) else cont.resumeWithException(IllegalStateException("SDP فارغ")) }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "تعذر تجهيز الاتصال")) }
        override fun onSetFailure(error: String?) {}
    }
    if (offer) peer.createOffer(observer, MediaConstraints()) else peer.createAnswer(observer, MediaConstraints())
}

private suspend fun setSportsDescription(peer: PeerConnection, value: SessionDescription, local: Boolean) = suspendCancellableCoroutine<Unit> { cont ->
    val observer = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "تعذر تعيين وصف الاتصال")) }
    }
    if (local) peer.setLocalDescription(observer, value) else peer.setRemoteDescription(observer, value)
}

private object SportsWebRtc {
    private val initialized = AtomicBoolean(false)
    fun factory(context: Context, egl: EglBase): PeerConnectionFactory {
        if (initialized.compareAndSet(false, true)) PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        return PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
    }
    fun peer(factory: PeerConnectionFactory, observer: PeerConnection.Observer,
        ice: List<PeerConnection.IceServer> = fallbackRtcIceServers()): PeerConnection {
        return requireNotNull(factory.createPeerConnection(PeerConnection.RTCConfiguration(ice), observer))
    }
}

private class SportsLiveViewer(private val context: Context, private val token: String, private val live: JSONObject,
    private val onState: (String) -> Unit) {
    private val egl = EglBase.create()
    private val factory = SportsWebRtc.factory(context, egl)
    private var peer: PeerConnection? = null
    private var view: SurfaceViewRenderer? = null
    private var viewerSessionId = ""
    private var started = false
    private val stopped = AtomicBoolean(false)

    fun attach(renderer: SurfaceViewRenderer) {
        if (view === renderer) return
        view?.release()
        view = renderer
        renderer.init(egl.eglBaseContext, null); renderer.setEnableHardwareScaler(true); renderer.setMirror(false)
    }
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) { runCatching {
            val iceServers = sportsIceServers(token)
            val liveId = live.getString("liveId")
            val result = sportsApi(token, JSONObject().put("action", "watch").put("liveId", liveId))
            viewerSessionId = result.getString("sessionId")
            val offer = result.getJSONObject("sessionDescription")
            val gathered = CompletableDeferred<Unit>()
            val connection = SportsWebRtc.peer(factory, object : PeerConnection.Observer {
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) { when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> onState("متصل بالبث")
                    PeerConnection.IceConnectionState.FAILED -> onState("تعذر اتصال المشاهدة؛ تحقق من الاتصال بالإنترنت")
                    else -> Unit
                } }
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) { if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit) }
                override fun onIceCandidate(c: IceCandidate?) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddStream(s: MediaStream?) { s?.videoTracks?.firstOrNull()?.addSink(view ?: return) }
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(c: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) { r?.track()?.let { (it as? VideoTrack)?.addSink(view ?: return) } }
                override fun onTrack(t: RtpTransceiver?) { (t?.receiver?.track() as? VideoTrack)?.addSink(view ?: return) }
            }, iceServers)
            peer = connection
            setSportsDescription(connection, SessionDescription(SessionDescription.Type.OFFER, offer.getString("sdp")), false)
            val answer = createSportsDescription(connection, false)
            setSportsDescription(connection, answer, true)
            withTimeout(15000) { gathered.await() }
            sportsApi(token, JSONObject().put("action", "answer").put("liveId", liveId).put("sessionId", viewerSessionId)
                .put("sessionDescription", JSONObject().put("type", "answer").put("sdp", answer.description)))
            onState("جارٍ تشغيل البث")
        }.onFailure { onState(it.message ?: "تعذر تشغيل البث") } }
    }
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { view?.release() }; view = null
        runCatching { peer?.close(); peer?.dispose(); factory.dispose(); egl.release() }
        if (viewerSessionId.isNotBlank()) CoroutineScope(Dispatchers.IO).launch {
            runCatching { sportsApi(token, JSONObject().put("action", "leave").put("liveId", live.optString("liveId")).put("sessionId", viewerSessionId)) }
        }
    }
    fun eglContext() = egl.eglBaseContext
}

private class SportsScreenPublisher(
    private val context: Context, private val token: String, private val projectionResult: Intent,
    private val title: String, private val scope: CoroutineScope, private val onState: (String) -> Unit
) {
    private val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var liveId = ""
    private var cloudSessionId = ""
    private var heartbeat: Job? = null
    private val stopped = AtomicBoolean(false)

    fun start() { scope.launch(Dispatchers.IO) { runCatching { startNow() }.onFailure { onState(it.message ?: "تعذر بدء البث") ; stop(true) } } }
    private suspend fun startNow() {
        val iceServers = sportsIceServers(token)
        val started = sportsApi(token, JSONObject().put("action", "start").put("title", title))
        liveId = started.getString("liveId")
        val pcFactory = SportsWebRtc.factory(context, egl); factory = pcFactory
        capturer = ScreenCapturerAndroid(projectionResult, object : MediaProjection.Callback() { override fun onStop() { scope.launch { stop(true) } } })
        source = pcFactory.createVideoSource(true)
        helper = SurfaceTextureHelper.create("AttaSportsScreen", egl.eglBaseContext)
        capturer!!.initialize(helper, context, source!!.capturerObserver)
        capturer!!.startCapture(1280, 720, 24)
        val video = pcFactory.createVideoTrack("sports-screen", source)
        val gathered = CompletableDeferred<Unit>()
        peer = SportsWebRtc.peer(pcFactory, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) { when (s) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> onState("مباشر الآن")
                PeerConnection.IceConnectionState.FAILED -> onState("فشل اتصال البث؛ تحقق من الاتصال بالإنترنت")
                else -> Unit
            } }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) { if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit) }
            override fun onIceCandidate(c: IceCandidate?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(c: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
            override fun onTrack(t: RtpTransceiver?) {}
        }, iceServers)
        peer!!.addTrack(video, listOf("sports-live"))
        val offer = createSportsDescription(peer!!, true)
        setSportsDescription(peer!!, offer, true)
        withTimeout(15000) { gathered.await() }
        val mids = Regex("a=mid:([^\\r\\n]+)").findAll(offer.description).map { it.groupValues[1].trim() }.toList()
        val mid = mids.firstOrNull() ?: error("تعذر تجهيز مسار الشاشة")
        val result = sportsApi(token, JSONObject().put("action", "publish").put("liveId", liveId)
            .put("sessionDescription", JSONObject().put("type", "offer").put("sdp", offer.description))
            .put("tracks", JSONArray().put(JSONObject().put("location", "local").put("mid", mid).put("trackName", "screen"))))
        cloudSessionId = result.getString("sessionId")
        val answer = result.getJSONObject("sessionDescription")
        setSportsDescription(peer!!, SessionDescription(SessionDescription.Type.ANSWER, answer.getString("sdp")), false)
        onState("مباشر الآن")
        heartbeat = scope.launch { while (isActive) { delay(25_000); withContext(Dispatchers.IO) { sportsApi(token, JSONObject().put("action", "heartbeat").put("liveId", liveId)) } } }
    }
    fun stop(notifyServer: Boolean = true) {
        if (!stopped.compareAndSet(false, true)) return
        heartbeat?.cancel(); runCatching { capturer?.stopCapture() }; runCatching { capturer?.dispose() }
        runCatching { peer?.close(); peer?.dispose() }; runCatching { source?.dispose(); helper?.dispose(); factory?.dispose(); egl.release() }
        if (notifyServer && liveId.isNotBlank()) scope.launch(Dispatchers.IO) { runCatching { sportsApi(token, JSONObject().put("action", "stop").put("liveId", liveId)) } }
        context.startService(Intent(context, SportsScreenLiveService::class.java).setAction("stop")); onState("")
    }
}

@Composable
internal fun SportsLivePanel(token: String, isAdmin: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var live by remember { mutableStateOf<JSONObject?>(null) }
    var configured by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var publisher by remember { mutableStateOf<SportsScreenPublisher?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && title.isNotBlank()) {
            ContextCompat.startForegroundService(context, Intent(context, SportsScreenLiveService::class.java).putExtra("permission", result.data))
            publisher?.stop(false)
            publisher = SportsScreenPublisher(context, token, result.data!!, title, scope) { status = it }.also { p -> scope.launch { delay(500); p.start() } }
        } else status = "ألغيت مشاركة الشاشة"
    }
    LaunchedEffect(token) {
        while (isActive && token.isNotBlank()) {
            try { val result = sportsApi(token); configured = result.optBoolean("configured"); live = if (result.optBoolean("live")) result else null }
            catch (_: Exception) { }
            delay(8000)
        }
    }
    DisposableEffect(Unit) { onDispose { publisher?.stop(true) } }
    Surface(color = Color(0xFF201B10), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(if (live != null) "● بث رياضي مباشر" else "بث الشاشة في صفحة الرياضة", color = PremiumGoldLight, fontSize = 15.sp)
            when {
                status.isNotBlank() -> Text(status, color = Color.White, fontSize = 12.sp)
                live != null -> Text("${live?.optString("title")} • ${live?.optString("broadcaster")} • ${live?.optInt("viewerCount")} مشاهد", color = Color.White, fontSize = 12.sp)
                else -> Text("شاهد البث المباشر من هنا عند بدء المدير له.", color = PremiumMuted, fontSize = 12.sp)
            }
            if (isAdmin && live == null && !configured) Text("يتطلب تفعيل البث إضافة CLOUDFLARE_REALTIME_APP_ID وCLOUDFLARE_REALTIME_APP_SECRET كأسرار في إعدادات Worker.", color = Color(0xFFFFD782), fontSize = 12.sp)
            if (isAdmin && live == null && configured) {
                OutlinedTextField(title, { title = it.take(100) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("عنوان البث") })
                Button(enabled = !busy && title.trim().length >= 2, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            launcher.launch(manager.createScreenCaptureIntent())
                        } catch (e: Exception) { status = e.message ?: "تعذر طلب مشاركة الشاشة" }
                        finally { busy = false }
                    }
                }) { Text("بدء بث الشاشة") }
            }
            if (isAdmin && live != null) Button(onClick = {
                val id = live?.optString("liveId").orEmpty(); busy = true
                scope.launch(Dispatchers.IO) { runCatching { sportsApi(token, JSONObject().put("action", "stop").put("liveId", id)) }
                    .onSuccess { withContext(Dispatchers.Main) { publisher?.stop(false); publisher = null; live = null; status = "تم إيقاف البث" } }
                    .onFailure { withContext(Dispatchers.Main) { status = it.message ?: "تعذر إيقاف البث" } }; busy = false }
            }) { Text("إيقاف البث") }
            if (live != null && !live!!.optBoolean("isOwner")) {
                val currentLive = live!!
                val viewer = remember(token, currentLive.optString("liveId")) { SportsLiveViewer(context, token, currentLive) { status = it } }
                DisposableEffect(viewer) { onDispose { viewer.stop() } }
                AndroidView(modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp, max = 280.dp),
                    factory = { ctx -> SurfaceViewRenderer(ctx).also { viewer.attach(it) } }, update = { viewer.attach(it) })
                LaunchedEffect(viewer) { viewer.start(this) }
                if (status.isNotBlank()) Text(status, color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
