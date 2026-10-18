import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const root = new URL('../', import.meta.url);
const read = path => fs.readFileSync(new URL(path, root), 'utf8');
const html = read('android-native/app/src/main/assets/atta-quran.html');
const kotlin = read('android-native/app/src/main/java/com/alatta/nativeapp/AttaQuranReader.kt');
const main = read('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt');
const video = read('android-native/app/src/main/java/com/alatta/nativeapp/NativeRtcCall.kt');
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
assert.ok(kotlin.includes("WebViewAssetLoader") && kotlin.includes("appassets.androidplatform.net/assets/atta-quran.html"));
assert.ok(!kotlin.includes('addJavascriptInterface'), 'Do not expose native JS bridges to Quran provider origin');
assert.ok(main.includes('"القرآن" -> QuranReaderScreen(Modifier.weight(1f), onExit ='));
assert.ok(kotlin.includes('settings.textZoom = 100'));
assert.ok(kotlin.includes('BackHandler'));
assert.ok(html.includes('window.attaQuranBack'));
assert.ok(html.includes('function applyReadingStyle()'));
assert.ok(html.includes('text-align:center'));
assert.ok(html.includes('height:52px') && html.includes('height:44px'), 'Compact Mushaf controls');
assert.ok(html.includes('text-size-adjust:100%'), 'Prevent WebView font boosting');
assert.ok(html.includes('id="nextButton" aria-label="الصفحة التالية"'), 'Arabic navigation arrow labels');
assert.ok(html.includes('#verseOverlay .sheet{height:min(70vh,510px)'), 'Accessible centered verse options');
assert.ok(!html.includes('text-align:justify'), 'Do not stretch Arabic words across the page');
assert.ok(main.includes('tab != "القرآن" && (tab != "الرسائل"'), 'Hide app nav in reader');
assert.ok(!html.includes('aria-label="رجوع"'), 'Use Android system back, no duplicate back button');
assert.ok(video.includes('localVideo?.addSink(localFrameProbe)'), 'Native camera frames feed the local preview');
assert.ok(gradle.includes('versionCode = 179') && gradle.includes('versionName = "1.0.79"'));
console.log('PASS: Quran 604-page reader, verse recitations, tafsir, system navigation, native WebRTC camera preview');

// Execute the real reader script with small/large viewport geometry.
// This is a DOM harness, not an Android/WebView device rendering claim.
function readerHarness(height, sharedStorage) {
    class Element {
        constructor(id='') { this.id=id;this.children=[];this.style={};this.dataset={};this.attrs={};this.events={};this.value='';this.disabled=false;const set=new Set();this.classList={toggle:(c,on)=>on?set.add(c):set.delete(c),contains:c=>set.has(c)}; }
        append(...items){this.children.push(...items)}
        replaceChildren(...items){this.children=items}
        addEventListener(name,fn){this.events[name]=fn}
        setAttribute(k,v){this.attrs[k]=v}
        removeAttribute(k){delete this.attrs[k]}
        pause(){} load(){}
    }
    const nodes=new Map([...html.matchAll(/\bid="([^"]+)"/g)].map(m=>[m[1],new Element(m[1])]));
    nodes.get('reader').clientHeight=height;
    nodes.get('reciter').options=[{value:'ar.minshawi'}];nodes.get('reciter').selectedOptions=[{textContent:'القارئ'}];
    const storage=sharedStorage||{};
    if(!sharedStorage)Object.defineProperties(storage,{getItem:{value:k=>storage[k]??null},setItem:{value:(k,v)=>{storage[k]=String(v)}},removeItem:{value:k=>{delete storage[k]}}});
    const calls=[];let offline=false;
    const originalText='بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ الٓمٓ';
    const doc={getElementById:id=>nodes.get(id),createElement:()=>new Element(),createTextNode:text=>({textContent:text}),querySelectorAll:()=>[],querySelector:()=>null,documentElement:{dataset:{}}};
    const context={document:doc,window:{addEventListener(){}},localStorage:storage,AbortController,setTimeout,clearTimeout,console,
        fetch:async url=>{calls.push(url);if(offline)throw Error('offline');const match=url.match(/\/page\/(\d+)/);const data=match?{number:Number(match[1]),ayahs:Array.from({length:40},(_,i)=>({number:i+8,numberInSurah:i+1,text:i===0?originalText:'اختبار آلي للعرض فقط',juz:1,hizbQuarter:1,page:Number(match[1]),surah:{number:2,name:'البقرة'}}))}:Array.from({length:114},(_,i)=>({number:i+1,name:'فهرس اختبار',numberOfAyahs:1}));return{ok:true,json:async()=>({code:200,data})}}};
    vm.createContext(context);vm.runInContext(matches[0][1],context);
    return{nodes,context,storage,calls,originalText,setOffline:value=>offline=value};
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
for(const height of [280,640,960]){
    const h=readerHarness(height);await settle();
    const page=h.nodes.get('pageText');
    assert.equal(page.style.fontSize,'30px','dense pages never shrink to fit a small viewport');
    const basmalahs=page.children.filter(c=>c.className==='basmalah');
    assert.equal(basmalahs.length,1,'provider basmalah is displayed only once');
    const firstVerse=page.children.find(c=>c.className==='ayah');
    assert.equal(basmalahs[0].textContent+' '+firstVerse.children[0].textContent.trim(),h.originalText,'layout splitting preserves original Quran glyphs');
    for(let i=0;i<20;i++)h.nodes.get('fontLarger').onclick();assert.equal(page.style.fontSize,'44px');assert.equal(h.nodes.get('fontLarger').disabled,true);
    for(let i=0;i<20;i++)h.nodes.get('fontSmaller').onclick();assert.equal(page.style.fontSize,'24px');assert.equal(h.nodes.get('fontSmaller').disabled,true);
    h.nodes.get('contrastButton').onclick();assert.equal(h.context.document.documentElement.dataset.contrast,'white');
    const before=h.calls.length;h.setOffline(true);vm.runInContext('loadPage(1)',h.context);await settle();
    assert.equal(h.calls.length,before,'cached Quran page opens without network');assert.ok(page.children.some(c=>c.className==='ayah'));
    const reopened=readerHarness(height,h.storage);await settle();assert.equal(reopened.nodes.get('pageText').style.fontSize,'24px');assert.equal(reopened.context.document.documentElement.dataset.contrast,'white');
}
assert.ok(html.includes('fonts/AmiriQuran-Regular.ttf'));
const font=fs.readFileSync(new URL('android-native/app/src/main/assets/fonts/AmiriQuran-Regular.ttf',root));assert.equal(font.readUInt32BE(0),0x00010000);
assert.ok(read('android-native/app/src/main/assets/fonts/AmiriQuran-OFL.txt').includes('SIL OPEN FONT LICENSE'));
console.log('PASS reader DOM at three viewport heights: fixed readable font, zoom limits/persistence, contrast, no duplicate basmalah, cached pages and packaged font/license');
