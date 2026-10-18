import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const native=readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/NativeCallScreen.kt','utf8');
const html=readFileSync('android-native/app/src/main/assets/atta-call.html','utf8');
const main=readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt','utf8');
assert.ok(native.includes('val loadCallPage: (WebView) -> Unit'), 'keep one WebView loader');
assert.ok(native.includes('AndroidView(\n            factory = { webView }'), 'one stable WebView for entire session');
assert.equal((native.match(/AndroidView\(/g)||[]).length,1,'do not remount video view on minimize or restore');
<<<<<<< HEAD
assert.ok(native.includes('} else if (videoCall) Column'), 'native video controls survive a black WebView');
assert.ok(native.includes('.padding(top = 66.dp, bottom = 108.dp)'), 'WebView does not sit behind native video controls');
assert.ok(native.includes('fun minimizeCall()'), 'video HTML minimize button must invoke native minimize');
assert.ok(html.includes("document.documentElement.classList.add('native-host')"), 'video HTML contains a single media-only stage');
=======
assert.ok(native.includes('} else if (!videoCall) Column'), 'video screen does not create a Compose layer above WebView');
assert.ok(native.includes('fun minimizeCall()'), 'video HTML minimize button must invoke native minimize');
assert.ok(html.includes("if(cfg.type!=='video')document.documentElement.classList.add('native-host')"), 'video must use WebView own visible controls');
>>>>>>> a9e93afa597f2ea057d2bc14ce29616f60ca0c55
assert.ok(native.includes('fun videoDiagnostic(message: String)'), 'native video diagnostic bridge');
assert.ok(html.includes('localPreviewFrame') && html.includes('preview.play().catch(e=>') && html.includes('localCameraPlaceholder'), 'camera preview state shown');
assert.ok(html.includes('meta.presentedFrames>0') && html.includes('remoteFrameConfirmed'), 'remote presentation detection');
assert.ok(html.includes('لا تصل بيانات فيديو من الطرف الآخر'), 'diagnose received video vs decoded frames');
assert.ok(main.includes('GoogleNativeSignIn.pickAccount') && main.includes('atta-site-youtube-bridge.js'), 'Google and shared YouTube preserved');
console.log('PASS full video WebView without opaque Compose overlay and media diagnosis regression');

assert.ok(html.includes("facingMode:{ideal:'user'}") && html.includes('const watchLocalFrame='), 'site-like camera capture and continuous frame probe');

assert.ok(html.includes('id="localPip"') && html.includes("$('localPip').style.display=cfg.type"), 'camera PiP visible from call start');
assert.ok(html.includes('preview.srcObject=local;') && html.includes('pc.addTrack(track,local)'), 'one captured MediaStream used for local preview and transmission');
assert.ok(native.includes('request.origin.scheme == "https"') && native.includes('request.origin.host == "tawasol-alatta.omaratta077-4fd.workers.dev"'), 'camera grant restricted to trusted origin');
assert.ok(native.includes('ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)'), 'camera requires Android permission');
assert.ok(readFileSync('android-native/app/src/main/AndroidManifest.xml','utf8').includes('android:hardwareAccelerated="true"'), 'Android video composition explicitly hardware accelerated');
