import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {gunzipSync} from 'node:zlib';
import {createHash} from 'node:crypto';

const root=new URL('../',import.meta.url),assets='android-native/app/src/main/assets/';
const read=p=>fs.readFileSync(new URL(p,root),'utf8');
const html=read(assets+'atta-quran.html');
const kotlin=read('android-native/app/src/main/java/com/alatta/nativeapp/AttaQuranReader.kt');
const main=read('android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt');
const gradle=read('android-native/app/build.gradle.kts');
const code=[...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)];
assert.equal(code.length,1,'One script owns the existing reader');new vm.Script(code[0][1]);
assert.ok(kotlin.includes('WebViewAssetLoader')&&kotlin.includes('context.assets.open("mushaf/pages/$path")'));
assert.ok(kotlin.includes('Regex("[0-9]{3}\\\\.svg")'),'Only numbered local page paths are served');
assert.ok(!kotlin.includes('addJavascriptInterface'));
assert.ok(main.includes('"القرآن" -> QuranReaderScreen(Modifier.weight(1f), onExit ='));
assert.ok(main.includes('tab != "القرآن" && (tab != "الرسائل"'));
assert.ok(kotlin.includes('BackHandler')&&html.includes('window.attaQuranBack'));
assert.ok(gradle.includes('versionCode = 180')&&gradle.includes('versionName = "1.0.80"'));
assert.ok(!html.includes('text-align:justify'));
assert.ok(html.includes('text-size-adjust:100%')&&html.includes("image.id='mushafImage'"));
for(const edition of ['ar.muyassar','ar.minshawi','ar.husary','ar.abdulbasit'])assert.ok(html.includes(edition));

