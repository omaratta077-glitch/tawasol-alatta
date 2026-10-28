package com.alatta.nativeapp

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID

/** One camera/microphone feeds bounded native WebRTC peers, using the existing TURN and SDP helpers. */
internal class WatchRoomEngine(private val context: Context, private val token: String,
    private val clientId: String, private val end: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val egl = run { NativeRtcCall.initializeRtc(context); EglBase.create() }
    private val audioRoute = WatchAudioRoute(context)
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var audio: AudioTrack? = null
    private var video: VideoTrack? = null
    private var camera: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var room: WatchRoom? = null
    private var stopped = false
    private var cursor = 0L
    private var servers: List<PeerConnection.IceServer> = emptyList()
    private val signals = Mutex()
    private val cameraLock = Mutex()
    private val seen = LinkedHashSet<String>()
    private val tracks = mutableMapOf<String, VideoTrack>()
    private val audioTracks = mutableMapOf<String, AudioTrack>()
    private val renderers = mutableMapOf<SurfaceViewRenderer, Pair<String, VideoTrack?>>()
    private data class Link(val peer: PeerConnection, var videoSender: RtpSender? = null, val ice: MutableList<IceCandidate> = ArrayList(), var retries: Int = 0)
    private val links = mutableMapOf<String, Link>()
    @Volatile private var cameraOff = false
    private var capturing = false

    private suspend fun request(data: JSONObject): JSONObject = NativeRtcCall.requestJson("/api/watch-room",
        data.put("token", token).put("clientId", clientId))

    fun start(action: String, code: String, channelId: String) { scope.launch {
        try {
            val initial = request(JSONObject().put("action", action).put("code", code).put("channelId", channelId))
            room = WatchRoom.parse(initial); WatchRoomController.room = room
            servers = try { parseRtcIceServers(NativeRtcCall.turnCredentials(token)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { fallbackRtcIceServers() }
            initializeMedia()
            WatchRoomController.ready = true
            reconcile(requireNotNull(room))
            var errors = 0
            while (isActive && !stopped) {
                try {
                    val state = requireNotNull(room)
                    val result = request(JSONObject().put("action", "poll").put("code", state.code).put("peerId", state.myPeerId)
                        .put("after", cursor).put("cameraOn", !cameraOff).put("micOn", !WatchRoomController.muted))
                    val next = WatchRoom.parse(result); room = next; WatchRoomController.room = next
                    reconcile(next)
                    val packets = result.optJSONArray("signals")
                    // Acknowledge only after SDP/ICE is processed. A failed packet must survive the next poll.
                    if (packets != null) for (i in 0 until packets.length())
                        check(receive(packets.getJSONObject(i), authoritativeRoster = true)) { "جارٍ استعادة اتصال الغرفة" }
                    cursor = result.optLong("cursor", cursor); errors = 0
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    errors++; WatchRoomController.status = "تعذر تحديث الاتصال؛ جارٍ المحاولة"
                    if (errors >= 10) throw IllegalStateException("انتهت الغرفة أو تعذر الاتصال بها")
                }
                delay(1500)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { WatchRoomController.status = error.message?.take(120) ?: "تعذر بدء الغرفة"; end() }
    } }

    private fun initializeMedia() {
        audioRoute.start()
        val module = watchAudioModule(context)
        factory = NativeRtcCall.mediaFactory(context, egl, module); module.release()
        val f = requireNotNull(factory)
        audioSource = f.createAudioSource(watchMicrophoneConstraints())
        audio = f.createAudioTrack("watch-audio", audioSource).also { it.setEnabled(!WatchRoomController.muted) }
        val enumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
        camera = enumerator.deviceNames.sortedBy { if (enumerator.isFrontFacing(it)) 0 else 1 }
            .firstNotNullOfOrNull { enumerator.createCapturer(it, null) }
        if (camera == null && enumerator is Camera2Enumerator) {
            val fallback = Camera1Enumerator(false)
            camera = fallback.deviceNames.firstNotNullOfOrNull { fallback.createCapturer(it, null) }
        }
        val capture = requireNotNull(camera) { "الكاميرا غير متاحة" }
        videoSource = f.createVideoSource(false)
        helper = SurfaceTextureHelper.create("AttaWatchCamera", egl.eglBaseContext)
        capture.initialize(helper, context, requireNotNull(videoSource).capturerObserver)
        video = f.createVideoTrack("watch-video", videoSource)
        capture.startCapture(480, 270, 15); capturing = true
        videoSource?.adaptOutputFormat(480, 270, 15)
        room?.myPeerId?.let { bindTrack(it, requireNotNull(video)) }
    }
    private fun reconcile(state: WatchRoom) {
        val remote = state.members.filter { it.peerId != state.myPeerId }.map { it.peerId }.toSet()
        links.keys.filter { it !in remote }.toList().forEach(::closePeer)
        for (id in remote) if (id !in links) {
            createPeer(id)
            if (state.myPeerId < id) scope.launch { signals.withLock { offer(id) } }
        }
        if (remote.isEmpty()) WatchRoomController.status = "بانتظار أصحابك"
    }
    private fun createPeer(id: String): Link {
        links[id]?.let { return it }
        val configuration = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        val connection = requireNotNull(requireNotNull(factory).createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                scope.launch {
                    if (stopped || id !in links) return@launch
                    if (s == PeerConnection.IceConnectionState.CONNECTED || s == PeerConnection.IceConnectionState.COMPLETED)
                        WatchRoomController.status = "متصل • ${room?.members?.size ?: 1} أشخاص"
                    if (s == PeerConnection.IceConnectionState.FAILED) {
                        val link = links[id] ?: return@launch
                        if (link.retries++ < 2) signals.withLock {
                            if (requireNotNull(room).myPeerId < id) offer(id, true) else send(id, "restart")
                        } else WatchRoomController.status = "تعذر اتصال أحد المشاركين؛ تحققوا من الإنترنت"
                    }
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) { if (c != null) scope.launch {
                runCatching { send(id, "ice", JSONObject().put("candidate", JSONObject().put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))) }
            } }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(c: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) { remoteTrack(id, r?.track()) }
            override fun onTrack(t: RtpTransceiver?) { remoteTrack(id, t?.receiver?.track()) }
        }))
        val link = Link(connection); links[id] = link
        audio?.let { connection.addTrack(it, listOf("watch")) }
        video?.let { link.videoSender = connection.addTrack(it, listOf("watch")) }
        connection.setBitrate(null, 150_000, 250_000)
        applyVideoPolicy(link)
        return link
    }
    private fun applyVideoPolicy(link: Link) {
        val sender = link.videoSender ?: return
        val parameters = sender.parameters
        parameters.encodings.forEach { it.maxBitrateBps = 200_000; it.maxFramerate = 15 }
        if (parameters.encodings.isNotEmpty()) sender.setParameters(parameters)
    }
    private fun remoteTrack(id: String, track: MediaStreamTrack?) { scope.launch {
        if (stopped || id !in links) return@launch
        when (track) {
            is VideoTrack -> { track.setEnabled(true); bindTrack(id, track) }
            is AudioTrack -> { audioTracks[id] = track; track.setEnabled(true); track.setVolume(WatchRoomController.volume.toDouble()) }
        }
    } }
    private suspend fun offer(id: String, restart: Boolean = false) {
        val peer = links[id]?.peer ?: return
        if (peer.signalingState() != PeerConnection.SignalingState.STABLE) return
        val sent = watchRoomSignalAttempt {
            if (restart) peer.restartIce()
            val sdp = createSportsDescription(peer, true)
            setSportsDescription(peer, sdp, true)
            links[id]?.let(::applyVideoPolicy)
            send(id, "offer", JSONObject().put("sdp", JSONObject().put("type", "offer").put("sdp", sdp.description)))
        }
        if (!sent) WatchRoomController.status = "تعذر توصيل أحد المشاركين"
    }
    private suspend fun send(to: String, type: String, extra: JSONObject = JSONObject()) {
        val state = room ?: return
        val signal = JSONObject(extra.toString()).put("type", type).put("to", to).put("signalId", UUID.randomUUID().toString())
        for (attempt in 0..2) try {
            request(JSONObject().put("action", "signal").put("code", state.code).put("peerId", state.myPeerId).put("signal", signal)); return
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (attempt == 2) throw error else delay(350) }
    }
    fun enqueue(packet: JSONObject) { scope.launch { receive(packet) } }
    private suspend fun receive(packet: JSONObject, authoritativeRoster: Boolean = false): Boolean = signals.withLock {
        val state = room ?: return@withLock false
        if (packet.optString("code") != state.code || packet.optString("to") != state.myPeerId) return@withLock true
        val id = packet.optString("signalId")
        val from = packet.optString("from")
        // WebSocket can beat the roster poll or local media initialization; the queued HTTP copy will retry it.
        if (factory == null) return@withLock false
        if (state.members.none { it.peerId == from && it.peerId != state.myPeerId }) return@withLock authoritativeRoster
        if (id in seen) return@withLock true
        val link = links[from] ?: createPeer(from)
        val processed = watchRoomSignalAttempt { when (packet.optString("type")) {
            "offer" -> if (from < state.myPeerId) {
                val sdp = packet.getJSONObject("sdp")
                setSportsDescription(link.peer, SessionDescription(SessionDescription.Type.OFFER, sdp.getString("sdp")), false)
                val answer = createSportsDescription(link.peer, false)
                setSportsDescription(link.peer, answer, true)
                applyVideoPolicy(link)
                send(from, "answer", JSONObject().put("sdp", JSONObject().put("type", "answer").put("sdp", answer.description)))
                flushIce(link)
            }
            "answer" -> if (link.peer.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                setSportsDescription(link.peer, SessionDescription(SessionDescription.Type.ANSWER, packet.getJSONObject("sdp").getString("sdp")), false)
                flushIce(link)
            }
            "ice" -> {
                val c = packet.getJSONObject("candidate")
                val candidate = IceCandidate(c.optString("sdpMid").takeUnless { it == "null" }, c.getInt("sdpMLineIndex"), c.getString("candidate"))
                if (link.peer.remoteDescription != null) link.peer.addIceCandidate(candidate)
                else if (link.ice.size < 128) link.ice.add(candidate)
            }
            "restart" -> if (state.myPeerId < from) offer(from, true)
        } }
        if (!processed) { WatchRoomController.status = "جارٍ استعادة اتصال أحد المشاركين"; return@withLock false }
        seen.add(id)
        while (seen.size > 512) seen.remove(seen.first())
        true
    }
    private fun flushIce(link: Link) { link.ice.forEach { link.peer.addIceCandidate(it) }; link.ice.clear() }
    private fun bindTrack(id: String, track: VideoTrack) {
        val old = tracks.put(id, track)
        if (old === track) return
        renderers.entries.filter { it.value.first == id }.forEach { entry ->
            runCatching { entry.value.second?.removeSink(entry.key) }
            track.addSink(entry.key); entry.setValue(id to track)
        }
    }
    fun attach(id: String, renderer: SurfaceViewRenderer) {
        if (renderer in renderers || stopped) return
        renderer.init(egl.eglBaseContext, null); renderer.setEnableHardwareScaler(true)
        renderer.setMirror(id == room?.myPeerId); renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        val track = tracks[id]; renderers[renderer] = id to track; track?.addSink(renderer)
    }
    fun detach(renderer: SurfaceViewRenderer) {
        val binding = renderers.remove(renderer) ?: return
        runCatching { binding.second?.removeSink(renderer) }; runCatching { renderer.release() }
    }
    fun mute(value: Boolean) { audio?.setEnabled(!value) }
    fun volume(value: Float) { audioTracks.values.forEach { runCatching { it.setVolume(value.toDouble()) } } }
    fun camera(off: Boolean) {
        cameraOff = off; video?.setEnabled(!off)
        scope.launch(Dispatchers.IO) { cameraLock.withLock {
            if (stopped) return@withLock
            runCatching {
                if (cameraOff && capturing) { camera?.stopCapture(); capturing = false }
                if (!cameraOff && !capturing) { camera?.startCapture(480, 270, 15); capturing = true }
            }
        } }
    }
    fun switchCamera() { camera?.switchCamera(null) }
    private fun closePeer(id: String) {
        renderers.filterValues { it.first == id }.keys.toList().forEach(::detach)
        tracks.remove(id); audioTracks.remove(id)
        links.remove(id)?.peer?.let { runCatching { it.close(); it.dispose() } }
    }
    fun stop(released: () -> Unit = {}) {
        if (stopped) return
        stopped = true; scope.cancel()
        renderers.keys.toList().forEach(::detach)
        links.keys.toList().forEach(::closePeer)
        audio?.setEnabled(false); video?.setEnabled(false)
        CoroutineScope(Dispatchers.IO).launch {
            cameraLock.withLock {
                runCatching { camera?.stopCapture() }; runCatching { camera?.dispose() }
                runCatching { video?.dispose(); audio?.dispose(); videoSource?.dispose(); audioSource?.dispose() }
                runCatching { helper?.dispose(); factory?.dispose() }
            }
            withContext(Dispatchers.Main) { audioRoute.close(); runCatching { egl.release() }; released() }
        }
        CoroutineScope(Dispatchers.IO).launch { withTimeoutOrNull(3000) { runCatching { request(JSONObject().put("action", "leave")) } } }
    }
}
