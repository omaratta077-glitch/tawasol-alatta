package com.alatta.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.*
import java.util.concurrent.ConcurrentLinkedQueue

/** Negotiates the shared native SDP/media stack over host ICE, without camera, microphone or a remote service. */
class WatchRtcDeviceTest {
    @Test fun negotiatedPeersDeliverAnEncodedVideoFrame() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NativeRtcCall.initializeRtc(context)
        val egl = EglBase.create()
        val factory = NativeRtcCall.mediaFactory(context, egl)
        val received = CompletableDeferred<Unit>()
        val candidates = Array(2) { ConcurrentLinkedQueue<IceCandidate>() }
        val peers = arrayOfNulls<PeerConnection>(2)
        val source = factory.createVideoSource(false)
        val track = factory.createVideoTrack("watch-test-video", source)
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
            private fun video(value: MediaStreamTrack?) {
                if (index == 1 && value is VideoTrack) value.addSink { frame ->
                    if (frame.buffer.width > 0 && frame.buffer.height > 0) received.complete(Unit)
                }
            }
            override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) { video(r?.track()) }
            override fun onTrack(t: RtpTransceiver?) { video(t?.receiver?.track()) }
        }
        try {
            repeat(2) { index -> peers[index] = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }, observer(index)) }
            val sender = requireNotNull(peers[0]); val receiver = requireNotNull(peers[1])
            sender.addTrack(track, listOf("watch-test"))
            withTimeout(30000) {
                val offer = createSportsDescription(sender, true)
                setSportsDescription(sender, offer, true); setSportsDescription(receiver, offer, false)
                val answer = createSportsDescription(receiver, false)
                setSportsDescription(receiver, answer, true); setSportsDescription(sender, answer, false)
                source.capturerObserver.onCapturerStarted(true)
                while (!received.isCompleted) {
                    repeat(2) { index -> while (true) {
                        val candidate = candidates[index].poll() ?: break
                        peers[index]?.addIceCandidate(candidate)
                    } }
                    val frame = VideoFrame(JavaI420Buffer.allocate(160, 90), 0, System.nanoTime())
                    source.capturerObserver.onFrameCaptured(frame); frame.release()
                    delay(100)
                }
                received.await()
            }
            assertTrue(received.isCompleted)
        } finally {
            source.capturerObserver.onCapturerStopped()
            peers.forEach { it?.close(); it?.dispose() }
            track.dispose(); source.dispose(); factory.dispose(); egl.release()
        }
    }
}
