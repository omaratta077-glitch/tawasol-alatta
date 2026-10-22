/* Website modules reuse native data, the existing composer, API and single YouTube player. */
(()=>{
'use strict';
const $=id=>document.getElementById(id), bridge=window.AttaWeb;
const base='/knowledge/generated/';
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon=n=>window.AttaIcons.icon(n);
const cache=new Map();
async function json(url){
 if(cache.has(url))return cache.get(url);
 const pending=(async()=>{const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),20000);try{const r=await fetch(url,{signal:controller.signal});if(!r.ok)throw Error('تعذر تحميل البيانات ('+r.status+')');return await r.json();}catch(e){throw Error(e.name==='AbortError'?'تأخر تحميل البيانات. أعد المحاولة.':e.message);}finally{clearTimeout(timer);}})();
 cache.set(url,pending);if(cache.size>80)cache.delete(cache.keys().next().value);
 try{return await pending;}catch(e){cache.delete(url);throw e;}
}
function error(target,e,retry){const node=$(target);node.innerHTML=`<div class="native-error" role="alert">${esc(e.message||'تعذر التحميل')} <button class="native-button">إعادة المحاولة</button></div>`;node.querySelector('button').onclick=retry;}
function loading(target){$(target).innerHTML='<div class="native-load-state" role="status">جاري التحميل...</div>';}
const normalize=s=>String(s).normalize('NFKC').replace(/[\u0610-\u061a\u064b-\u065f\u0670\u06d6-\u06ed]/g,'').replace(/[أإآ]/g,'ا').replace(/ى/g,'ي').replace(/ـ/g,' ').toLowerCase();
const safeUrl=value=>{try{const url=new URL(value);return url.protocol==='https:'&&!url.username&&!url.password?url.href:'';}catch{return '';}};
document.addEventListener('click',e=>{
 const go=e.target.closest('[data-web-go]');if(go)bridge.navigate(go.dataset.webGo);
});
$('webLogout').onclick=()=>$('logoutBtn').click();
$('webPasswordForm').onsubmit=async e=>{
 e.preventDefault();const submit=e.target.querySelector('button');submit.disabled=true;
 try{await bridge.changePassword($('webOldPassword').value,$('webNewPassword').value,$('webConfirmPassword').value);e.target.reset();}
 catch(error){$('webPasswordStatus').textContent=error.message;}finally{submit.disabled=false;}
};

let quranFrame=null;
function openQuran(){if(quranFrame)return;quranFrame=document.createElement('iframe');quranFrame.title='المصحف الشريف';quranFrame.className='native-reader-frame';quranFrame.src=base+'quran.html';$('quranMount').append(quranFrame);}
$('shareQuranBtn').onclick=()=>{
 let page=1;try{page=Number(localStorage.getItem('atta-quran-last-page-v1'))||1;}catch{}
 $('quranFrom').value=page;$('quranTo').value=page;$('quranPart').value='';$('quranShareForm').classList.remove('hidden');$('quranShareForm').scrollIntoView({block:'center',behavior:'smooth'});
};
$('cancelQuranPost').onclick=()=>$('quranShareForm').classList.add('hidden');
$('quranPart').onchange=async()=>{if(!$('quranPart').value)return;try{const index=await json(base+'mushaf/index.json'),part=Number($('quranPart').value);$('quranFrom').value=index.juzStart[part];$('quranTo').value=part===30?604:index.juzStart[part+1]-1;}catch(e){$('quranShareStatus').textContent=e.message;}};
async function pagePng(number,index){
 const info=index.pages[number],image=new Image();image.src=base+'mushaf/'+String(number).padStart(3,'0')+'.svg';await image.decode();
 const canvas=document.createElement('canvas');canvas.width=900;canvas.height=Math.round(900*info.height/info.width);const ctx=canvas.getContext('2d');
 ctx.fillStyle='#fff9e8';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.drawImage(image,0,0,canvas.width,canvas.height);
 if(number>2){
  const font=new FontFace('AttaQuranWeb',`url(${base}fonts/AmiriQuran-Regular.ttf)`);await font.load();document.fonts.add(font);
  ctx.font=`${Math.round(canvas.width*.045)}px AttaQuranWeb`;ctx.fillStyle='#884300';ctx.textAlign='center';ctx.textBaseline='middle';
  for(const header of info.headers||[])ctx.fillText(header.nameArabic,canvas.width/2,header.headerPosition/info.height*canvas.height);
 }
 return canvas.toDataURL('image/png');
}
$('prepareQuranPost').onclick=async()=>{
 const button=$('prepareQuranPost');button.disabled=true;$('quranShareStatus').textContent='جاري تجهيز الصفحة...';
 try{
  const from=Number($('quranFrom').value),to=Number($('quranTo').value);
  if(!Number.isInteger(from)||!Number.isInteger(to)||from<1||to>604||to<from||to-from>60)throw Error('اختر صفحات بين 1 و604، بحد أقصى 61 صفحة في الورد.');
  const index=await json(base+'mushaf/index.json'),image=await pagePng(from,index);
  const title=$('quranPart').value?'الجزء '+$('quranPart').value:'ورد من القرآن الكريم';
  const links=Array.from({length:to-from+1},(_,i)=>'صفحة '+(from+i)+': '+location.origin+base+'quran.html?page='+(from+i));
  bridge.preparePost(title+' • الصفحات '+from+'–'+to+'\n'+links.join('\n'),image);
  $('quranShareForm').classList.add('hidden');$('quranShareStatus').textContent='';
 }catch(e){$('quranShareStatus').textContent=e.message;}finally{button.disabled=false;}
};

