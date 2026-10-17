import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const native=readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/NativeCallScreen.kt','utf8');
const html=readFileSync('android-native/app/src/main/assets/atta-call.html','utf8');
const main=readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt','utf8');
assert.ok(native.includes('val loadCallPage: (WebView) -> Unit'), 'keep one WebView loader');
assert.ok(native.includes('AndroidView(\n            factory = { webView }'), 'one stable WebView for entire session');
assert.equal((native.match(/AndroidView\(/g)||[]).length,1,'do not remount video view on minimize or restore');
assert.ok(native.includes('if (videoCall) {\n                    // The sole video WebView'), 'video surface below transparent Compose stage');
assert.ok(native.includes('fun videoDiagnostic(message: String)'), 'native video diagnostic bridge');
assert.ok(html.includes('localPreviewFrame') && html.includes('preview.play().catch(e=>') && html.includes('localCameraPlaceholder'), 'camera preview state shown');
assert.ok(html.includes('meta.presentedFrames>0') && html.includes('remoteFrameConfirmed'), 'remote presentation detection');
assert.ok(html.includes('لا تصل بيانات فيديو من الطرف الآخر'), 'diagnose received video vs decoded frames');
assert.ok(main.includes('GoogleNativeSignIn.pickAccount') && main.includes('atta-site-youtube-bridge.js'), 'Google and shared YouTube preserved');
console.log('PASS native WebRTC video stage and media diagnosis regression');

assert.ok(html.includes("facingMode:{ideal:'user'}") && html.includes('const watchLocalFrame='), 'site-like camera capture and continuous frame probe');

assert.ok(html.includes('id="localPip"') && html.includes("$('localPip').style.display=cfg.type"), 'camera PiP visible from call start');
assert.ok(html.includes('preview.srcObject=local;') && html.includes('pc.addTrack(track,local)'), 'one captured MediaStream used for local preview and transmission');
assert.ok(native.includes('request.origin.scheme == "https"') && native.includes('request.origin.host == "tawasol-alatta.omaratta077-4fd.workers.dev"'), 'camera grant restricted to trusted origin');
assert.ok(native.includes('ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)'), 'camera requires Android permission');
assert.ok(readFileSync('android-native/app/src/main/AndroidManifest.xml','utf8').includes('android:hardwareAccelerated="true"'), 'Android video composition explicitly hardware accelerated');
