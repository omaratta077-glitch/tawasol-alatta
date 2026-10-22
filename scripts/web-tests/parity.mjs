import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {JSDOM,VirtualConsole} from 'jsdom';
const root=path.resolve(import.meta.dirname,'../..'),pub=path.join(root,'cloudflare-site/public');
const read=f=>fs.readFileSync(path.join(pub,f),'utf8');
const html=read('index.html'),app=read('app.js'),css=read('native-parity.css');
const dom=new JSDOM(html,{url:'https://example.test/',runScripts:'outside-only',pretendToBeVisual:true,virtualConsole:new VirtualConsole()});
const w=dom.window,d=w.document;
w.matchMedia=()=>({matches:false,addEventListener(){},removeEventListener(){}});
w.setTimeout=()=>1;w.setInterval=()=>1;w.clearTimeout=()=>{};w.clearInterval=()=>{};w.queueMicrotask=()=>{};
w.alert=()=>{};w.confirm=()=>true;w.HTMLElement.prototype.scrollIntoView=function(){};
w.Audio=class{play(){return Promise.resolve();}pause(){}load(){}};
w.AbortController=AbortController;
let requests=[],mode='data',postFixtures=[];
w.fetch=async(url,opts={})=>{
 requests.push({url,opts});
 if(mode==='network')throw new w.TypeError('Failed to fetch');
 if(mode==='401')return new Response(JSON.stringify({error:'الجلسة منتهية'}),{status:401});
 const target=String(url).split('?')[0];
 if(target.startsWith('/knowledge/'))return new Response(fs.readFileSync(path.join(pub,target)));
 if(target==='/api/posts')return Response.json({ok:true,posts:postFixtures});
 if(target==='/api/football')return Response.json({ok:true,matches:[{home:'<img src=x onerror=boom()>',away:'B',league:'Test',startsAt:1790000000000,status:'scheduled'}],channels:[]});
 return Response.json({ok:true,users:[],friends:[],requests:[],notifications:[],stories:[],voices:[]});
};
w.eval(read('icons.js'));
const index=app.indexOf('})();\n\n\n/* ===== V13.28');
w.eval(app.slice(0,index)+'window.__webTest={api,restoreSavedLogin,showPage,loadPosts,setSession(){token="fixture";me={username:"fixture"}}};'+app.slice(index));
w.eval(read('web-modules.js'));
const tick=async()=>{for(let i=0;i<10;i++)await new Promise(r=>setImmediate(r));};
// All existing and newly introduced navigation targets resolve; rightmost first matches native.
const buttons=[...d.querySelectorAll('.premium-nav .nav-btn')];
assert.deepEqual(buttons.map(b=>b.dataset.page),['homePage','messagesPage','profilePage','quranPage','hadithPage','azkarPage','sportsPage','youtubePage','morePage']);
assert.ok(buttons.every(b=>d.getElementById(b.dataset.page)));
const before=d.querySelector('.active-page').id;
assert.equal(w.__webTest.showPage(undefined),false);assert.equal(d.querySelector('.active-page').id,before);
d.getElementById('railMoreBtn').click();assert.equal(d.querySelector('.active-page').id,'morePage');
assert.equal(d.getElementById('webAdminGo').hidden,true);
assert.ok(!read('style.css').includes('html.mobile-device'));
assert.ok(!html.includes('mobile-final-clean')&&!html.includes('atta-native-parity-web-only'));
assert.match(css,/flex:0 0 66px/);assert.match(css,/height:62px/);assert.match(css,/prefers-reduced-motion/);
// Every web icon path is copied from its exact canonical Android vector.
for(const file of fs.readdirSync(path.join(root,'android-native/app/src/main/res/drawable')).filter(f=>f.startsWith('atta_ic_')&&f.endsWith('.xml'))){
 const xml=fs.readFileSync(path.join(root,'android-native/app/src/main/res/drawable',file),'utf8');
 const name=file.slice(8,-4).replaceAll('_','-');const svg=w.AttaIcons.icon(name);
 const svgNode=d.createElement('div');svgNode.innerHTML=svg;const paths=svgNode.querySelectorAll('path');
 const nativePaths=[...xml.matchAll(/<path\b[^>]*>/g)];assert.equal(paths.length,nativePaths.length);
 nativePaths.forEach(([tag],i)=>{
  const attr=name=>tag.match(new RegExp('android:'+name+'="([^"<]*)"'))?.[1];
  assert.equal(paths[i].getAttribute('d'),attr('pathData'),name+' geometry');
  assert.equal(paths[i].getAttribute('fill'),['#00000000','@android:color/transparent'].includes(attr('fillColor'))?'none':'currentColor',name+' fill');
  assert.equal(paths[i].getAttribute('stroke'),attr('strokeColor')&&!['#00000000','@android:color/transparent'].includes(attr('strokeColor'))?'currentColor':'none',name+' stroke');
 });
}
// Actual connection failures preserve saved login. Only an explicit auth refusal clears it.
const key='tawasol_alatta_session_v1';w.localStorage.setItem(key,JSON.stringify({token:'fixture'}));mode='network';requests=[];
assert.equal(await w.__webTest.restoreSavedLogin(),false);assert.ok(w.localStorage.getItem(key));assert.equal(requests.length,2);
requests=[];await assert.rejects(w.__webTest.api('/api/posts',{method:'POST',body:'{}'}));assert.equal(requests.length,1,'POST is never replayed');
mode='401';assert.equal(await w.__webTest.restoreSavedLogin(),false);assert.equal(w.localStorage.getItem(key),null);mode='data';
// Render real feed reactions, preserving the chosen emoji and canonical native order.
w.__webTest.setSession();
postFixtures=['','like','love','angry'].map((myReaction,i)=>({id:'reaction-'+i,author:'fixture',authorInfo:{username:'fixture',fullName:'اختبار'},myReaction,createdAt:Date.now(),likes:[],comments:[],text:'اختبار التفاعلات'}));
await w.__webTest.loadPosts();
const symbols=[...d.querySelectorAll('[data-react-main]')];
assert.equal(symbols[0].querySelector('svg path').getAttribute('fill'),'none');
assert.equal(symbols[1].querySelector('svg path').getAttribute('fill'),'currentColor');
assert.equal(symbols[2].textContent,'❤️');assert.equal(symbols[3].textContent,'😡');
assert.deepEqual([...d.querySelector('[data-reaction-picker]').querySelectorAll('[data-reaction]')].map(b=>b.dataset.reaction),['love','haha','wow','sad','angry']);
assert.equal(d.querySelector('[data-post="reaction-0"]').querySelectorAll('[data-share-post]').length,1,'one native-style share action');
postFixtures=[];
// Full native catalogues; no abbreviated or invented replacement text.
const azkar=JSON.parse(read('knowledge/azkar.json'));assert.equal(azkar.find(g=>g.id==='morning').entries.length,34);assert.equal(azkar.find(g=>g.id==='evening').entries.length,30);
w.AttaWeb.navigate('azkarPage');await tick();assert.equal(d.querySelectorAll('[data-dhikr-group]').length,6);
const counter=d.querySelector('[data-dhikr-count]');counter.click();assert.ok(counter.textContent.startsWith('1 /'));
d.querySelector('[data-dhikr-reset]').click();assert.ok(d.querySelector('[data-dhikr-count]').textContent.startsWith('0 /'));
w.AttaWeb.navigate('hadithPage');await tick();assert.equal(d.querySelectorAll('[data-hadith-book]').length,4);
d.querySelector('[data-hadith-book="nawawi"]').click();await tick();assert.equal(d.querySelectorAll('[data-share-hadith]').length,32);
requests=[];d.querySelector('[data-share-hadith]').click();await tick();
assert.equal(d.querySelector('.active-page').id,'homePage');assert.ok(d.getElementById('postText').value.includes('الأربعون النووية'));
assert.ok(!requests.some(r=>r.opts.method==='POST'),'share prepares draft, never silently publishes');
w.AttaWeb.navigate('hadithPage');d.getElementById('hadithSearch').value='الأعمال بالنيات';d.getElementById('hadithSearchBtn').click();await tick();assert.ok(d.querySelectorAll('[data-share-hadith]').length>0,'full-book normalized search returns real hadith');
w.AttaWeb.navigate('quranPage');assert.equal(d.querySelectorAll('#quranMount iframe').length,1);w.AttaWeb.navigate('homePage');w.AttaWeb.navigate('quranPage');assert.equal(d.querySelectorAll('#quranMount iframe').length,1);
w.AttaWeb.navigate('sportsPage');await tick();assert.equal(d.querySelectorAll('#footballResults img').length,0,'channel/match text is escaped');
const generated=path.join(pub,'knowledge/generated');
const manifest=JSON.parse(read('knowledge/generated/hadith/books.json'));assert.equal(manifest.total,14006);
let total=0;for(const book of manifest.books){let count=0;for(let page=0;page<Math.ceil(book.count/32);page++)count+=JSON.parse(read(`knowledge/generated/hadith/${book.id}/${page}.json`)).length;assert.equal(count,book.count);total+=count;}assert.equal(total,14006);
for(let p=1;p<=604;p++)assert.ok(fs.statSync(path.join(generated,'mushaf',String(p).padStart(3,'0')+'.svg')).size>1000);
const provenance=JSON.parse(read('knowledge/generated/provenance.json'));
assert.equal(provenance.azkarSourceSha256,crypto.createHash('sha256').update(fs.readFileSync(path.join(root,'android-native/app/src/main/java/com/alatta/nativeapp/AzkarCatalog.kt'))).digest('hex'));
assert.match(read('knowledge/generated/quran.html'),/linkedPage/);
assert.ok(!fs.existsSync(path.join(generated,'.export.db')));
assert.ok(read('sw.js').includes('u.pathname.startsWith("/knowledge/generated/")'));
console.log('PASS: native navigation/icons, invalid target recovery, safe session restore, GET/POST retry, 6 Azkar groups/counters, all 14006 hadiths/search/share, 604 Quran pages, escaped football, single reader, on-demand assets');
dom.window.close();