const hadith={book:null,page:0,matches:null,serial:0,querySerial:0,books:[]};
async function openHadith(){
 if(hadith.books.length)return;loading('hadithBooks');
 try{const data=await json(base+'hadith/books.json');hadith.books=data.books;
  $('hadithBooks').innerHTML=data.books.map(b=>`<button class="native-button" data-hadith-book="${esc(b.id)}">${icon('book-text')}<span>${esc(b.title)}<br><small>${b.count} حديث</small></span></button>`).join('');
  $('hadithBooks').querySelectorAll('button').forEach(b=>b.onclick=()=>{hadith.querySerial++;hadith.book=data.books.find(x=>x.id===b.dataset.hadithBook);hadith.page=0;hadith.matches=null;$('hadithSearch').value='';$('hadithControls').classList.remove('hidden');$('hadithBookTitle').textContent=hadith.book.title;loadHadith();});
 }catch(e){error('hadithBooks',e,openHadith);}
}
async function hadithPage(page){return json(base+'hadith/'+hadith.book.id+'/'+page+'.json');}
async function loadHadith(){
 const serial=++hadith.serial;loading('hadithResults');$('hadithPrev').disabled=true;$('hadithNext').disabled=true;
 try{
  const size=32,total=hadith.matches?hadith.matches.length:hadith.book.count;
  const last=Math.max(0,Math.ceil(total/size)-1);hadith.page=Math.max(0,Math.min(hadith.page,last));
  let rows;
  if(hadith.matches){const selection=hadith.matches.slice(hadith.page*size,(hadith.page+1)*size),pages=[...new Set(selection.map(r=>r.page))];const chunks=await Promise.all(pages.map(p=>hadithPage(p)));const numbers=new Set(selection.map(x=>x.number));rows=chunks.flat().filter(r=>numbers.has(r.number));}
  else rows=await hadithPage(hadith.page);
  if(serial!==hadith.serial)return;
  $('hadithResults').innerHTML=rows.length?rows.map(r=>`<article class="native-card"><h3>${esc(hadith.book.title)} • ${r.number}</h3><small>الباب ${r.section}</small><p class="native-reading">${esc(r.body)}</p><button class="native-button" data-share-hadith="${r.number}">${icon('share')}مشاركة كمنشور</button></article>`).join(''):'<div class="native-card">لا توجد أحاديث مطابقة للبحث.</div>';
  $('hadithResults').querySelectorAll('[data-share-hadith]').forEach(b=>b.onclick=()=>{const row=rows.find(r=>r.number===Number(b.dataset.shareHadith));bridge.preparePost(row.body+'\n\n'+hadith.book.title+' • الحديث '+row.number+' • الباب '+row.section+'\n'+hadith.book.edition);});
  $('hadithPageNumber').textContent=(hadith.page+1)+' / '+(last+1)+' • '+total+' حديث';$('hadithPrev').disabled=hadith.page===0;$('hadithNext').disabled=hadith.page>=last;
 }catch(e){if(serial===hadith.serial)error('hadithResults',e,loadHadith);}
}
$('hadithPrev').onclick=()=>{hadith.page--;loadHadith();};$('hadithNext').onclick=()=>{hadith.page++;loadHadith();};
$('hadithSearchBtn').onclick=async()=>{
 const book=hadith.book;if(!book)return;const serial=++hadith.querySerial;const query=normalize($('hadithSearch').value).trim();loading('hadithResults');
 try{const matches=query?(await json(base+'hadith/'+book.id+'/search.json')).filter(r=>query.split(/\s+/).every(t=>normalize(r.search).includes(t))):null;if(hadith.book!==book||serial!==hadith.querySerial)return;hadith.matches=matches;hadith.page=0;loadHadith();}
 catch(e){error('hadithResults',e,()=>$('hadithSearchBtn').click());}
};
$('hadithSearch').onkeydown=e=>{if(e.key==='Enter')$('hadithSearchBtn').click();};
$('hadithGo').onclick=async()=>{try{if(!hadith.book)return;const book=hadith.book,index=await json(base+'hadith/'+book.id+'/search.json');if(hadith.book!==book)return;const found=index.find(r=>r.number===Number($('hadithNumber').value));if(!found)throw Error('رقم الحديث غير موجود في هذه النسخة من الكتاب.');hadith.matches=null;hadith.page=found.page;$('hadithSearch').value='';await loadHadith();}catch(e){error('hadithResults',e,()=>$('hadithGo').click());}};

