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
check("Video, calls, messenger, profiles preserved",all((ROOT/(android+x)).is_file() for x in ["NativeCallScreen.kt","MessengerChat.kt","UserProfileScreen.kt","AttaExpandableVideo.kt","ProfileMedia.kt"]) and (ROOT/"android-native/app/src/main/assets/atta-call.html").is_file())
check("Cloudflare deployment paths", 'main = "src/worker.js"' in read('cloudflare-site/wrangler.toml') and 'directory = "./public"' in read('cloudflare-site/wrangler.toml'))
check("Worker core routes preserved",all(x in worker for x in ['/api/google-login','/api/friend-request','/api/conversation','/api/youtube','/api/call-signal','/ws']))
check("Version declared", bool(re.search(r'versionName\s*=\s*"\d+\.\d+\.\d+"',gradle)) and bool(re.search(r'versionCode\s*=\s*\d+',gradle)))
check("No legacy root Android source copies", not any((ROOT/p).exists() for p in ["MainActivity.kt","GoogleNativeSignIn.kt","AndroidManifest.xml","build.gradle.kts"]))
check("One shared website YouTube player", main.count("fun YouTubePlayer(")==1 and "YouTubePlayer(youtubeId)" in main and "native-youtube-player.html?id=$safeId" in main and "attaYouTubeHtml" not in main and "www.youtube.com/embed/" not in main and (ROOT/"cloudflare-site/public/youtube-player-shared.js").is_file() and "AttaYouTubeEmbed.create" in read("cloudflare-site/public/youtube-section.js") and "AttaYouTubeEmbed.create" in read("cloudflare-site/public/native-youtube-player.html"))
check("No unresolved merge markers", not any(re.match(r"^(<<<<<<< |=======\s*$|>>>>>>> )", line) for f in (ROOT/"android-native/app/src/main/java").rglob("*.kt") for line in f.read_text(encoding="utf-8").splitlines()))
check("PushCall visibility compatible", "internal var incomingPush by mutableStateOf<PushCall?>" in main)
check("Native Google bridge removed", not (ROOT/(android+"GoogleWebBridge.kt")).exists())
check("Official version 1.0.53", 'versionCode = 153' in gradle and 'versionName = "1.0.53"' in gradle)
check("Four additional screens and scrollable navigation", all(x in main for x in ['KnowledgeScreen(tab', 'FootballScreen(']) and all(x in read(android+"PremiumChrome.kt") for x in ['"القرآن"', '"الأحاديث"', '"الأذكار"', '"الرياضة"', '.horizontalScroll(scroll)']) and (ROOT/(android+"AlattaKnowledgeScreens.kt")).is_file())
check("Background FCM integration declared", all((ROOT/(android+x)).is_file() for x in ['AttaPush.kt']) and 'FIREBASE_SERVICE_ACCOUNT_JSON' in worker and '/api/push/register' in worker and 'ALATTA_FCM_APP_ID' in workflow)
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
if fails:raise SystemExit(f"FAIL: {len(fails)} checks failed")
print('PASS: static repository check complete. APK build/device tests are still required.')
