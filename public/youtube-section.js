/* AL-ATTA YouTube: official privacy-enhanced embed; no ad bypass. */
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
  overlay.innerHTML=`<section class="atta-youtube-panel"><div class="atta-youtube-heading"><h2>▶ فيديوهات يوتيوب</h2><button class="atta-youtube-close" type="button" aria-label="إغلاق">×</button></div><form class="atta-youtube-form"><input type="url" placeholder="الصق رابط فيديو يوتيوب هنا" aria-label="رابط فيديو يوتيوب" required><button type="submit">تشغيل</button></form><p class="atta-youtube-error" role="status" aria-live="polite"></p><div class="atta-youtube-frame" id="attaYouTubeFrame"><div style="height:100%;display:grid;place-items:center;color:#d9c58b;text-align:center;padding:16px">الصق رابط فيديو من يوتيوب واضغط تشغيل</div></div><p class="atta-youtube-note">يُعرض الفيديو داخل تواصل العطا باستخدام مشغّل YouTube الرسمي. قد تظهر إعلانات يوتيوب. البحث عن فيديوهات جديدة داخل التطبيق يحتاج إعداد YouTube Data API.</p></section>`;
  document.body.appendChild(overlay);
  const input=overlay.querySelector('input');
  const frame=overlay.querySelector('#attaYouTubeFrame');
  const error=overlay.querySelector('.atta-youtube-error');
  function videoId(value){
    try{
      const u=new URL(value);
      const h=u.hostname.toLowerCase();
      let id='';
      if(h==='youtu.be'||h==='www.youtu.be')id=u.pathname.split('/')[1]||'';
      else if(['youtube.com','www.youtube.com','m.youtube.com','music.youtube.com','youtube-nocookie.com','www.youtube-nocookie.com'].includes(h)){
        if(u.pathname==='/watch')id=u.searchParams.get('v')||'';
        else if(/^\/(shorts|embed|live)\//.test(u.pathname))id=u.pathname.split('/')[2]||'';
      }
      return /^[a-zA-Z0-9_-]{11}$/.test(id)?id:null;
    }catch{return null;}
  }
  function close(){overlay.hidden=true;frame.replaceChildren();document.body.style.overflow=overlay.dataset.oldOverflow||'';btn.focus();}
  btn.addEventListener('click',()=>{overlay.dataset.oldOverflow=document.body.style.overflow;document.body.style.overflow='hidden';overlay.hidden=false;input.focus();});
  overlay.querySelector('.atta-youtube-close').addEventListener('click',close);
  overlay.addEventListener('click',e=>{if(e.target===overlay)close();});
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!overlay.hidden)close();});
  overlay.querySelector('form').addEventListener('submit',e=>{
    e.preventDefault();const id=videoId(input.value.trim());
    if(!id){error.textContent='الرابط غير صالح. استخدم رابط فيديو من youtube.com أو youtu.be';return;}
    error.textContent='';const iframe=document.createElement('iframe');
    iframe.src='https://www.youtube-nocookie.com/embed/'+encodeURIComponent(id)+'?playsinline=1';
    iframe.title='مشغّل فيديو يوتيوب';iframe.allow='accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share';iframe.allowFullscreen=true;iframe.referrerPolicy='strict-origin-when-cross-origin';
    frame.replaceChildren(iframe);
  });
})();