let dhikrGroups=[],dhikrGroup=null;
function countKey(entry){return 'atta-web-dhikr:'+bridge.user().username+':'+new Date().toLocaleDateString('en-CA')+':'+dhikrGroup.id+':'+entry.id;}
function readCount(entry){try{return Math.min(entry.target,Math.max(0,Number(localStorage.getItem(countKey(entry)))||0));}catch{return 0;}}
function renderAzkar(){
 if(!dhikrGroup)return;const q=normalize($('azkarSearch').value);
 const entries=dhikrGroup.entries.filter(d=>normalize(d.title+' '+d.text).includes(q));
 $('azkarResults').innerHTML=`<h3>${esc(dhikrGroup.title)}</h3>`+entries.map(d=>`<article class="native-card"><h3>${esc(d.title)}</h3><p class="native-reading">${esc(d.text)}</p><p class="native-muted">${esc(d.source)}</p>${d.note?`<p class="native-muted">${esc(d.note)}</p>`:''}<div class="native-toolbar"><button class="native-button native-count" data-dhikr-count="${esc(d.id)}">${readCount(d)} / ${d.target}</button><button class="native-button" data-dhikr-reset="${esc(d.id)}">إعادة</button><button class="native-button" data-dhikr-share="${esc(d.id)}">${icon('share')}مشاركة</button></div></article>`).join('');
 $('azkarResults').querySelectorAll('[data-dhikr-count]').forEach(b=>b.onclick=()=>{const d=entries.find(d=>d.id===b.dataset.dhikrCount),n=Math.min(d.target,readCount(d)+1);try{localStorage.setItem(countKey(d),n);}catch{}b.textContent=n+' / '+d.target;});
 $('azkarResults').querySelectorAll('[data-dhikr-reset]').forEach(b=>b.onclick=()=>{const d=entries.find(d=>d.id===b.dataset.dhikrReset);try{localStorage.removeItem(countKey(d));}catch{}renderAzkar();});
 $('azkarResults').querySelectorAll('[data-dhikr-share]').forEach(b=>b.onclick=()=>{const d=entries.find(d=>d.id===b.dataset.dhikrShare);bridge.preparePost(d.title+'\n\n'+d.text+'\n\n'+d.source+(d.note?'\n'+d.note:''));});
}
async function openAzkar(){
 if(dhikrGroups.length){renderAzkar();return;}loading('azkarGroups');
 try{dhikrGroups=await json('/knowledge/azkar.json');$('azkarGroups').innerHTML=dhikrGroups.map(g=>`<button class="native-button" data-dhikr-group="${esc(g.id)}">${icon('hand-heart')}<span>${esc(g.title)}<br><small>${g.entries.length} ذكر</small></span></button>`).join('');$('azkarGroups').querySelectorAll('button').forEach(b=>b.onclick=()=>{dhikrGroup=dhikrGroups.find(g=>g.id===b.dataset.dhikrGroup);$('azkarSearch').value='';renderAzkar();});dhikrGroup=dhikrGroups[0];renderAzkar();}
 catch(e){error('azkarGroups',e,openAzkar);}
}
$('azkarSearch').oninput=renderAzkar;
let voiceAudio=null;
$('loadAdhanVoices').onclick=async()=>{loading('webAdhanVoices');try{const data=await json('/api/adhan-voices');$('webAdhanVoices').innerHTML=(data.voices||[]).map(v=>`<button class="native-button" data-adhan-id="${esc(v.id)}">${esc(v.name)} • ${esc(v.mosque||'')}</button>`).join('')||'<p>لا توجد تسجيلات مضافة من الإدارة بعد.</p>';$('webAdhanVoices').querySelectorAll('button').forEach(b=>b.onclick=()=>{const voice=data.voices.find(v=>v.id===b.dataset.adhanId);if(!/^\/media\/adhan\/[a-zA-Z0-9.-]+$/.test(voice.url))return;voiceAudio?.pause();voiceAudio=new Audio(voice.url);voiceAudio.play().catch(e=>error('webAdhanVoices',e,()=>$('loadAdhanVoices').click()));});}catch(e){error('webAdhanVoices',e,()=>$('loadAdhanVoices').click());}};

