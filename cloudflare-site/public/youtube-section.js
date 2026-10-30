/* متصفح YouTube في Tawasol Alatta. يتولى المشغّل الرسمي لـYouTube الإعلانات. */
(()=>{
'use strict';
const btn=document.getElementById('attaYouTubeBtn');
if(!btn)return;
const overlay=document.createElement('div');
overlay.className='atta-youtube-overlay';
overlay.hidden=true;
overlay.setAttribute('role','dialog');
overlay.setAttribute('aria-modal','true');
overlay.setAttribute('aria-label','فيديوهات يوتيوب');
overlay.innerHTML=`<section class="atta-youtube-panel" dir="rtl"><div class="atta-youtube-heading"><h2>يوتيوب</h2><button class="atta-youtube-close" type="button" aria-label="إغلاق">×</button></div><form class="atta-youtube-form"><input type="search" placeholder="ابحث عن فيديو أو قناة" aria-label="ابحث في يوتيوب" maxlength="100"><button type="submit">بحث</button></form><h3 class="atta-yt-suggestions-title">اقتراحات</h3><div class="atta-yt-topics"><button type="button" data-q="تلاوة القرآن الكريم">قرآن</button><button type="button" data-q="أحاديث نبوية صحيحة شرح">أحاديث</button><button type="button" data-q="أذكار الصباح والمساء">الأذكار</button><button type="button" data-q="كرة القدم ملخص مباريات">رياضة</button><button type="button" data-q="أخبار الجزيرة اليوم">أخبار</button></div><p class="atta-youtube-error" role="status" aria-live="polite"></p><div class="atta-yt-results" aria-live="polite"></div><div class="atta-youtube-frame" id="attaYouTubeFrame" hidden></div><button class="atta-yt-more" type="button" hidden>عرض المزيد</button><p class="atta-youtube-note">التشغيل عبر مشغّل YouTube الرسمي؛ قد تظهر إعلانات يوتيوب. النتائج العامة ليست اقتراحات حسابك الشخصية.</p></section>`;
document.body.appendChild(overlay);
const panel=overlay.querySelector('.atta-youtube-panel');
const inlineMount=document.getElementById('youtubeInlineMount');
const mobile=window.matchMedia('(max-width:999px)');
let activePage=document.getElementById('appView')?.dataset.page||'homePage';
const inlineMode=()=>mobile.matches&&inlineMount&&!window.__attaAndroidYoutubeVideo;
function placePanel(){
 const inline=inlineMode();
 (inline?inlineMount:overlay).append(panel);
 panel.classList.toggle('atta-youtube-inline',!!inline);
 if(inline){overlay.hidden=true;document.body.style.overflow=overlay.dataset.oldOverflow||'';}
 if(inline&&activePage==='youtubePage')ensureLoaded();
}
function ensureLoaded(){if(!loadedOnce){loadedOnce=true;load();}}

const input=overlay.querySelector('input');
const frame=overlay.querySelector('#attaYouTubeFrame');
const error=overlay.querySelector('.atta-youtube-error');
const results=overlay.querySelector('.atta-yt-results');
const more=overlay.querySelector('.atta-yt-more');
let query='',nextPageToken='',loading=false,loadedOnce=false,seq=0;
// تخدم لوحة ومشغّل واحدان العرض المضمّن على الهاتف والنافذة على الكمبيوتر.
const suggestions=document.createElement('div');suggestions.className='atta-yt-topics atta-yt-readers';
const topicSuggestions={
 'قرآن':['محمد صديق المنشاوي','محمود خليل الحصري','عبدالباسط عبدالصمد','مصطفى إسماعيل','محمد رفعت','علي جابر','بدر التركي','ماهر المعيقلي','خالد الجليل'],
 'أحاديث':['سمير مصطفى','إسحاق الحويني','مصطفى العدوي','محمد حسين يعقوب','محمد حسان','عبدالرزاق البدر'],
 'الأذكار':['أذكار الصباح','أذكار المساء','أذكار النوم','أذكار بعد الصلاة','حصن المسلم'],
 'أخبار':['الجزيرة مباشر','العربية أخبار','الجزيرة نت']
};
function showSuggestions(topic){
 suggestions.replaceChildren();
 for(const name of topicSuggestions[topic]||[]){
  const button=document.createElement('button');button.type='button';button.textContent=name;
  button.onclick=()=>{query=name;input.value=name;load();};suggestions.append(button);
 }
 suggestions.hidden=!suggestions.children.length;
 panel.querySelectorAll('[data-q]').forEach(button=>button.setAttribute('aria-pressed',String(button.textContent===topic)));
}
showSuggestions('قرآن');
panel.querySelector('.atta-yt-topics').after(suggestions);
panel.querySelector('.atta-youtube-form').after(frame);
placePanel();mobile.addEventListener?.('change',placePanel);
document.addEventListener('atta:page',event=>{
 activePage=event.detail.pageId;
 if(inlineMode()&&activePage==='youtubePage')ensureLoaded();
 else if(inlineMode()){frame.hidden=true;frame.replaceChildren();}
});
function videoId(value){
 try{const u=new URL(value);const h=u.hostname.toLowerCase();let id='';if(['youtu.be','www.youtu.be'].includes(h))id=u.pathname.split('/')[1]||'';else if(['youtube.com','www.youtube.com','m.youtube.com','music.youtube.com','youtube-nocookie.com','www.youtube-nocookie.com'].includes(h)){if(u.pathname==='/watch')id=u.searchParams.get('v')||'';else if(/^\/(shorts|embed|live)\//.test(u.pathname))id=u.pathname.split('/')[2]||'';}return /^[a-zA-Z0-9_-]{11}$/.test(id)?id:null;}catch{return null;}
}
function play(id){
 if(!/^[a-zA-Z0-9_-]{11}$/.test(id))return;
 const iframe=window.AttaYouTubeEmbed.create(id,'مشغّل فيديو يوتيوب');
 frame.hidden=false;frame.replaceChildren(iframe);
 frame.scrollIntoView({behavior:'smooth',block:'start'});
}
async function load(append=false){
 if(loading)return;
 loading=true;const requestId=++seq;error.textContent='جاري تحميل الفيديوهات...';more.hidden=true;
 if(!append){results.replaceChildren();frame.hidden=true;frame.replaceChildren();}
 try{
  const p=new URLSearchParams();if(query)p.set('q',query);if(append&&nextPageToken)p.set('pageToken',nextPageToken);
  const r=await fetch('/api/youtube?'+p.toString());const data=await r.json().catch(()=>({}));
  if(requestId!==seq)return;
  if(!r.ok||!data.ok)throw new Error(data.error||'تعذر تحميل الفيديوهات');
  nextPageToken=data.nextPageToken||'';
  for(const v of data.items||[]){
   const card=document.createElement('button');card.type='button';card.className='atta-yt-card';
   const img=document.createElement('img');img.loading='lazy';img.alt='';img.referrerPolicy='no-referrer';img.src=v.thumbnail||'';
   const info=document.createElement('span');info.className='atta-yt-card-info';
   const title=document.createElement('strong');title.textContent=v.title||'فيديو';
   const channel=document.createElement('small');channel.textContent=v.channel||'';
   info.append(title,channel);card.append(img,info);card.addEventListener('click',()=>play(v.id));
   const item=document.createElement('article');item.className='atta-yt-result';item.append(card);
   const share=document.createElement('button');share.type='button';share.className='native-button atta-yt-share';share.setAttribute('aria-label','مشاركة الفيديو كمنشور في الرئيسية');share.title='مشاركة الفيديو كمنشور';share.innerHTML=window.AttaIcons?.icon?.('share')||'<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 10.5 6.8-4M8.6 13.5l6.8 4"/></svg>';
   share.onclick=()=>window.AttaWeb?.preparePost((v.title||'فيديو يوتيوب')+'\n[[alatta-youtube:'+v.id+']]');
   item.append(share);results.append(item);
  }
  error.textContent=results.children.length?'':'لا توجد نتائج';more.hidden=!nextPageToken;if(!append&&results.children.length)results.scrollIntoView({behavior:'smooth',block:'nearest'});
 }catch(e){if(requestId===seq)error.textContent=e.message||'حدث خطأ أثناء البحث';}
 finally{loading=false;}
}
function close(){overlay.hidden=true;frame.hidden=true;frame.replaceChildren();document.body.style.overflow=overlay.dataset.oldOverflow||'';btn.focus();}
btn.addEventListener('click',()=>{placePanel();if(inlineMode()){window.AttaWeb?.navigate('youtubePage');return;}overlay.dataset.oldOverflow=document.body.style.overflow;document.body.style.overflow='hidden';overlay.hidden=false;ensureLoaded();input.focus();});
panel.querySelector('.atta-youtube-close').addEventListener('click',close);
overlay.addEventListener('click',e=>{if(e.target===overlay)close();});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!overlay.hidden)close();});
panel.querySelector('form').addEventListener('submit',e=>{e.preventDefault();const q=input.value.trim();const id=videoId(q);if(id){error.textContent='';play(id);return;}if(!q){query='';load();return;}query=q;load();});
panel.querySelectorAll('[data-q]').forEach(b=>b.addEventListener('click',()=>{query=b.dataset.q;input.value=query;showSuggestions(b.textContent);load();}));
more.addEventListener('click',()=>{if(nextPageToken)load(true);});

})();
