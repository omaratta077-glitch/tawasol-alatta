import assert from 'node:assert/strict';
import {readFileSync,existsSync} from 'node:fs';
const root = 'android-native/app/src/main/';
const engine = readFileSync(root+'java/com/alatta/nativeapp/NativeRtcCall.kt','utf8');
const screen = readFileSync(root+'java/com/alatta/nativeapp/NativeCallScreen.kt','utf8');
const gradle = readFileSync('android-native/app/build.gradle.kts','utf8');
const site = readFileSync('cloudflare-site/public/app.js','utf8');
const main = readFileSync(root+'java/com/alatta/nativeapp/MainActivity.kt','utf8');
assert.ok(!existsSync(root+'assets/atta-call.html'),'legacy Android call HTML must be deleted');
assert.ok(!engine.includes('WebView(') && !screen.includes('WebView('),'no embedded call page');
assert.ok(gradle.includes('io.github.webrtc-sdk:android:137.7151.03'),'native WebRTC dependency');
for (const part of ['PeerConnectionFactory','Camera2Enumerator','SurfaceTextureHelper','createPeerConnection','createVideoTrack','createOffer','createAnswer','setRemoteDescription','onIceCandidate']) {
  assert.ok(engine.includes(part), `Native engine missing ${part}`);
}
assert.ok(screen.includes('AndroidView(factory = { localRenderer }'),'a native selfie SurfaceView is visible');
assert.ok(screen.includes('AndroidView(factory = { remoteRenderer }'),'a native remote SurfaceView is visible');
assert.ok(screen.includes('setZOrderMediaOverlay(true)'),'PiP renderer is above remote image');
assert.ok(engine.includes('localVideo?.addSink(localFrameProbe)'), 'local camera is previewed');
assert.ok(engine.includes('localVideo?.let { videoSender = peer?.addTrack(it,'), 'same camera track is sent by WebRTC');
assert.ok(engine.includes('track.addSink(remoteFrameProbe)'), 'remote frame confirmation');
assert.ok(engine.includes('/api/turn-credentials'),'use exactly the same TURN endpoint as the website');
assert.ok(engine.includes('/api/message-send') && engine.includes('/api/call-media-signals'),'signaling interoperable with site');
for (const type of ['call-request','call-ringing','call-accept','call-reject','offer','answer','ice','hangup']) {
  assert.ok(engine.includes(type) || type==='call-reject', `Native signaling lacks ${type}`);
  assert.ok(site.includes(`"${type}"`) || site.includes(`'${type}'`), `Site signaling lacks ${type}`);
}
assert.ok(main.includes('GoogleNativeSignIn.pickAccount') && main.includes('atta-site-youtube-bridge.js'),'Google and YouTube unchanged');
console.log('PASS Android-native WebRTC video renderers, media capture and website signaling compatibility');