// Verify every delivered page, not just the reference screenshot.
const index=JSON.parse(read(assets+'mushaf/index.json'));
assert.equal(index.pages.length,605);assert.equal(index.ayahs.length,6237);assert.equal(index.surahs.length,114);
assert.equal(index.juzStart.length,31);assert.equal(index.quarterStart.length,241);
const files=fs.readdirSync(new URL(assets+'mushaf/pages/',root)).sort();assert.equal(files.length,604);
let number=0;const counts=Array(115).fill(0);
for(let p=1;p<=604;p++){
 const page=index.pages[p],filename=String(p).padStart(3,'0')+'.svg.gz';assert.equal(files[p-1],filename);
 const data=fs.readFileSync(new URL(assets+'mushaf/pages/'+filename,root));
 assert.equal(createHash('sha256').update(data).digest('hex'),page.assetSha256,`Page ${p} integrity`);
 assert.equal(data.length,page.bytes);
 const svg=gunzipSync(data).toString('utf8');assert.ok(svg.includes('id="content"')&&svg.includes('id="ayah_markers"'));
 assert.ok(!/<script\b|<foreignObject\b|(?:href|src)=["']https?:/i.test(svg),`Page ${p} is self-contained artwork`);
 assert.equal(page.markers.length,page.ayahs.length);assert.ok(page.markers.length>0);
 for(const [id,x,y] of page.markers){number++;assert.equal(id,number);assert.equal(page.ayahs[page.ayahs.indexOf(id)],id);assert.equal(index.ayahs[id].page,p);assert.ok(Number.isFinite(x)&&Number.isFinite(y)&&y>=0&&y<=page.height);counts[index.ayahs[id].surah]++;}
 for(const header of page.headers){assert.equal(header.pageNumber,p);assert.ok(header.headerPosition>0&&header.headerPosition<page.height);}
}
assert.equal(number,6236);
for(const s of index.surahs){assert.equal(counts[s.number],s.numberOfAyahs);assert.equal(index.ayahs.find(a=>a?.surah===s.number).page,s.startPage);}
for(const starts of [index.juzStart.slice(1),index.quarterStart.slice(1)])assert.ok(starts.every((p,i)=>p>=1&&p<=604&&(!i||p>=starts[i-1])));
assert.deepEqual(index.pages[356].ayahs.map(n=>[index.ayahs[n].surah,index.ayahs[n].numberInSurah]),Array.from({length:10},(_,i)=>[24,44+i]));
assert.equal(index.ayahs[index.pages[356].ayahs.at(-1)].hizbQuarter,143,'Reference footer shows half of hizb 36');
assert.ok(read(assets+'mushaf/LICENSE.txt').includes('MIT License'));
assert.ok(read(assets+'mushaf/SOURCE.md').includes(index.sourceCommit));
console.log('PASS: 604 packaged printed pages, 6236 ordered verse endpoints, 114 chapter starts, asset integrity and licenses');

// Execute the actual reader script; browser pixel rendering is checked separately.
function harness(height,shared){
 class Element{
  constructor(id=''){this.id=id;this.children=[];this.style={};this.dataset={};this.attrs={};this.events={};this.value='';this.disabled=false;this.clientWidth=390;this.clientHeight=height;const set=new Set();this.classList={toggle:(c,on)=>on?set.add(c):set.delete(c),contains:c=>set.has(c)};}
  append(...items){for(const child of items){child.parent=this;this.children.push(child);}}
  replaceChildren(...items){this.children=[];this.append(...items);}
  addEventListener(name,fn){this.events[name]=fn;}
  setAttribute(k,v){this.attrs[k]=v;}
  removeAttribute(k){delete this.attrs[k];}
  getBoundingClientRect(){return{left:0,top:0,width:parseFloat(this.parent?.style.width)||370,height:parseFloat(this.parent?.style.height)||height-14};}
  pause(){this.paused=true;}load(){}play(){this.paused=false;return Promise.resolve();}
 }
 const nodes=new Map([...html.matchAll(/\bid="([^"]+)"/g)].map(m=>[m[1],new Element(m[1])]));
 nodes.get('reciter').options=[{value:'ar.minshawi'}];nodes.get('reciter').selectedOptions=[{textContent:'القارئ'}];
 const storage=shared||{'atta-quran-last-page-v1':'356'};
 if(!shared)Object.defineProperties(storage,{getItem:{value:k=>storage[k]??null},setItem:{value:(k,v)=>storage[k]=String(v)},removeItem:{value:k=>delete storage[k]}});
 const calls=[];
 const doc={getElementById:id=>nodes.get(id),createElement:()=>new Element(),querySelectorAll:()=>[],querySelector:selector=>selector==='.overlay.open'?[...nodes.values()].find(n=>n.classList.contains('open')):null,documentElement:{dataset:{}}};
 const context={document:doc,window:{addEventListener(){}},localStorage:storage,AbortController,setTimeout,clearTimeout,console,fetch:async url=>{calls.push(url);if(url==='mushaf/index.json')return{ok:true,json:async()=>index};let data;if(url.includes('/ar.muyassar'))data={text:'تفسير اختبار آلي'};else data={ayahs:[{numberInSurah:43,audio:'https://example.invalid/43.mp3'},{numberInSurah:44,audio:'https://example.invalid/44.mp3'},{numberInSurah:45,audio:'https://example.invalid/45.mp3'}]};return{ok:true,json:async()=>({code:200,data})};}};
 vm.createContext(context);vm.runInContext(code[0][1],context);
 return{nodes,context,storage,calls,image:()=>nodes.get('pageText').children.find(n=>n.id==='mushafImage'),run:s=>vm.runInContext(s,context)};
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
for(const height of [280,640,960]){
 const h=harness(height);await settle();assert.ok(h.image().src.endsWith('/356.svg'));assert.deepEqual(h.calls,['mushaf/index.json'],'No remote calls to read Quran');
 assert.equal(h.nodes.get('surahButton').textContent,'النور');assert.ok(h.nodes.get('pageCount').textContent.includes('1/2'));
 const rect=h.image().getBoundingClientRect();h.image().onclick({clientX:rect.width*.85,clientY:rect.height*.035});
 assert.ok(h.nodes.get('verseTitle').textContent.includes('٤٤'));assert.equal(h.nodes.get('versePreview').textContent,index.ayahs[2835].text+' ﴿٤٤﴾');
 await h.run('loadTafsir()');assert.equal(h.nodes.get('tafsirText').textContent,'تفسير اختبار آلي');assert.ok(h.calls.at(-1).endsWith('/ayah/24:44/ar.muyassar'));
 await h.run('startRecitation(false)');await settle();assert.ok(h.nodes.get('recitation').src.endsWith('/44.mp3'),'Playback starts from selected verse');
 h.nodes.get('stopAudio').events.click();h.nodes.get('bookmarkButton').onclick();assert.equal(h.storage['atta-quran-bookmark-v1'],'356');
 for(let i=0;i<20;i++)h.nodes.get('fontLarger').onclick();assert.equal(h.nodes.get('fontSizeLabel').textContent,'180%');assert.equal(h.nodes.get('fontLarger').disabled,true);
 h.nodes.get('contrastButton').onclick();assert.equal(h.context.document.documentElement.dataset.contrast,'white');
 h.run('loadPage(604);loadPage(356)');await settle();assert.ok(h.image().src.endsWith('/356.svg'),'Late page load cannot replace current page');
 h.run("jumpToSegment('surah',114)");await settle();assert.ok(h.image().src.endsWith('/604.svg'));
 const reopened=harness(height,h.storage);await settle();assert.equal(reopened.nodes.get('fontSizeLabel').textContent,'180%');assert.equal(reopened.context.document.documentElement.dataset.contrast,'white');assert.ok(reopened.image().src.endsWith('/604.svg'));
 for(let i=0;i<20;i++)reopened.nodes.get('fontSmaller').onclick();assert.equal(reopened.nodes.get('fontSizeLabel').textContent,'100%');assert.equal(reopened.nodes.get('fontSmaller').disabled,true);
 assert.ok(h.calls.filter(u=>u==='mushaf/index.json').length===1);
}
console.log('PASS: offline reading, correct verse hit testing, tafsir, selected-verse audio, bookmarks, chapter jumps, load races, zoom and persistence at three viewport heights');
