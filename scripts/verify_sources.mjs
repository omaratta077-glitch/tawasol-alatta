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
  native.includes('"ريلز" -> {') && native.includes('uploadedPostReels(reelPosts, rejectedShortIds)') &&
  native.includes('collectUploadedReels(reset = true)') &&
  native.includes('FeedPages.fetch(::get, next)') &&
  native.includes('onLoadMoreReels = {') &&
  native.includes('reelsOnly = true') &&
  native.includes('YoutubePostMedia(videoId, activeYoutubePostId == postId') &&
  native.includes('if (showFullscreenVideo && video.isNotBlank())') &&
  !native.includes('includeYoutubePosts = true') &&
  native.includes('visible = chromeVisible && tab != "القرآن"') &&
  native.includes('chromeVisible = tab != "القرآن"'),
  'Uploaded, YouTube and public social videos are discoverable in Reels, with native navigation retained');

const mushafReader = fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AttaQuranReader.kt','utf8');
assert.ok(mushafReader.includes('AnimatedVisibility(visible = readerControlsVisible') &&
  mushafReader.includes('ScaleGestureDetector(context') &&
  mushafReader.includes('❖  صفحة') &&
  mushafReader.includes('onZoomChanged = { pageZoom = it }') &&
  mushafReader.includes('pageZoom = (pageZoom + .15f).coerceAtMost(2f)'),
  'Single Quran reader hides its toolbar on idle and supports SVG zoom and ornate page numbers');
const uploadedPostDialog = native.split('if (showFullscreenVideo && video.isNotBlank()) {',2)[1]?.split('loadInteractions =',1)[0] || '';
assert.ok(uploadedPostDialog.includes('reelsOnly = true') &&
  native.includes('AttaPostMediaViewer(') &&
  native.includes('reelsOnly = true') &&
  !native.includes('includeYoutubePosts = true'),
  'Home uploaded video and Reels use one portrait viewer; YouTube never opens in the media dialog');
const siteYoutube=fs.readFileSync('cloudflare-site/public/youtube-section.js','utf8');
const workerSource=fs.readFileSync('cloudflare-site/src/worker.js','utf8');
assert.ok(!native.includes('youtube_verified_short_link') &&
  !native.includes('youtubeShortUrl') &&
  native.includes('youtubeShareSelection = id to v.optString("title").take(160)') &&
  native.includes('مشاركة كمنشور') && native.includes('مشاركة في الريلز') &&
  native.includes('youtubeShareChecking') &&
  native.includes('جاري تجهيز فيديو يوتيوب') &&
  !siteYoutube.includes('shortsForm') &&
  !siteYoutube.includes('shortShare.onclick') &&
  siteYoutube.includes('share.onclick=()=>openShareOptions(v)') &&
  siteYoutube.includes('postChoice.addEventListener') &&
  siteYoutube.includes('reelsChoice.addEventListener') &&
  workerSource.includes('url.pathname==="/api/youtube-short"') &&
  workerSource.includes('video.status?.embeddable===true') &&
  !workerSource.includes('seconds<=180') &&
  workerSource.includes('ids.length>25') &&
  native.includes('api("/api/youtube-short?ids='),
  'One existing YouTube share button offers Post/Reels on app and site, without separate Shorts paste form');
assert.ok(native.includes('if (!frameLoaded || error.isNotBlank()) {') &&
  native.includes('Box(Modifier.fillMaxSize().background(Color.Black)') &&
  native.includes('جاري تجهيز فيديو يوتيوب داخل العطا') &&
  native.includes('إعادة تشغيل الفيديو') &&
  native.includes('playerReload++') &&
  native.includes('loadUrl("$BASE/")') &&
  !native.includes('if (portrait && !frameLoaded && error.isBlank())'),
  'Site login screen hidden while WebView boots: one official YouTube iframe reveals only after load, safe retry');
const youtubeShare=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/YoutubePostShare.kt','utf8');
assert.ok(youtubeShare.includes('alatta-youtube(-short)?') &&
  youtubeShare.includes('fun youtubeShortPostId(') &&
  native.includes('youtubeShortContent = { shortId, command, shouldPlay, changed, onShortsDrag ->') &&
  native.includes('YouTubePlayer(shortId, onMiniDrag = onShortsDrag,') &&
  native.includes('portrait = true, playbackCommand = command,') &&
  native.includes('youtubePostDraftIsShort = true') &&
  native.includes('playbackCommand = command') &&
  native.includes('event == "PAUSED" || event == "ENDED"'),
  'YouTube Reels use one official embedded player with play/pause signals');
