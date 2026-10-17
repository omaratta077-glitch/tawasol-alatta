/* Tawas ol Alatta YouTube browser. YouTube's official player controls ads. */
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
overlay.innerHTML=`<section class="atta-youtube-panel" dir="rtl"><div class="atta-youtube-heading"><h2>▶ فيديوهات يوتيوب</h2><button class="atta-youtube-close" type="button" aria-label="إغلاق">×</button></div><form class="atta-youtube-form"><input type="search" placeholder="ابحث عن فيديو أو قناة" aria-label="ابحث في يوتيوب" maxlength="100"><button type="submit">بحث</button></form><div class="atta-yt-topics"><button type="button" data-q="تلاوة القرآن الكريم">قرآن</button><button type="button" data-q="أحاديث نبوية صحيحة شرح">أحاديث</button><button type="button" data-q="كرة القدم ملخص مباريات">رياضة</button><button type="button" data-q="أخبار الجزيرة اليوم">أخبار</button></div><p class="atta-youtube-error" role="status" aria-live="polite"></p><div class="atta-yt-results" aria-live="polite"></div><div class="atta-youtube-frame" id="attaYouTubeFrame" hidden></div><button class="atta-yt-more" type="button" hidden>عرض المزيد</button><p class="atta-youtube-note">التشغيل عبر مشغّل YouTube الرسمي؛ قد تظهر إعلانات يوتيوب. النتائج العامة ليست اقتراحات حسابك الشخصية.</p></section>`;
document.body.appendChild(overlay);
const input=overlay.querySelector('input');
const frame=overlay.querySelector('#attaYouTubeFrame');
const error=overlay.querySelector('.atta-youtube-error');
const results=overlay.querySelector('.atta-yt-results');
const more=overlay.querySelector('.atta-yt-more');
let query='',nextPageToken='',loading=false,loadedOnce=false,seq=0;
function videoId(value){
 try{const u=new URL(value);const h=u.hostname.toLowerCase();let id='';if(['youtu.be','www.youtu.be'].includes(h))id=u.pathname.split('/')[1]||'';else if(['youtube.com','www.youtube.com','m.youtube.com','music.youtube.com','youtube-nocookie.com','www.youtube-nocookie.com'].includes(h)){if(u.pathname==='/watch')id=u.searchParams.get('v')||'';else if(/^\/(shorts|embed|live)\//.test(u.pathname))id=u.pathname.split('/')[2]||'';}return /^[a-zA-Z0-9_-]{11}$/.test(id)?id:null;}catch{return null;}
}
function play(id){
 if(!/^[a-zA-Z0-9_-]{11}$/.test(id))return;
 const iframe=window.AttaYouTubeEmbed.create(id,'مشغّل فيديو يوتيوب');
 frame.hidden=false;frame.replaceChildren(iframe);
 frame.scrollIntoView({behavior:'smooth',block:'nearest'});
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
   info.append(title,channel);card.append(img,info);card.addEventListener('click',()=>play(v.id));results.append(card);
  }
  error.textContent=results.children.length?'':'لا توجد نتائج';more.hidden=!nextPageToken;if(!append&&results.children.length)results.scrollIntoView({behavior:'smooth',block:'nearest'});
 }catch(e){if(requestId===seq)error.textContent=e.message||'حدث خطأ أثناء البحث';}
 finally{loading=false;}
}
function close(){overlay.hidden=true;frame.hidden=true;frame.replaceChildren();document.body.style.overflow=overlay.dataset.oldOverflow||'';btn.focus();}
btn.addEventListener('click',()=>{overlay.dataset.oldOverflow=document.body.style.overflow;document.body.style.overflow='hidden';overlay.hidden=false;if(!loadedOnce){loadedOnce=true;load();}input.focus();});
overlay.querySelector('.atta-youtube-close').addEventListener('click',close);
overlay.addEventListener('click',e=>{if(e.target===overlay)close();});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!overlay.hidden)close();});
overlay.querySelector('form').addEventListener('submit',e=>{e.preventDefault();const q=input.value.trim();const id=videoId(q);if(id){error.textContent='';play(id);return;}if(!q){query='';load();return;}query=q;load();});
overlay.querySelectorAll('[data-q]').forEach(b=>b.addEventListener('click',()=>{query=b.dataset.q;input.value=query;load();}));
more.addEventListener('click',()=>{if(nextPageToken)load(true);});

})();
