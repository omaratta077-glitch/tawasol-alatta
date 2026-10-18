import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const root = new URL('../', import.meta.url);
const read = path => fs.readFileSync(new URL(path, root), 'utf8');
const html = read('android-native/app/src/main/assets/atta-quran.html');
const kotlin = read('android-native/app/src/main/java/com/alatta/nativeapp/AttaQuranReader.kt');
const main = read('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt');
const video = read('android-native/app/src/main/assets/atta-call.html');
const gradle = read('android-native/app/build.gradle.kts');

const matches = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)];
assert.equal(matches.length, 1, 'A single reader script should own the Quran UI');
new vm.Script(matches[0][1], { filename: 'atta-quran.html' });
for (const endpoint of ['/page/', '/surah/', '/juz/', '/hizbQuarter/', '/ayah/']) {
    assert.ok(html.includes(endpoint), `Missing Quran provider path: ${endpoint}`);
}
for (const edition of ['quran-uthmani', 'ar.muyassar', 'ar.minshawi', 'ar.husary', 'ar.abdulbasit']) {
    assert.ok(html.includes(edition), `Missing verified Quran edition: ${edition}`);
}
for (const feature of ['playHere', 'playSurah', 'loadTafsir', 'hizbQuarter', 'localStorage', 'verse-num', 'indexOverlay']) {
    assert.ok(html.includes(feature), `Missing Quran reader action: ${feature}`);
}
assert.ok(html.includes('٢٤٠') && html.includes('٦٠٤') && html.includes('١١٤'));
assert.ok(kotlin.includes('loadDataWithBaseURL("https://api.alquran.cloud/"'));
assert.ok(!kotlin.includes('addJavascriptInterface'), 'Do not expose native JS bridges to Quran provider origin');
assert.ok(main.includes('"القرآن" -> QuranReaderScreen(Modifier.weight(1f))'));
assert.ok(main.includes('tab != "القرآن" && (tab != "الرسائل"'), 'Hide app nav in reader');
assert.ok(!html.includes('aria-label="رجوع"'), 'Use Android system back, no duplicate back button');
assert.ok(video.includes("canvas.style.display=visible?'block':'none'"), 'Never overlay an all-black selfie canvas');
assert.ok(gradle.includes('versionCode = 170') && gradle.includes('versionName = "1.0.70"'));
console.log('PASS: Quran 604-page reader, verse recitations, tafsir, system navigation, selfie canvas guard');
