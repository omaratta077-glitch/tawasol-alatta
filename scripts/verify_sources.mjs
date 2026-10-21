import fs from 'node:fs';
import assert from 'node:assert/strict';
const native=fs.readFileSync('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt','utf8');
const worker=fs.readFileSync('cloudflare-site/src/worker.js','utf8');
const html=fs.readFileSync('cloudflare-site/public/index.html','utf8');
assert.equal((native.match(/fun YouTubePlayer\(/g)||[]).length,1,'one canonical YouTubePlayer');
assert.equal((native.match(/YouTubePlayer\(youtubeId\)/g)||[]).length,1,'screen calls canonical player');
assert.ok(!native.includes('AlattaFixedYouTubePlayer') && !native.includes('attaYouTubeHtml'),'no duplicate native player');
assert.ok(native.includes('loadUrl("$BASE/")') && native.includes('atta-site-youtube-bridge.js') && !native.includes('document.body.replaceChildren(host)'),'Android drives the actual site player without replacing the website document');
assert.ok(!native.includes('raw.contains("username", ignoreCase = true)'),'no blanket mapping of login failures to username-taken');
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
