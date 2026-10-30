package com.alatta.nativeapp

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

/** صوت وصورة مشفّران فعليًا عبر ICE محلي على الجهاز وباتجاهَي عرض؛ بلا خدمة خارجية أو كاميرا. */
class WatchRtcDeviceTest {
    @Test fun negotiatedPeersDeliverAudioAndVideoInBothDirections() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        NativeRtcCall.initializeRtc(context)
        val egl = EglBase.create()
        val route = WatchAudioRoute(context).also { it.start() }
        val module = watchAudioModule(context)
        val factory = NativeRtcCall.mediaFactory(context, egl, module)
        module.release()
        try { for (initiator in 0..1) exercise(factory, initiator) }
        finally { factory.dispose(); route.close(); egl.release() }
    }

    private suspend fun receivedAudio(peer: PeerConnection): Boolean = suspendCancellableCoroutine { continuation ->
        peer.getStats { report ->
            val received = report.statsMap.values.any { stat ->
                stat.type == "inbound-rtp" && (stat.members["kind"] == "audio" || stat.members["mediaType"] == "audio") &&
                    ((stat.members["bytesReceived"] as? Number)?.toLong() ?: 0L) > 0L
            }
            if (continuation.isActive) continuation.resume(received)
        }
    }
    private suspend fun exercise(factory: PeerConnectionFactory, initiator: Int) {
        val frames = Array(2) { CompletableDeferred<Unit>() }
        val audioTracks = Array(2) { CompletableDeferred<Unit>() }
        val candidates = Array(2) { ConcurrentLinkedQueue<IceCandidate>() }
        val peers = arrayOfNulls<PeerConnection>(2)
        val sources = Array(2) { factory.createVideoSource(false) }
        val videos = Array(2) { factory.createVideoTrack("watch-test-video-$it", sources[it]) }
        val audioSource = factory.createAudioSource(watchMicrophoneConstraints())
        val audio = Array(2) { factory.createAudioTrack("watch-test-audio-$it", audioSource) }
        fun observer(index: Int) = object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) { if (c != null) candidates[1 - index].add(c) }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(c: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            private fun track(value: MediaStreamTrack?) {
                if (value is VideoTrack) value.addSink { frame ->
                    if (frame.buffer.width > 0 && frame.buffer.height > 0) frames[index].complete(Unit)
                }
                if (value is AudioTrack) { value.setEnabled(true); audioTracks[index].complete(Unit) }
            }
            override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) { track(r?.track()) }
            override fun onTrack(t: RtpTransceiver?) { track(t?.receiver?.track()) }
        }
        try {
            repeat(2) { index ->
                peers[index] = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }, observer(index))
                requireNotNull(peers[index]).addTrack(audio[index], listOf("watch-test-$index"))
                requireNotNull(peers[index]).addTrack(videos[index], listOf("watch-test-$index"))
            }
            val sender = requireNotNull(peers[initiator]); val receiver = requireNotNull(peers[1 - initiator])
            withTimeout(30000) {
                val offer = createSportsDescription(sender, true)
                setSportsDescription(sender, offer, true); setSportsDescription(receiver, offer, false)
                val answer = createSportsDescription(receiver, false)
                setSportsDescription(receiver, answer, true); setSportsDescription(sender, answer, false)
                sources.forEach { it.capturerObserver.onCapturerStarted(true) }
                var audioReceived = false
                while (!frames.all { it.isCompleted } || !audioTracks.all { it.isCompleted } || !audioReceived) {
                    repeat(2) { index -> while (true) {
                        val candidate = candidates[index].poll() ?: break
                        peers[index]?.addIceCandidate(candidate)
                    } }
                    for (source in sources) {
                        val frame = VideoFrame(JavaI420Buffer.allocate(160, 90), 0, System.nanoTime())
                        source.capturerObserver.onFrameCaptured(frame); frame.release()
                    }
                    audioReceived = receivedAudio(sender) && receivedAudio(receiver)
                    delay(100)
                }
                assertTrue("Both devices receive decoded video regardless of who offers", frames.all { it.isCompleted })
                assertTrue("Both devices receive audio RTP, not only an audio track declaration", audioReceived)
            }
        } finally {
            sources.forEach { it.capturerObserver.onCapturerStopped() }
            peers.forEach { it?.close(); it?.dispose() }
            videos.forEach { it.dispose() }; audio.forEach { it.dispose() }
            sources.forEach { it.dispose() }; audioSource.dispose()
        }
    }
}
