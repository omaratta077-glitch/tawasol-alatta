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
overlay.innerHTML=`<section class="atta-youtube-panel" dir="rtl"><div class="atta-youtube-heading"><h2>▶ فيديوهات يوتيوب</h2><button class="atta-youtube-close" type="button" aria-label="إغلاق">×</button></div><form class="atta-youtube-form"><input type="search" placeholder="ابحث عن فيديو أو قناة" aria-label="ابحث في يوتيوب" maxlength="100"><button type="submit">بحث</button></form><div class="atta-yt-topics"><button type="button" data-q="">الرائجة</button><button type="button" data-q="رياضة">رياضة</button><button type="button" data-q="تقنية">تقنية</button><button type="button" data-q="أخبار">أخبار</button><button type="button" data-q="قرآن كريم">قرآن كريم</button></div><p class="atta-youtube-error" role="status" aria-live="polite"></p><div class="atta-youtube-frame" id="attaYouTubeFrame"><div class="atta-yt-placeholder">اختر فيديو من النتائج لتشغيله هنا</div></div><div class="atta-yt-results" aria-live="polite"></div><button class="atta-yt-more" type="button" hidden>عرض المزيد</button><p class="atta-youtube-note">التشغيل عبر مشغّل YouTube الرسمي؛ قد تظهر إعلانات يوتيوب. النتائج العامة ليست اقتراحات حسابك الشخصية.</p></section>`;
document.body.appendChild(overlay);
const input=overlay.querySelector('input');
const frame=overlay.querySelector('#attaYouTubeFrame');
const error=overlay.querySelector('.atta-youtube-error');
const results=overlay.querySelector('.atta-yt-results');
const more=overlay.querySelector('.atta-yt-more');
let query='',nextPageToken='',loading=false,loadedOnce=false,seq=0,activeController=null;
function videoId(value){
 try{const u=new URL(value);const h=u.hostname.toLowerCase();let id='';if(['youtu.be','www.youtu.be'].includes(h))id=u.pathname.split('/')[1]||'';else if(['youtube.com','www.youtube.com','m.youtube.com','music.youtube.com','youtube-nocookie.com','www.youtube-nocookie.com'].includes(h)){if(u.pathname==='/watch')id=u.searchParams.get('v')||'';else if(/^\/(shorts|embed|live)\//.test(u.pathname))id=u.pathname.split('/')[2]||'';}return /^[a-zA-Z0-9_-]{11}$/.test(id)?id:null;}catch{return null;}
}
function play(id){if(!/^[a-zA-Z0-9_-]{11}$/.test(id))return;const iframe=document.createElement('iframe');iframe.src='https://www.youtube-nocookie.com/embed/'+encodeURIComponent(id)+'?playsinline=1&rel=1';iframe.title='مشغّل فيديو يوتيوب';iframe.allow='accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share';iframe.allowFullscreen=true;iframe.referrerPolicy='strict-origin-when-cross-origin';frame.replaceChildren(iframe);frame.scrollIntoView({behavior:'smooth',block:'nearest'});}
async function load(append=false){
 if(append && loading)return;
 if(!append){
  activeController?.abort();
  nextPageToken='';
 }
 loading=true;const requestId=++seq;
 const controller=new AbortController();activeController=controller;
 error.textContent='جاري تحميل الفيديوهات...';more.hidden=true;
 if(!append)results.replaceChildren();
 try{
  const p=new URLSearchParams();if(query)p.set('q',query);if(append&&nextPageToken)p.set('pageToken',nextPageToken);
  const r=await fetch('/api/youtube?'+p.toString(),{signal:controller.signal,cache:'no-store'});const data=await r.json().catch(()=>({}));
  if(requestId!==seq)return;
  if(!r.ok||!data.ok)throw new Error(data.error||'تعذر تحميل الفيديوهات');
  nextPageToken=data.nextPageToken||'';
  for(const v of data.items||[]){
   if(!/^[a-zA-Z0-9_-]{11}$/.test(String(v.id||'')))continue;
   const card=document.createElement('button');card.type='button';card.className='atta-yt-card';
   const img=document.createElement('img');img.loading='lazy';img.alt='';img.referrerPolicy='no-referrer';img.src=v.thumbnail||('https://i.ytimg.com/vi/'+v.id+'/mqdefault.jpg');img.onerror=()=>{if(!img.dataset.fallback){img.dataset.fallback='1';img.src='https://img.youtube.com/vi/'+v.id+'/hqdefault.jpg';}};
   const info=document.createElement('span');info.className='atta-yt-card-info';
   const title=document.createElement('strong');title.textContent=v.title||'فيديو';
   const channel=document.createElement('small');channel.textContent=v.channel||'';
   info.append(title,channel);card.append(img,info);card.addEventListener('click',()=>play(v.id));results.append(card);
  }
  error.textContent=results.children.length?'':'لا توجد نتائج';more.hidden=!nextPageToken;
 }catch(e){if(requestId===seq && e.name!=='AbortError')error.textContent=e.message||'حدث خطأ أثناء البحث';}
 finally{if(requestId===seq){loading=false;activeController=null;}}
}
function close(){activeController?.abort();seq++;loading=false;activeController=null;overlay.hidden=true;frame.replaceChildren();document.body.style.overflow=overlay.dataset.oldOverflow||'';btn.focus();}
btn.addEventListener('click',()=>{overlay.dataset.oldOverflow=document.body.style.overflow;document.body.style.overflow='hidden';overlay.hidden=false;if(!loadedOnce){loadedOnce=true;load();}else if(!results.children.length){load();}input.focus();});
overlay.querySelector('.atta-youtube-close').addEventListener('click',close);
overlay.addEventListener('click',e=>{if(e.target===overlay)close();});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!overlay.hidden)close();});
overlay.querySelector('form').addEventListener('submit',e=>{e.preventDefault();const q=input.value.trim();const id=videoId(q);if(id){error.textContent='';play(id);return;}if(!q){query='';load();return;}query=q;load();});
overlay.querySelectorAll('[data-q]').forEach(b=>b.addEventListener('click',()=>{query=b.dataset.q;input.value=query;load();}));
more.addEventListener('click',()=>{if(nextPageToken)load(true);});
const style=document.createElement('style');style.textContent=`
.atta-yt-topics{display:flex;flex-wrap:wrap;gap:8px;margin:10px 0}.atta-yt-topics button,.atta-yt-more{background:#181818;border:1px solid #bd932f;color:#f5d37b;border-radius:18px;padding:8px 14px;cursor:pointer}.atta-youtube-overlay[hidden]{display:none!important}.atta-yt-results{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:12px;margin:14px 0;max-height:48vh;overflow:auto}.atta-yt-card{background:#111;border:1px solid #4b3b1b;color:white;border-radius:12px;overflow:hidden;cursor:pointer;text-align:right;padding:0}.atta-yt-card img{width:100%;aspect-ratio:16/9;object-fit:cover;display:block}.atta-yt-card-info{display:flex;flex-direction:column;gap:5px;padding:9px}.atta-yt-card-info strong{font-size:13px;line-height:1.5}.atta-yt-card-info small{color:#d2b16c}.atta-yt-placeholder{display:grid;place-items:center;min-height:120px;color:#d9c58b}.atta-youtube-panel{overflow-y:auto;max-height:92vh}.atta-yt-more{display:block;margin:10px auto}.atta-yt-more[hidden]{display:none}
`;document.head.appendChild(style);
})();
