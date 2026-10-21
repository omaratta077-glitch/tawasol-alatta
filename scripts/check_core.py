#!/usr/bin/env python3
"""Source-only safety checks. Does not build, sign, or deploy."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import re
ROOT=Path(__file__).resolve().parent.parent
fails=[]
def check(name,ok):
    print(("PASS" if ok else "FAIL")+": "+name)
    if not ok:fails.append(name)
def read(rel):return (ROOT/rel).read_text(encoding="utf-8")
android="android-native/app/src/main/java/com/alatta/nativeapp/"
gradle=read("android-native/app/build.gradle.kts")
workflow=read(".github/workflows/build-native-release.yml")
worker=read("cloudflare-site/src/worker.js")
main=read(android+"MainActivity.kt")
check("Correct official package", 'applicationId = "com.alatta.nativeapp"' in gradle)
check("Only official Android source", not (ROOT/"android-app").exists() and not (ROOT/"android-studio").exists())
check("Release signing configuration", all(x in gradle for x in ["officialRelease","ALATTA_KEYSTORE_PATH","ALATTA_KEYSTORE_PASSWORD","ALATTA_KEY_ALIAS","ALATTA_KEY_PASSWORD"]))
check("Four original GitHub signing secrets",all("secrets."+x in workflow for x in ["KEYSTORE_BASE64","KEYSTORE_PASSWORD","KEY_ALIAS","KEY_PASSWORD"]))
check("Signature SHA-1 safeguard", '62E90655FDAF3788706105CF4D998592CBBD8275' in workflow)
check("Official release builds android-native", 'working-directory: android-native' in workflow and 'assembleRelease' in workflow)
check("Native Google sign in", all(x in main for x in ["GoogleNativeSignIn.pickAccount","/api/google-login"]) and (ROOT/(android+"GoogleNativeSignIn.kt")).exists())
check("Video, calls, messenger, profiles preserved",all((ROOT/(android+x)).is_file() for x in ["NativeCallScreen.kt","MessengerChat.kt","UserProfileScreen.kt","AttaExpandableVideo.kt","ProfileMedia.kt"]) and (ROOT/(android+"NativeRtcCall.kt")).is_file())
check("Cloudflare deployment paths", 'main = "src/worker.js"' in read('cloudflare-site/wrangler.toml') and 'directory = "./public"' in read('cloudflare-site/wrangler.toml'))
check("Cloudflare PBKDF2 iteration cap for Google signup", bool(re.search(r'const\s+KDF_ITERATIONS\s*=\s*100000\s*;', worker)) and
      'pbkdf2:${KDF_ITERATIONS}' in worker)
check("Worker core routes preserved",all(x in worker for x in ['/api/google-login','/api/friend-request','/api/conversation','/api/youtube','/api/call-signal','/ws']))
check("Version declared", bool(re.search(r'versionName\s*=\s*"\d+\.\d+\.\d+"',gradle)) and bool(re.search(r'versionCode\s*=\s*\d+',gradle)))
check("Exactly one Android versionCode and versionName",
      len(re.findall(r'^\s*versionCode\s*=', gradle, re.M)) == 1 and
      len(re.findall(r'^\s*versionName\s*=', gradle, re.M)) == 1)
check("No legacy root Android source copies", not any((ROOT/p).exists() for p in ["MainActivity.kt","GoogleNativeSignIn.kt","AndroidManifest.xml","build.gradle.kts"]))
check("One actual site YouTube player, no Android duplication", main.count("fun YouTubePlayer(")==1 and "YouTubePlayer(youtubeId)" in main and 'loadUrl("$BASE/")' in main and 'atta-site-youtube-bridge.js' in main and 'document.body.replaceChildren(host)' not in main and 'new YT.Player(' not in main and 'attaYouTubeHtml' not in main and 'www.youtube.com/embed/' not in main and 'syncAttaYouTube' not in gradle and (ROOT/'android-native/app/src/main/assets/atta-site-youtube-bridge.js').is_file() and 'AttaYouTubeEmbed.create' in read('cloudflare-site/public/youtube-section.js') and 'global.AttaYouTubeEmbed=' in read('cloudflare-site/public/youtube-player-shared.js'))
check("One compact header search and no duplicate friend search", main.count('AttaCompactSearch(')==2 and 'SectionTitle("البحث وإضافة صديق")' not in main and 'OutlinedTextField(search, { search = it }, label = { Text("ابحث بالاسم أو اسم المستخدم") }' not in main)
# Git conflict markers can break the Worker, Gradle and JS tests as well as Kotlin.
# Inspect all project source/config files and report the exact location if found.
SOURCE_EXTENSIONS = {".kt", ".kts", ".js", ".mjs", ".py", ".html", ".css", ".xml", ".yml", ".yaml", ".toml", ".properties"}
merge_conflicts = []
for source_file in ROOT.rglob("*"):
    if source_file.is_file() and source_file.suffix in SOURCE_EXTENSIONS and ".git" not in source_file.parts:
        for line_number, line in enumerate(source_file.read_text(encoding="utf-8").splitlines(), 1):
            if re.match(r"^(?:<<<<<<<(?: |$)|=======$|>>>>>>>(?: |$))", line):
                merge_conflicts.append(f"{source_file.relative_to(ROOT)}:{line_number}")
check("No unresolved merge markers", not merge_conflicts)
for location in merge_conflicts:
    print("CONFLICT: " + location)
check("PushCall visibility compatible", "internal var incomingPush by mutableStateOf<PushCall?>" in main)
# Protect the cross-file foreground ringtone contract. Partial uploads must
# include MainActivity.kt, NativeCallScreen.kt, and AttaPush.kt from one version.
ring_screen=read(android+"NativeCallScreen.kt")
push_source=read(android+"AttaPush.kt")
check("Incoming call ringtone passes callId", 
      "IncomingCallRingtone(invitation.callId)" in main and
      "internal fun IncomingCallRingtone(callId: String)" in ring_screen and
      not re.search(r"IncomingCallRingtone\(\s*\)", main))
check("Foreground ring state declared in AttaPush", 
      "@Volatile var foregroundRingCallId" in push_source and
      "AttaPush.foregroundRingCallId = callId" in ring_screen and
      "AttaPush.foregroundRingCallId == callId" in ring_screen)

check("Native Google bridge removed", not (ROOT/(android+"GoogleWebBridge.kt")).exists())
check("Official version 1.0.138", 'versionCode = 238' in gradle and 'versionName = "1.0.138"' in gradle)
check("Four additional screens and scrollable navigation", all(x in main for x in ['QuranReaderScreen(', 'KnowledgeScreen(tab', 'FootballScreen(']) and all(x in read(android+"PremiumChrome.kt") for x in ['"القرآن"', '"الأحاديث"', '"الأذكار"', '"الرياضة"', '.horizontalScroll(scroll)']) and (ROOT/(android+"AlattaKnowledgeScreens.kt")).is_file())
check("Quran-first categories and click-to-play preview", all(x in main for x in [
    'var youtubeCategory by remember { mutableStateOf("قرآن") }',
    '"أحاديث" -> "الشيخ سمير مصطفى دروس ومحاضرات"',
    '"الأذكار" -> "أذكار الصباح والمساء حصن المسلم"',
    '"رياضة" -> "كرة القدم ملخص مباريات"',
    '"أخبار" -> "قناة الجزيرة أخبار اليوم"',
    'youtubeListState.animateScrollToItem(0)',
    'if (youtubePlayRequested)',
    'GoldButton("▶ تشغيل الفيديو")',
    'youtubePlayRequested = false'])
    and '⛶ ملء الشاشة' not in main and 'فتح في YouTube' not in main
    and 'تلاوة القرآن الكريم' in worker and 'data-q="تلاوة القرآن الكريم"' in read('cloudflare-site/public/youtube-section.js'))
check("Background FCM integration declared", all((ROOT/(android+x)).is_file() for x in ['AttaPush.kt']) and 'FIREBASE_SERVICE_ACCOUNT_JSON' in worker and '/api/push/register' in worker and 'ALATTA_FCM_APP_ID' in workflow)
check("Notification Intent.data does not shadow Firebase payload", 'this.data = Uri.parse("alatta://notification/${Uri.encode(eventId)}")' in read(android+"AttaPush.kt"))
check("Social, chat and call FCM routing", 'scheduleUserPush' in worker and
      'deliverUserPush' in worker and 'AttaPush.receive(this, message.data)' in read(android+'AttaPush.kt') and 'notifyActivity(context, d)' in read(android+'AttaPush.kt') and
      'ACTION_OPEN_NOTIFICATION' in main)
check("Site/Android keep screen on without changing player", 'FLAG_KEEP_SCREEN_ON' in main and
      'youtube-screen-awake.js' in read('cloudflare-site/public/index.html') and
      (ROOT/'cloudflare-site/public/youtube-screen-awake.js').is_file())
check("No old patch workflows",not list((ROOT/'.github/workflows').glob('apply-*.yml')) and not list((ROOT/'.github/workflows').glob('install-*.yml')))
xmls=list((ROOT/'android-native').rglob('*.xml'))
errors=[]
for f in xmls:
    try:ET.parse(f)
    except ET.ParseError as e:errors.append(f'{f}: {e}')
check(f"Android XML parses ({len(xmls)} files)",not errors)
for x in errors:print(x)
js=list((ROOT/'cloudflare-site').rglob('*.js'))
failjs=[]
for f in js:
    p=subprocess.run(['node','--check',str(f)],capture_output=True,text=True)
    if p.returncode:failjs.append((f.as_posix(),p.stderr.strip()[:150]))
check(f"JavaScript syntax ({len(js)} files)",not failjs)
for x in failjs:print(x)


# Exactly one native WebRTC media implementation replaces the old call WebView.
video_native=read(android+"NativeCallScreen.kt")
video_engine=read(android+"NativeRtcCall.kt")
check("Native WebRTC camera preview and remote video without call WebView",
      all(k in video_engine for k in ["PeerConnectionFactory", "Camera2Enumerator", "Camera1Enumerator", "SurfaceTextureHelper", "createVideoTrack", "createPeerConnection", "addSink", "createOffer", "createAnswer", "call-accept"]) and
      'SurfaceViewRenderer' in video_native and
      'AndroidView(factory = { localRenderer }' in video_native and
      'AndroidView(factory = { remoteRenderer }' in video_native and
      'io.github.webrtc-sdk:android:' in gradle and
      not (ROOT/'android-native/app/src/main/assets/atta-call.html').exists() and
      'WebView(' not in video_native and 'WebView(' not in video_engine)
check("FCM reserved data key converted safely", "fcmData.sender=String(fcmData.from??'')" in worker and "delete fcmData.from" in worker and "data:fcmData" in worker and 'data["sender"] ?: data["from"]' in push_source and 'd["sender"] ?: d["from"]' in push_source)

# An FCM notification+data packet is displayed by Android itself when the app
# is asleep; the launcher Intent then receives the custom payload on tap.
check("OS-displayed background call notification and safe call controls",
      "android.notification={" in worker and
      "channel_id:'alatta_incoming_call_v2'" in worker and
      'lastCallPushResult:' in worker and
      'intent.getStringExtra("type") == "call-request"' in main and
      'manager.cancel("alatta-call-$callId", 0)' in push_source and
      'SurfaceViewRenderer' in video_native)

# Regression guard: FCM / WebSocket / HTTP can deliver the same invitation.
accept_fn=main.split('fun acceptIncomingInvite(invitation: NativeCallSession)',1)[-1].split('fun sendSelectedChatMedia(',1)[0]
request_fn=main.split('fun handleIncomingCallEvent(signal: JSONObject)',1)[-1].split('val activity = ctx as? MainActivity',1)[0]
check("Same call ID is never busy after answering",
      'fun acceptIncomingInvite(invitation: NativeCallSession)' in main and
      'knownCalls.add(id)' in accept_fn and
      accept_fn.rfind('knownCalls.add(id)') < accept_fn.rfind('openCall(') and
      'if (activeCall?.callId == callId ||' in request_fn and
      request_fn.index('if (activeCall?.callId == callId ||') < request_fn.index('sendCallResponse(invite, "busy")') and
      'onClick = { acceptIncomingInvite(invitation) }' in main and
      'acceptIncomingInvite(session)' in main)
check("Accepted FCM call is not re-notified and stale dialogs expire",
      '@Volatile var activeCallId = ""' in push_source and
      'if (activeCallId == call.callId) return' in push_source and
      'AttaPush.activeCallId = callId' in main and
      'LaunchedEffect(incomingCall?.callId)' in main and
      'delay(45000L)' in main and
      'if (AttaPush.activeCallId == session.callId) AttaPush.activeCallId = ""' in main)

# Interactive Quran has one source of truth; never duplicate a second reader.
quran_reader = ROOT / "android-native/app/src/main/assets/atta-quran.html"
quran_kotlin = ROOT / (android + "AttaQuranReader.kt")
check("One full Quran reader with selectable verses and system back",
      quran_reader.is_file() and quran_kotlin.is_file() and
      'QuranReaderScreen(Modifier.weight(1f),' in main and
      'if (tab != "الرسائل" || chatWith.isBlank()) PremiumBottomNav' in main and
      all(text in quran_reader.read_text(encoding="utf-8") for text in [
          'mushaf/index.json', '/mushaf/', 'jumpToSegment', 'ar.muyassar',
          'ar.minshawi', 'ar.abdulbasit', 'ar.husary', 'playSurah', 'playHere']))

# Native offline reader replaces the former intercepted WebView implementation.
quran_source = quran_kotlin.read_text(encoding="utf-8")
check("Quran uses one Native Android SVG renderer without WebView",
      'internal fun QuranReaderScreen(' in quran_source and
      'MushafPageView(context, page, changePage,' in quran_source and
      'SVG.getFromInputStream' in quran_source and
      'GZIPInputStream' not in quran_source and
      'WebView(' not in quran_source and
      'loadDataWithBaseURL(' not in quran_source)
check("Quran downloadable pages and compact native controls",
      all((ROOT / f"android-native/app/src/main/assets/mushaf/pages/{p:03d}.svg.gz").is_file() for p in range(1,605)) and
      (ROOT / 'android-native/app/src/main/assets/mushaf/index.json').is_file() and
      'MushafPageStore.openPage(context,page,allowNetwork)' in quran_source and 'GZIPInputStream' not in quran_source and
      all(x in quran_source for x in ['فهرس السور', 'BackHandler', 'prefs.edit().putInt("page"',
                                      'height(20.dp)', 'Text("صفحة $page"', 'Text("الحزب $hizb"',
                                      'onFling(', 'navigate(page + 1)', 'navigate(page - 1)']) and
      'Text("ملاحظاتي"' not in quran_source)
if fails:raise SystemExit(f"FAIL: {len(fails)} checks failed")
print('PASS: static repository check complete. APK build/device tests are still required.')