let football=null;
function renderFootball(){
 if(!football)return;const q=normalize($('footballSearch').value),day=$('footballDate').value;
 const matches=(football.matches||[]).filter(m=>normalize(m.home+' '+m.away+' '+m.league).includes(q)&&(!day||new Date(m.startsAt).toLocaleDateString('en-CA')===day)).sort((a,b)=>a.startsAt-b.startsAt);
 const channels=(football.channels||[]).filter(c=>c.enabled);
 $('footballResults').innerHTML='<h3>المباريات</h3>'+ (matches.map(m=>`<article class="native-card"><h3>${esc(m.home)} — ${esc(m.away)}</h3><p>${esc(m.league)} • ${esc(new Date(m.startsAt).toLocaleString('ar'))}</p><p>${esc(m.homeScore??'–')} : ${esc(m.awayScore??'–')} • ${esc({live:'مباشر',finished:'انتهت',scheduled:'لم تبدأ',postponed:'مؤجلة',cancelled:'ملغاة'}[m.status]||m.status)}</p>${m.channelId&&channels.some(c=>c.id===m.channelId)?`<button class="native-button" data-football-channel="${esc(m.channelId)}">عرض القناة</button>`:''}</article>`).join('')||'<div class="native-card">لا توجد مباريات في هذا الموعد.</div>')+'<h3>القنوات</h3>'+channels.map(c=>`<article class="native-card"><h3>${esc(c.name)}</h3><p class="native-muted">${esc(c.provider)} • ${esc(c.category)}</p><button class="native-button" data-football-channel="${esc(c.id)}">فتح البث</button></article>`).join('')+'<div id="footballPlayer"></div>';
 $('footballResults').querySelectorAll('[data-football-channel]').forEach(b=>b.onclick=()=>{
  const channel=channels.find(c=>c.id===b.dataset.footballChannel),url=safeUrl(channel?.streamUrl);if(!url)return;
  const host=$('footballPlayer');host.replaceChildren();const title=document.createElement('h3');title.textContent=channel.name;host.append(title);
  // Use the existing official YouTube section, never a second YouTube player.
  if(channel.format==='youtube'){bridge.navigate('youtubePage');$('attaYouTubeBtn').click();const field=document.querySelector('.atta-youtube-form input');if(field){field.value=url;field.dispatchEvent(new Event('input',{bubbles:true}));}return;}
  if(channel.format==='hls'){const video=document.createElement('video');video.controls=true;video.playsInline=true;video.preload='none';video.src=url;video.style.width='100%';host.append(video);}
  const link=document.createElement('a');link.href=url;link.target='_blank';link.rel='noopener noreferrer';link.className='native-button';link.textContent=channel.format==='external'?'فتح المصدر الرسمي':'فتح في مشغل يدعم المصدر';host.append(link);host.scrollIntoView({block:'center',behavior:'smooth'});
 });
}
async function openFootball(){loading('footballResults');try{const r=await fetch('/api/football',{cache:'no-store'});if(!r.ok)throw Error('تعذر تحميل المباريات ('+r.status+')');football=await r.json();renderFootball();}catch(e){error('footballResults',e,openFootball);}}
$('footballSearch').oninput=renderFootball;$('footballDate').onchange=renderFootball;$('footballRefresh').onclick=openFootball;

document.addEventListener('atta:page',e=>{
 const id=e.detail.pageId;
 if(id!=='azkarPage')voiceAudio?.pause();
 if(id==='quranPage')openQuran();
 if(id==='hadithPage')openHadith();
 if(id==='azkarPage')openAzkar();
 if(id==='sportsPage')openFootball();
 if(id==='morePage')$('webAdminGo').hidden=!['super_admin','moderator'].includes(bridge.user().role);
});
})();
