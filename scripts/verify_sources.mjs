import fs from 'node:fs';
import assert from 'node:assert/strict';
const native=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt','utf8');
const worker=fs.readFileSync('cloudflare-site/src/worker.js','utf8');
const html=fs.readFileSync('cloudflare-site/public/index.html','utf8');
assert.equal((native.match(/fun YouTubePlayer\(/g)||[]).length,1,'one canonical YouTubePlayer');
assert.equal((native.match(/YouTubePlayer\(youtubeId,\s*onMiniDrag = onMiniDrag\)/g)||[]).length,1,'screen calls canonical player with its mini-drag callback');
assert.ok(!native.includes('AlattaFixedYouTubePlayer') && !native.includes('attaYouTubeHtml'),'no duplicate native player');
assert.ok(native.includes('playerContent(videoId)') && !native.includes('youtubeId = videoId; youtubePlayRequested = true'),
  'post videos use the canonical player inline without selecting the floating host');
assert.ok(native.includes('loadUrl("$BASE/")') && native.includes('atta-site-youtube-bridge.js') && !native.includes('document.body.replaceChildren(host)'),'Android drives the actual site player without replacing the website document');
assert.ok(!native.includes('raw.contains("username", ignoreCase = true)'),'no blanket mapping of login failures to username-taken');
const roleScript=fs.readFileSync('cloudflare-site/public/app.js','utf8');
const ownerAccess=fs.readFileSync('cloudflare-site/src/worker.js','utf8');
assert.ok(native.includes('if (section != "حول") editProfileDetails = false') &&
  native.includes('if (ownProfileFilter == "حول") {') &&
  native.includes('Text(if (editProfileDetails) "إلغاء" else "تعديل الملف الشخصي"') &&
  !native.includes('ownProfileFilter == "حول" || editProfileDetails'),
  'Profile edit only appears inside About');
assert.ok(native.includes('if (destination == "القائمة" && tab != "القائمة")') &&
  native.includes('if (adminRole == "super_admin" || adminRole == "moderator")') &&
  ownerAccess.includes('admin-moderators:v2') &&
  ownerAccess.includes('make_moderator') && ownerAccess.includes('remove_moderator'),
  'Owner or specifically granted moderator only');
assert.ok(roleScript.includes('user={...user,role:"user"};') &&
  roleScript.includes('const canAdmin=["super_admin","moderator"].includes(me?.role);'),
  'Web does not trust stale administrator roles');
const adminWeb=fs.readFileSync('cloudflare-site/public/app.js','utf8');
assert.ok(native.includes('if (destination == "الإدارة" && adminRole !in listOf("super_admin", "moderator")) return') &&
  native.includes('AdminOwnerHero(displayName, myAvatar') &&
  !native.includes('تحديث صلاحية المدير') &&
  !native.includes('صلاحية الإدارة غير مفعّلة لهذا الحساب على الخادم'),
  'members do not see role diagnostics or enter owner dashboard');
assert.ok(adminWeb.includes('if(pageId==="adminPage" && !["super_admin","moderator"].includes(me?.role))return false;'),
  'non-owners cannot open the web admin screen');
const shine = fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AttaGoldShine.kt', 'utf8');
const auth = fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/PremiumAuth.kt','utf8');
const chrome = fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/PremiumChrome.kt','utf8');
const chat = fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/MessengerChat.kt','utf8');
const css = fs.readFileSync('cloudflare-site/public/style.css','utf8');
assert.ok(css.includes('@keyframes attaGoldShine') && css.includes('animation:attaGoldShine 4.6s ease-in-out infinite;'),
    'website is still the single reference animation');
assert.ok(shine.includes('durationMillis = 4600') && shine.includes('2.6f at 2530') &&
    shine.includes('2.6f at 4600') && shine.includes('CubicBezierEasing(.42f, 0f, .58f, 1f)'),
    'native gold sweep uses the website 4.6-second CSS ease-in-out with 55% travel');
assert.ok(shine.includes('size.width * .24f') && shine.includes('size.height * 1.40f') &&
    shine.includes('Color.White.copy(alpha = .48f)') && shine.includes('tan(-22 degrees)'),
    'native shine strip matches website width, height, opacity and slant');
assert.ok(auth.includes('.attaGoldShine(enabled)') && !auth.includes('button-glimmer'),
    'auth shares the same effect instead of legacy 3-second shine');
assert.ok((native.match(/\.attaGoldShine\(enabled\)/g)||[]).length === 2 &&
    chrome.includes('.attaGoldShine(selected)') &&
    chat.includes('.attaGoldShine(canSend)'),
    'publish, gold CTA, selected nav, and message send reuse the canonical shine');
const hadithScreen=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/HadithLibraryScreen.kt','utf8');
const knowledge=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AlattaKnowledgeScreens.kt','utf8');
assert.ok(hadithScreen.includes('onSharePost(sharedText)') &&
    hadithScreen.includes('مشاركة كمنشور في تواصل العطا'),
    'Each Hadith opens native post composer with the entire text and reference');
assert.ok(knowledge.includes('onShareHadithPost') &&
    native.includes('composeHadithPost(draft, hadith)') &&
    native.includes('openHomeComposer()'),
    'Hadith share uses the existing Home post flow, without a separate publisher');
const azkarScreen=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AzkarPrayerScreen.kt','utf8');
const azkarCatalog=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AzkarCatalog.kt','utf8');
const chromeUi=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/PremiumChrome.kt','utf8');
const navItems=chromeUi.split('val destinations = listOf(',2)[1].split('val scroll =',1)[0];
assert.ok(navItems.includes('Triple("ريلز", "video", "ريلز")') &&
  !navItems.includes('Triple("الرسائل"') &&
  chromeUi.includes('PremiumTopItem("message-circle", "الرسائل"') &&
  native.includes('"ريلز" -> {') && native.includes('mixedPostReels(posts)') &&
  native.includes('includeYoutubePosts = true'),
  'Reels replaces only bottom-bar messages; top-bar Messenger and shared reels viewer remain');

const videoViewer=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AttaExpandableVideo.kt','utf8');
assert.ok(videoViewer.includes('val reelsMode = includeYoutubePosts && live == null') &&
  videoViewer.includes('Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f)') &&
  videoViewer.includes('Column(Modifier.align(AbsoluteAlignment.TopRight)') &&
  !videoViewer.includes('AbsoluteAlignment.BottomRight') &&
  videoViewer.includes('if (reelsMode) AspectRatioFrameLayout.RESIZE_MODE_ZOOM') &&
  videoViewer.includes('else AspectRatioFrameLayout.RESIZE_MODE_FIT') &&
  videoViewer.includes('Modifier.align(AbsoluteAlignment.TopLeft)') &&
  !videoViewer.includes('Text("ريلز •'),
  'Reels fill vertical display without a top counter; live playback stays FIT');
assert.ok(azkarScreen.includes('local.getInt("font_size", 23)') &&
  azkarScreen.includes('textAlign = TextAlign.Center') &&
  azkarScreen.includes('(fontSize * 1.75f).sp') &&
  azkarScreen.includes('Modifier.wrapContentWidth().clip(RoundedCornerShape(12.dp))'),
  'Dhikr has restored classic text alignment and a content-width title badge');
assert.ok(!azkarScreen.includes('مشاركة كمنشور داخل التطبيق') &&
  !azkarScreen.includes('بحث في الأذكار') && !azkarScreen.includes('onQueryChange') &&
  native.includes('tab != "الأذكار" &&') &&
  azkarScreen.includes('TextButton(onClick = { shareChoice = group to entry })'),
  'Azkar screen has no search UI and keeps in-app sharing in the compact icon');
for (const groupId of ['morning', 'evening']) {
  const groupStart=azkarCatalog.indexOf(`DhikrGroup("${groupId}"`);
  const nextGroup=azkarCatalog.indexOf('DhikrGroup(', groupStart+10);
  const groupBlock=azkarCatalog.slice(groupStart, nextGroup < 0 ? undefined : nextGroup);
  assert.ok(groupBlock.includes('dailyAdhkar('), `${groupId} uses the daily catalog`);
}
assert.ok(azkarCatalog.includes('fatiha, ayatKursi, baqarahEnding') &&
  azkarCatalog.includes('add(29, salawat)') && !azkarCatalog.includes('Dhikr("salawat-ten"'),
  'Daily groups keep their verses and display the unique general salawat in position 30');
const hadith=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/HadithLibrary.kt','utf8');
assert.ok(hadith.includes('verifiedCopy(target, expectedBytes, hash)'), 'Hadith copy verified by actual SHA-256');
assert.ok(!hadith.includes('PRAGMA quick_check", null') && !hadith.includes('require(db.version == 1)'),
  'avoid vendor-specific SQLite PRAGMA checks on app initialization');
assert.ok(hadith.includes('SQLiteDatabase.OPEN_READONLY') &&
  hadith.includes('"BOOKS_QUERY"') && hadith.includes('"SQLITE_OPEN"'),
  'read-only offline DB with useful stage diagnostics');
assert.ok(native.includes('error = loadingFailureMessage(e)'),'native errors shown without misleading remapping');
const transport=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AttaHttp.kt','utf8');
assert.ok(transport.includes('else -> failure.message') && !transport.includes('USERNAME_TAKEN'),'network recovery preserves real account error messages');
assert.ok(!worker.includes('AL_ATTA_DOWNLOAD_BUTTON_SCRIPT'),'no injected download button');
assert.ok(!fs.existsSync('android-app')&&!fs.existsSync('android-studio'),'no obsolete Android build roots');
assert.ok(!fs.existsSync('cloudflare-site/index.html'),'no shadow index');
assert.ok(!fs.existsSync('cloudflare-site/style.css'),'no shadow stylesheet');
assert.ok(!fs.existsSync('android-native/app/src/main/java/com/alatta/nativeapp/GoogleWebBridge.kt'),'no unused OAuth bridge');
assert.ok(html.includes('tawasol-alatta-latest.apk'),'canonical release link remains in HTML');
assert.ok(fs.existsSync('.github/workflows/verify-source.yml'),'official source CI available');
console.log('PASS: source organization, one YouTube player, single APK button, CI');
