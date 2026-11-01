// اختبار تراجعي للمصحف الأصلي: صفحات محلية مضمّنة وعارض Canvas واحد.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import zlib from 'node:zlib';
const root = new URL('../', import.meta.url).pathname;
const app = path.join(root, 'android-native/app/src/main');
const source = fs.readFileSync(path.join(app,'java/com/alatta/nativeapp/AttaQuranReader.kt'),'utf8');
assert.match(source,/internal fun QuranReaderScreen\(/);
assert.match(source,/MushafPageView\(context, page, changePage,/);
assert.match(source,/SVG\.getFromInputStream/);
assert.doesNotMatch(source,/GZIPInputStream/);
assert.doesNotMatch(source,/WebView\(|loadDataWithBaseURL\(/);
assert.match(source,/BackHandler/);
assert.match(source,/putInt\("page"/);
assert.match(source,/فهرس المصحف الشريف/);
const dir=path.join(app,'assets/mushaf/pages');
const pages=fs.readdirSync(dir).filter(x=>/^\d{3}\.svg\.gz$/.test(x));
assert.equal(pages.length,604);
for(let p=1;p<=604;p++)assert.ok(fs.existsSync(path.join(dir,String(p).padStart(3,'0')+'.svg.gz')));
for(const p of [1,356,604]){
 const xml=zlib.gunzipSync(fs.readFileSync(path.join(dir,String(p).padStart(3,'0')+'.svg.gz'))).toString('utf8');
 assert.match(xml,/<svg\b/i,`missing SVG page ${p}`);
}
const index=JSON.parse(fs.readFileSync(path.join(app,'assets/mushaf/index.json'),'utf8'));
assert.ok(index.pages, 'offline Quran index missing');
console.log('PASS: Native offline Quran reader, 604 compressed SVG pages, sampled valid pages, history and back navigation');

// يحوّل دمج أصول Android ملفات .svg.gz إلى .svg قبل تضمينها في APK.
const pageStore = fs.readFileSync(path.join(app, 'java/com/alatta/nativeapp/MushafPageStore.kt'), 'utf8');
assert.ok(source.includes('MushafPageStore.openPage(context,page,allowNetwork)'));
assert.ok(pageStore.includes('mushaf/pages/001.svg'));
assert.match(pageStore, /GZIPInputStream/);
assert.match(pageStore, /assetSha256/);
assert.match(pageStore, /ensureCached/);
assert.match(pageStore, /MessageDigest/);
assert.doesNotMatch(source, /GZIPInputStream/);

for (const control of ["فهرس المصحف الشريف","الأجزاء","المرجعيات","الملاحظات","ابحث عن اسم السورة أو رقم الجزء","bookmark","note_$page"]) assert.ok(source.includes(control), `reader control missing: ${control}`);

// اختبارات تراجعية لمشغّل نطاقات التلاوة.
for(const fn of ['playRange(', 'audio.pause()', 'audio.resume()', 'audio.next()', 'audio.previous()', 'repeatRange = !repeatRange','repeat = { repeatRange }','إلى آخر الصفحة','إلى آخر السورة','customEnd','QURAN_RECITERS']) {
 assert.ok(source.includes(fn), `missing range playback control ${fn}`);
}
assert.ok(source.includes('height(38.dp)'), 'Missing compact Quran footer');
assert.match(source, /ScaleGestureDetector/);
assert.match(source, /readerControlsVisible/);
assert.ok(source.includes('mushafArabicNumber(page)'), 'Missing ornamental Arabic page number');

// يستخدم المشغّل المصغّر أيقونة للتكرار، لكن يجب أن يبقى زر التبديل عاملًا.
assert.match(source, /IconButton\(onClick = \{ repeatRange = !repeatRange \}\)/);
assert.match(source, /if \(repeat\(\)\) current = start/);
console.log('PASS: inline recitation controls, repeat-range state and Arabic labels');
