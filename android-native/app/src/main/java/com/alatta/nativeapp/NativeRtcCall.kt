package com.alatta.nativeapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * تنفيذ مكالمة WebRTC أصلي مخصص لنظام Android.
 * لا تُحمّل هنا صفحة HTML أو WebView أو Canvas أو صفحة مكالمات الموقع.
 * يطابق بروتوكول الإشارات بصيغة JSON ما في cloudflare-site/public/app.js:
 * call-request وcall-ringing وcall-accept وoffer وanswer وice وhangup.
 * يوفّر WebSocket للمستخدم المسجل دخوله إشارات فورية واردة، مع
 * واجهة /api/call-media-signals المصادق عليها كبديل صغير للتخزين.
 */
internal class NativeRtcCall(
    private val context: Context,
    private val session: NativeCallSession,
    private val token: String,
    private val me: String,
    private val report: (String) -> Unit,
    private val localFrame: () -> Unit,
    private val remoteFrame: () -> Unit,
    private val connected: () -> Unit,
    private val audioRouteChanged: (Boolean) -> Unit,
    private val bluetoothAvailabilityChanged: (Boolean) -> Unit,
    private val finished: () -> Unit
) {
    companion object {
        private const val BASE = "https://tawasol-alatta.omaratta077-4fd.workers.dev"
        private val factoryInitialized = AtomicBoolean(false)
        private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build()
        private val turnLock = Mutex()
        private var cachedIce: JSONObject? = null
        private var cachedIceToken = ""
        private var cachedIceExpires = 0L
        private var activeEngine: NativeRtcCall? = null

        // وجّه WebSocket الحالي للمستخدم المسجّل دخوله مباشرةً إلى المحرك الأصلي الوحيد.
        fun deliverSignal(signal: JSONObject) { activeEngine?.enqueueSignal(signal) }
        fun clearIceCache() { cachedIce = null; cachedIceToken = ""; cachedIceExpires = 0L }
        suspend fun prewarmIce(token: String) { try { turnCredentials(token) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }

        internal suspend fun turnCredentials(token: String): JSONObject = turnLock.withLock {
            if (cachedIceToken == token && cachedIceExpires > System.currentTimeMillis())
                return@withLock requireNotNull(cachedIce)
            val result = requestJson("/api/turn-credentials?token=${URLEncoder.encode(token, "UTF-8")}")
            if ((result.optJSONArray("iceServers")?.length() ?: 0) == 0) error("خوادم TURN غير متاحة")
            cachedIce = result; cachedIceToken = token
            cachedIceExpires = System.currentTimeMillis() + (result.optLong("ttl", 3600L) - 300L).coerceIn(60L, 3600L) * 1000L
            result
        }

        internal fun initializeRtc(context: Context) {
            if (factoryInitialized.compareAndSet(false, true)) PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        }
        internal fun mediaFactory(context: Context, egl: EglBase, audio: org.webrtc.audio.AudioDeviceModule? = null): PeerConnectionFactory {
            initializeRtc(context)
            val builder = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            if (audio != null) builder.setAudioDeviceModule(audio)
            return builder.createPeerConnectionFactory()
        }

        internal suspend fun requestJson(path: String, post: JSONObject? = null): JSONObject {
            val request = Request.Builder().url(BASE + path).header("Accept", "application/json").apply {
                if (post != null) this.post(post.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
            return suspendCancellableCoroutine { continuation ->
                val call = http.newCall(request)
                // يلغي انتهاء مهلة إنهاء المكالمة طلب المقبس فعلًا بدلًا من انتظار انتهاء الإدخال والإخراج.
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val parsed = response.use {
                                val value = JSONObject(it.body?.string().orEmpty().ifBlank { "{}" })
                                if (!it.isSuccessful || !value.optBoolean("ok", true))
                                    error(value.optString("error", "خطأ اتصال بالخادم (${it.code})"))
                                value
                            }
                            if (continuation.isActive) continuation.resume(parsed)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                })
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val egl = run {
        // تُهيّأ العروض قبل التقاط الوسائط؛ لذا حمّل مكتبة JNI
        // قبل إنشاء أسطح EGL، لا بعدها.
        initializeRtc(context)
        EglBase.create()
    }
    private val seen = LinkedHashSet<String>()
    private val pendingIce = ArrayList<IceCandidate>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val priorAudioMode = audioManager.mode
    @Suppress("DEPRECATION")
    private val priorSpeaker = audioManager.isSpeakerphoneOn
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideo: VideoTrack? = null
    private var localAudio: AudioTrack? = null
    private var remoteVideo: VideoTrack? = null
    private var localView: SurfaceViewRenderer? = null
    private var remoteView: SurfaceViewRenderer? = null
    private val firstLocal = AtomicBoolean(false)
    private val firstRemote = AtomicBoolean(false)
    private var started = false
    private var ended = false
    private var offerStarted = false
    private val signalMutex = Mutex()
    private var videoSender: RtpSender? = null
    private var pollJob: Job? = null
    private var callTimeoutJob: Job? = null
    private var speaker = session.type == "video"
    private var routeChosenByUser = false
    private var audioRouteReady = false
    private var legacyScoRequested = false
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshAudioRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshAudioRoute()
    }
    private var muted = false
    private var cameraOff = false
    private val localFrameProbe = VideoSink {
        if (firstLocal.compareAndSet(false, true)) scope.launch { localFrame() }
    }
    private val remoteFrameProbe = VideoSink {
        if (firstRemote.compareAndSet(false, true)) {
            // يثبت الإطار المفكوك وصول الفيديو البعيد بالفعل،
            // حتى على الأجهزة التي تتأخر فيها إشعارات اتصال WebRTC.
            confirmMediaConnected()
            scope.launch { remoteFrame() }
        }
    }

    private val mediaConnected = AtomicBoolean(false)
    private fun confirmMediaConnected() {
        if (ended || !mediaConnected.compareAndSet(false, true)) return
        scope.launch {
            if (ended) return@launch
            report("متصل")
            connected()
            callTimeoutJob?.cancel()
        }
    }

    fun eglContext(): EglBase.Context = egl.eglBaseContext

    fun attachRenderers(local: SurfaceViewRenderer, remote: SurfaceViewRenderer) {
        localView = local
        remoteView = remote
        localVideo?.addSink(local)
        remoteVideo?.addSink(remote)
    }

    fun start() {
        if (started || ended) return
        started = true
        scope.launch {
            try {
                report("جارٍ تشغيل الكاميرا والميكروفون...")
                // ابدأ طلب الشبكة بالتوازي مع تهيئة الكاميرا؛ وغالبًا يُخدم من التهيئة المسبقة.
                val pendingServers = async(Dispatchers.IO) { loadIceServers() }
                initializeNativeMedia()
                if (ended) return@launch
                val ice = pendingServers.await()
                if (ended) return@launch
                createPeer(ice)
                activeEngine = this@NativeRtcCall
                pollJob = scope.launch { pollConversation() }
                if (session.incoming) {
                    report("جارٍ الرد على المكالمة...")
                    sendSignal("call-accept")
                } else {
                    report("جارٍ الاتصال...")
                    sendSignal("call-request")
                }
                callTimeoutJob = scope.launch {
                    delay(55000L)
                    if (!ended && peer?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) {
                        report("لم يتم الرد أو تعذر اتصال الوسائط")
                        hangup()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                report("تعذر بدء المكالمة: ${e.message?.take(90) ?: "خطأ في الوسائط"}")
                // أبقِ عناصر التحكم الأصلية ظاهرة حتى يستطيع المستخدم إنهاء المكالمة.
            }
        }
    }

    private fun initializeNativeMedia() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (session.type == "video" && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)) {
            error("يجب السماح للتطبيق بالكاميرا والميكروفون")
        }
        factory = mediaFactory(context, egl)
        val pf = requireNotNull(factory)
        audioSource = pf.createAudioSource(MediaConstraints())
        localAudio = pf.createAudioTrack("atta-audio", audioSource)
        if (session.type == "video") {
            val enumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context)
                else Camera1Enumerator(false)
            val chosen = enumerator.deviceNames.sortedBy { if (enumerator.isFrontFacing(it)) 0 else 1 }
            videoCapturer = chosen.firstNotNullOfOrNull { enumerator.createCapturer(it, null) }
            if (videoCapturer == null && enumerator is Camera2Enumerator) {
                val fallback = Camera1Enumerator(false)
                videoCapturer = fallback.deviceNames.firstNotNullOfOrNull { fallback.createCapturer(it, null) }
            }
            val capture = requireNotNull(videoCapturer) { "الكاميرا غير متاحة" }
            videoSource = pf.createVideoSource(false)
            helper = SurfaceTextureHelper.create("AttaCamera", egl.eglBaseContext)
            capture.initialize(helper, context, requireNotNull(videoSource).capturerObserver)
            localVideo = pf.createVideoTrack("atta-video", videoSource)
            localVideo?.setEnabled(true)
            localVideo?.addSink(localFrameProbe)
            localView?.let { localVideo?.addSink(it) }
            // يخدم التقاط كاميرا أصلي واحد كلاً من PiP ومرسل WebRTC.
            capture.startCapture(640, 360, 20)
            videoSource?.adaptOutputFormat(640, 360, 20)
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioRouteReady = true
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        refreshAudioRoute()
    }

    private fun bluetoothOutputPresent(): Boolean = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    device.type == AudioDeviceInfo.TYPE_HEARING_AID) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }.getOrDefault(false)

    // اختر جهاز اتصال ثنائي الاتجاه، لا مخرج وسائط A2DP فقط.
    // في Android 12 وما بعده، يوجّه setCommunicationDevice تشغيل الصوت والميكروفون.
    @Suppress("DEPRECATION")
    fun refreshAudioRoute() {
        if (!audioRouteReady || ended) return
        bluetoothAvailabilityChanged(bluetoothOutputPresent())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
            val bluetooth = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
            }
            if (!routeChosenByUser) speaker = bluetooth == null && session.type == "video"
            val target = if (speaker) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else {
                bluetooth ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            val routed = target != null && runCatching {
                audioManager.setCommunicationDevice(target)
            }.getOrDefault(false)
            if (!routed) audioManager.isSpeakerphoneOn = speaker
        } else {
            if (!routeChosenByUser) speaker = !bluetoothOutputPresent() && session.type == "video"
            if (!speaker && bluetoothOutputPresent() && audioManager.isBluetoothScoAvailableOffCall) {
                if (!legacyScoRequested) {
                    legacyScoRequested = true
                    runCatching { audioManager.startBluetoothSco() }
                }
                runCatching { audioManager.isBluetoothScoOn = true }
                audioManager.isSpeakerphoneOn = false
            } else {
                if (legacyScoRequested) {
                    legacyScoRequested = false
                    runCatching { audioManager.isBluetoothScoOn = false; audioManager.stopBluetoothSco() }
                }
                audioManager.isSpeakerphoneOn = speaker
            }
        }
        audioRouteChanged(speaker)
    }

    private suspend fun loadIceServers(): List<PeerConnection.IceServer> {
        return try {
            parseRtcIceServers(turnCredentials(token))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // يحافظ البديل المباشر عبر STUN على الصوت والفيديو في الشبكات المفتوحة.
            report("TURN غير متاح — جارٍ تجربة STUN")
            fallbackRtcIceServers()
        }
    }

    private fun createPeer(ice: List<PeerConnection.IceServer>) {
        val options = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceCandidatePoolSize = 2
        }
        peer = requireNotNull(factory).createPeerConnection(options, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> confirmMediaConnected()
                    PeerConnection.IceConnectionState.FAILED -> scope.launch {
                        report("فشل اتصال الوسائط — راجع إعداد TURN")
                    }
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null || ended) return
                scope.launch {
                    runCatching {
                        sendSignal("ice", JSONObject().put("candidate", JSONObject()
                            .put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)))
                    }
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let(::attachRemoteTrack)
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.let(::attachRemoteTrack)
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                (transceiver?.receiver?.track() as? VideoTrack)?.let(::attachRemoteTrack)
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> confirmMediaConnected()
                    PeerConnection.PeerConnectionState.DISCONNECTED -> scope.launch { report("الاتصال ضعيف — جارٍ إعادة المحاولة") }
                    PeerConnection.PeerConnectionState.FAILED -> scope.launch { report("تعذر توصيل الوسائط — تحقق من TURN") }
                    else -> Unit
                }
            }
        }) ?: error("تعذر إنشاء اتصال WebRTC")
        localAudio?.let { peer?.addTrack(it, listOf("atta-stream")) }
        localVideo?.let { videoSender = peer?.addTrack(it, listOf("atta-stream")) }
        if (session.type == "video") peer?.setBitrate(null, 300_000, 750_000)
        applyVideoPolicy()
    }

    private fun applyVideoPolicy() {
        val sender = videoSender ?: return
        val parameters = sender.parameters
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        parameters.encodings.forEach { encoding ->
            encoding.maxBitrateBps = 650_000
            encoding.maxFramerate = 20
        }
        if (parameters.encodings.isNotEmpty() && !sender.setParameters(parameters))
            report("جارٍ ضبط جودة الفيديو تلقائيًا")
    }

    private fun enqueueSignal(signal: JSONObject) {
        if (ended) return
        scope.launch {
            try { receiveSignal(signal) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Stored signaling retries through the small fallback endpoint. */ }
        }
    }

    private fun attachRemoteTrack(track: VideoTrack) {
        if (remoteVideo === track || ended) return
        remoteVideo?.removeSink(remoteFrameProbe)
        remoteView?.let { remoteVideo?.removeSink(it) }
        remoteVideo = track
        track.addSink(remoteFrameProbe)
        remoteView?.let(track::addSink)
    }

    private suspend fun pollConversation() {
        // استطلع إشارات النظير نفسها المخزنة التي يستخدمها الموقع العامل.
        // لا ضرر من تعدد القراء المستقلين؛ فهم لا يستهلكون الرسائل.
        while (scope.isActive && !ended) {
            try {
                // اجلب SDP/ICE لهذه المكالمة فقط؛ ولا تنزّل صور المحادثة وسجلها في كل استطلاع.
                val d = api("/api/call-media-signals?token=${urlEncode(token)}&with=${urlEncode(session.peer)}&callId=${urlEncode(session.callId)}")
                val signals = d.optJSONArray("signals") ?: JSONArray()
                for (i in 0 until signals.length()) signals.optJSONObject(i)?.let { receiveSignal(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* the next poll retries without dropping the call */ }
            // إشارات أسرع أثناء الاتصال، مع تقليل الطلبات بعد استقرار المكالمة.
            delay(if (peer?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) 2500L else 900L)
        }
    }

    private suspend fun receiveSignal(s: JSONObject) = signalMutex.withLock {
        try { processSignal(s) }
        catch (e: Exception) {
            seen.remove(s.optString("signalId").ifBlank { "${s.optString("type")}:${s.optLong("createdAt")}" })
            throw e
        }
    }

    private suspend fun processSignal(s: JSONObject) {
        if (ended || s.optString("callId") != session.callId ||
            !s.optString("from").equals(session.peer, ignoreCase = true) ||
            (s.has("to") && !s.optString("to").equals(me, ignoreCase = true))) return
        val age = System.currentTimeMillis() - s.optLong("createdAt")
        if (age !in -30000L..120000L) return
        val kind = s.optString("type")
        val key = s.optString("signalId").ifBlank { "${kind}:${s.optLong("createdAt")}" }
        if (!seen.add(key)) return
        while (seen.size > 400) seen.remove(seen.first())
        when (kind) {
            "call-ringing" -> if (!session.incoming && !offerStarted) report("يرن الآن...")
            "call-reject" -> { report(if (s.optString("reason") == "busy") "المستخدم مشغول في مكالمة أخرى" else "تم رفض المكالمة"); finishAfterDelay() }
            "hangup" -> { report("انتهت المكالمة"); finishAfterDelay() }
            "call-accept" -> if (!session.incoming && !offerStarted) {
                offerStarted = true
                report("تم الرد — جاري توصيل الصوت والصورة...")
                try {
                    val conn = requireNotNull(peer)
                    val offer = createDescription(conn, true)
                    setDescription(conn, offer, local = true)
                    // أُرسلت ICE المتدرجة بالفعل بواسطة onIceCandidate؛ فلا تحجب SDP.
                    sendSignal("offer", JSONObject().put("sdp", JSONObject()
                        .put("type", "offer").put("sdp", conn.localDescription?.description ?: offer.description)))
                } catch (e: Exception) {
                    offerStarted = false
                    report("فشل إنشاء الاتصال: ${e.message?.take(75)}")
                }
            }
            "offer" -> {
                val conn = peer ?: return
                val desc = s.optJSONObject("sdp") ?: return
                setDescription(conn, SessionDescription(SessionDescription.Type.OFFER, desc.optString("sdp")), local = false)
                flushIce()
                val answer = createDescription(conn, false)
                setDescription(conn, answer, local = true)
                // تقبل الوجهة البعيدة ICE المتدرج؛ فأرسل الإجابة سريعًا.
                sendSignal("answer", JSONObject().put("sdp", JSONObject()
                    .put("type", "answer").put("sdp", conn.localDescription?.description ?: answer.description)))
                report("جارٍ إتمام اتصال الفيديو والصوت...")
            }
            "answer" -> {
                val conn = peer ?: return
                if (conn.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                    val desc = s.optJSONObject("sdp") ?: return
                    setDescription(conn, SessionDescription(SessionDescription.Type.ANSWER, desc.optString("sdp")), local = false)
                    flushIce()
                    report("جارٍ إتمام الاتصال...")
                }
            }
            "ice" -> {
                val c = s.optJSONObject("candidate") ?: return
                val candidate = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
                if (peer?.remoteDescription == null) pendingIce += candidate
                else peer?.addIceCandidate(candidate)
            }
        }
    }

    private fun flushIce() {
        val conn = peer ?: return
        pendingIce.forEach { conn.addIceCandidate(it) }
        pendingIce.clear()
    }

    private suspend fun createDescription(conn: PeerConnection, offer: Boolean): SessionDescription {
        val result = CompletableDeferred<SessionDescription>()
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) result.complete(sdp) else result.completeExceptionally(IllegalStateException("SDP فارغ"))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { result.completeExceptionally(IllegalStateException(error ?: "SDP error")) }
            override fun onSetFailure(error: String?) {}
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", (session.type == "video").toString()))
        }
        if (offer) conn.createOffer(observer, constraints) else conn.createAnswer(observer, constraints)
        return withTimeoutOrNull(12000L) { result.await() } ?: error("مهلة إنشاء SDP")
    }

    private suspend fun setDescription(conn: PeerConnection, sdp: SessionDescription, local: Boolean) {
        val result = CompletableDeferred<Unit>()
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() { result.complete(Unit) }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) { result.completeExceptionally(IllegalStateException(error ?: "SDP set failure")) }
        }
        if (local) conn.setLocalDescription(observer, sdp) else conn.setRemoteDescription(observer, sdp)
        withTimeoutOrNull(12000L) { result.await() } ?: error("مهلة تعيين SDP")
        applyVideoPolicy()
    }

    private suspend fun sendSignal(kind: String, extra: JSONObject = JSONObject()) {
        val packet = JSONObject(extra.toString())
            .put("type", kind).put("to", session.peer).put("from", me)
            .put("callId", session.callId).put("callType", session.type)
            .put("signalId", UUID.randomUUID().toString())
            .put("createdAt", System.currentTimeMillis())
        val body = JSONObject().put("token", token).put("to", session.peer).put("text", "")
            .put("media", packet.toString()).put("mediaType", "call-signal")
        // احتفظ بـ signalId عبر المحاولات لمنع تكرار نسختي WebSocket وHTTP.
        for (attempt in 0..2) {
            try { api("/api/message-send", body); return }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == 2) throw e else delay(150L * (attempt + 1)) }
        }
    }

    private fun urlEncode(text: String) = URLEncoder.encode(text, "UTF-8")

    private suspend fun api(path: String, post: JSONObject? = null): JSONObject = requestJson(path, post)

    fun toggleMute(): Boolean {
        muted = !muted
        localAudio?.setEnabled(!muted)
        return muted
    }
    private val switchingCamera = AtomicBoolean(false)

    fun switchCamera(onSwitched: (Boolean) -> Unit) {
        val capturer = videoCapturer ?: return
        if (ended || !switchingCamera.compareAndSet(false, true)) return
        runCatching {
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    switchingCamera.set(false)
                    if (!ended) onSwitched(isFrontCamera)
                }
                override fun onCameraSwitchError(errorDescription: String) {
                    switchingCamera.set(false)
                    if (!ended) report("تعذر تبديل الكاميرا")
                }
            })
        }.onFailure { switchingCamera.set(false); if (!ended) report("تعذر تبديل الكاميرا") }
    }
    fun toggleCamera(): Boolean {
        cameraOff = !cameraOff
        localVideo?.setEnabled(!cameraOff)
        return cameraOff
    }
    fun toggleSpeaker(): Boolean {
        routeChosenByUser = true
        speaker = !speaker
        refreshAudioRoute()
        return speaker
    }

    private fun finishAfterDelay() {
        scope.launch { delay(900L); if (!ended) finished() }
    }

    fun hangup() {
        if (ended) return
        scope.launch {
            withTimeoutOrNull(1200L) { runCatching { sendSignal("hangup") } }
            if (!ended) finished()
        }
    }

    fun stop() {
        if (activeEngine === this) activeEngine = null
        if (ended) return
        ended = true
        callTimeoutJob?.cancel()
        pollJob?.cancel()
        scope.cancel()
        localVideo?.let { track ->
            runCatching { track.removeSink(localFrameProbe) }
            localView?.let { runCatching { track.removeSink(it) } }
        }
        remoteVideo?.let { track ->
            runCatching { track.removeSink(remoteFrameProbe) }
            remoteView?.let { runCatching { track.removeSink(it) } }
        }
        runCatching { videoCapturer?.stopCapture() }
        runCatching { peer?.close() }; peer = null
        runCatching { videoCapturer?.dispose() }
        runCatching { helper?.dispose() }
        runCatching { localVideo?.dispose() }
        runCatching { localAudio?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { factory?.dispose() }
        if (audioRouteReady) {
            audioRouteReady = false
            runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { audioManager.clearCommunicationDevice() }
            } else if (legacyScoRequested) {
                @Suppress("DEPRECATION")
                runCatching { audioManager.isBluetoothScoOn = false; audioManager.stopBluetoothSco() }
            }
            legacyScoRequested = false
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = priorSpeaker
        audioManager.mode = priorAudioMode
        // تحرر الجهة المالكة للمرسم SurfaceViewRenderer قبل إنهاء EGL.
        // تحرر الشاشة EglBase نفسها بعد التخلص من المرسومات.
    }

    fun disposeEgl() { runCatching { egl.release() } }
}