const videoViewer=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/AttaExpandableVideo.kt','utf8');
assert.ok(
  videoViewer.includes('val reelsMode = reelsOnly && live == null') &&
  videoViewer.includes('internal fun uploadedPostReels(') &&
  videoViewer.includes('youtubePostId(post.optString("text"))') &&
  videoViewer.includes('socialVideoInPost(post.optString("text"))') &&
  videoViewer.includes('onRefreshReels?.invoke()') &&
  videoViewer.includes('onLoadMoreReels?.invoke()') &&
  videoViewer.includes('SocialVideoPostEmbed(social, Modifier.fillMaxSize()') &&
  videoViewer.includes('youtubeShortContent(youtubeId, youtubeCommand, youtubeCommandPlay,') &&
  videoViewer.includes('resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT') &&
  videoViewer.includes('else if (reelsOnly) 37.dp') &&
  videoViewer.includes('if (reelsOnly && controls && player != null)') &&
  !videoViewer.includes('RESIZE_MODE_ZOOM') &&
  (videoViewer.match(/ExoPlayer\.Builder\(/g) || []).length === 1,
  'One FIT Media3 player; Reels accept uploaded, YouTube and public Meta videos with swipe paging and in-screen refresh');
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
  azkarCatalog.includes('val closingIds = listOf(') &&
  azkarCatalog.includes('"salawat-daily", "tawhid", "tasbih", "repent-day"') &&
  azkarCatalog.includes('closingIds.map { id -> entries.single { it.id == id } }') &&
  azkarCatalog.includes('Dhikr("salawat-daily"') &&
  azkarCatalog.includes('سَيِّدِنَا مُحَمَّدٍ", 10,') &&
  azkarCatalog.includes('Dhikr("worry-debt"') &&
  azkarCatalog.includes('قَهْرِ الرِّجَالِ", 3,') &&
  azkarCatalog.includes('بِاللَّهِ", 10, "صحيح مسلم • 2704') &&
  azkarCatalog.includes('Dhikr("beloved-words", "كلمتان حبيبتان إلى الرحمن"') &&
  azkarCatalog.includes('سُبْحَانَ اللَّهِ وَبِحَمْدِهِ، سُبْحَانَ اللَّهِ الْعَظِيمِ", 10,') &&
  azkarCatalog.includes('يُحْيِي وَيُمِيتُ وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ", 100,') &&
  !azkarCatalog.includes('Dhikr("salawat-ten"'),
  'Morning/evening Adhkar keep Quran texts, custom repetition targets and ending: salawat, tawhid, tasbih, 100-count forgiveness');
assert.ok(azkarScreen.includes('ContentScale.FillBounds') &&
  !azkarScreen.includes('ContentScale.Crop') &&
  azkarScreen.includes('Arrangement.spacedBy(12.dp)') &&
  azkarScreen.includes('Box(Modifier.size(44.dp).clickable {') &&
  azkarScreen.includes('Box(Modifier.size(27.dp).clip(CircleShape)') &&
  azkarScreen.includes('entry.target >= 100 -> 14.sp') &&
  azkarScreen.includes('modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)') &&
  azkarScreen.includes('entry.id == "kursi" || entry.id == "baqarah-ending"') &&
  azkarScreen.includes('أَعُوذُ بِاللَّهِ مِنَ الشَّيْطَانِ الرَّجِيمِ'),
  'Azkar controls have separated 27dp circles, 100-count digits fit and istiadhah precedes the verses');
const dhikrShare = fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/DhikrShare.kt','utf8');
const filePathsXml = fs.readFileSync('android-native/app/src/main/res/xml/atta_update_file_paths.xml','utf8');
assert.ok(azkarScreen.includes('onShareImagePost(selectedGroup, selectedEntry)') &&
  azkarScreen.includes('نشر الذكر بصورة ذهبية داخل العطا') &&
  azkarScreen.includes('مشاركة الذكر كمنشور') &&
  azkarScreen.includes('sharePost(true)') &&
  dhikrShare.includes('android.graphics.Bitmap.createBitmap') &&
  dhikrShare.includes('createDhikrGoldPostImage(') &&
  dhikrShare.includes('android.net.Uri.fromFile(image)') &&
  !dhikrShare.includes('android.content.Intent.ACTION_SEND') &&
  native.includes('onShareDhikrImagePost = { group, entry ->') &&
  native.includes('pickedMime = "image/png"') &&
  filePathsXml.includes('<cache-path name="dhikr_gold_cards" path="share_cards/" />'),
  'Gold dhikr card is an actual PNG attached to the AL-ATTA post composer, never a system external share');
assert.ok(azkarScreen.includes('AzkarHeaderPlaque(Modifier.weight(1f))') &&
  azkarScreen.includes('val goldStyle = true') &&
  azkarScreen.includes('azkar_prayer_backdrop') &&
  azkarScreen.includes('page = category.id') &&
  !azkarScreen.includes('أذكار بعدد تكرار أكثر من 10'),
  'Azkar preserves full passages with gold-black styling and only the ornamental header, not a category divider');
const socialEmbed=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/SocialVideoEmbed.kt','utf8');
assert.ok(socialEmbed.includes('socialVideoUrl(') &&
  socialEmbed.includes('SocialVideoPostEmbed(') &&
  socialEmbed.includes('MiniPlayerTouchHost(context)') &&
  socialEmbed.includes('https://www.facebook.com/plugins/video.php?href=') &&
  socialEmbed.includes('instagram.com') &&
  native.includes('socialVideoDraftUrl') &&
  native.includes('SocialVideoPostEmbed(social, Modifier.fillMaxWidth())') &&
  native.includes('onRefreshReels = {'),
  'Public Instagram/Facebook videos use only their official embedded players; Reels have in-place refresh');
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
