/* مشغّل الويب للمصادر المباشرة المسموح بعرضها HLS/DASH، بلا اعتماد على تطبيقات أندرويد. */
(function (global) {
  'use strict';
  const libraries = {
    hls: 'https://cdn.jsdelivr.net/npm/hls.js@1.7.3/dist/hls.min.js',
    dash: 'https://cdnjs.cloudflare.com/ajax/libs/dashjs/5.2.1/modern/umd/dash.all.min.js'
  };
  const pending = Object.create(null);
  let current = null;
  const checkedUrl = value => {
    try {
      const u = new URL(value);
      return u.protocol === 'https:' && !u.username && !u.password && u.hostname ? u.href : null;
    } catch { return null; }
  };
  function ensureLibrary(kind) {
    const exists = kind === 'hls' ? global.Hls : global.dashjs;
    if (exists) return Promise.resolve();
    if (!pending[kind]) {
      pending[kind] = new Promise((resolve, reject) => {
        const s = document.createElement('script');
        s.src = libraries[kind];
        s.async = true;
        s.onload = () => (kind === 'hls' ? global.Hls : global.dashjs)
          ? resolve() : reject(new Error('لم تكتمل تهيئة مشغّل الفيديو.'));
        s.onerror = () => reject(new Error('تعذّر تحميل مكوّن تشغيل الفيديو. تحقق من الاتصال ثم حاول مجددًا.'));
        document.head.append(s);
      }).catch(error => { delete pending[kind]; throw error; });
    }
    return pending[kind];
  }
  function stop() {
    const last = current;
    if (!last) return;
    current = null;
    last.stopped = true;
    try { last.hls?.destroy(); } catch {}
    try { last.dash?.reset(); } catch {}
    try { last.video.pause(); } catch {}
    last.video.removeAttribute('src');
    try { last.video.load(); } catch {}
    last.status.textContent = 'تم إيقاف التشغيل.';
  }
  async function play(host, rawUrl, format, onFatal = null) {
    stop();
    const url = checkedUrl(rawUrl);
    if (!url || !['hls', 'dash'].includes(format)) {
      host.replaceChildren();
      const note = document.createElement('p');
      note.textContent = 'لا يوجد رابط بث ويب HTTPS صالح لهذه القناة.';
      host.append(note);
      return false;
    }
    const video = document.createElement('video');
    video.controls = true;
    video.playsInline = true;
    video.autoplay = true;
    video.preload = 'metadata';
    video.style.width = '100%';
    video.style.maxHeight = '65vh';
    video.style.background = '#080808';
    video.style.borderRadius = '12px';
    video.setAttribute('aria-label', 'مشغّل الرياضة داخل الموقع');
    const status = document.createElement('p');
    status.setAttribute('role', 'status');
    status.textContent = 'جاري تجهيز البث...';
    const stopButton = document.createElement('button');
    stopButton.type = 'button';
    stopButton.className = 'native-button';
    stopButton.textContent = 'إيقاف البث';
    stopButton.onclick = stop;
    host.append(video, status, stopButton);
    host.scrollIntoView?.({block:'center',behavior:'smooth'});
    const state = {video, status, hls: null, dash: null, stopped: false};
    current = state;
    let fatalReported = false;
    const setError = () => {
      if (state !== current || fatalReported) return;
      fatalReported = true;
      status.textContent = 'تعذّر تشغيل المصدر داخل المتصفح. قد يكون السبب CORS أو انتهاء صلاحية الرابط أو حماية البث.';
      if (typeof onFatal === 'function') onFatal();
    };
    const tryStart = () => {
      if (state !== current) return;
      video.play().then(() => {
        if (state === current) status.textContent = 'البث يعمل.';
      }).catch(() => {
        if (state === current) status.textContent = 'اضغط تشغيل من مشغّل الفيديو لبدء البث.';
      });
    };
    video.addEventListener('error', setError);
    video.addEventListener('playing', () => {
      if (state === current) status.textContent = 'البث يعمل.';
    });
    try {
      if (format === 'hls') {
        // hls.js يتعامل مع Chrome/Firefox؛ وSafari يستطيع الاستفادة من HLS المدمج.
        if (video.canPlayType('application/vnd.apple.mpegurl') && !global.MediaSource) {
          video.src = url;
          tryStart();
        } else {
          await ensureLibrary('hls');
          if (state !== current) return false;
          if (global.Hls?.isSupported()) {
            const hls = new global.Hls({enableWorker:true,lowLatencyMode:true,backBufferLength:30});
            state.hls = hls;
            hls.on(global.Hls.Events.ERROR, (_event, info) => {
              if (info?.fatal) setError();
            });
            hls.on(global.Hls.Events.MANIFEST_PARSED, tryStart);
            hls.attachMedia(video);
            hls.loadSource(url);
            status.textContent = 'جاري تحميل البث داخل الموقع...';
          } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = url;
            tryStart();
          } else {
            throw new Error('هذا المتصفح لا يدعم تشغيل HLS.');
          }
        }
      } else {
        await ensureLibrary('dash');
        if (state !== current) return false;
        if (!global.dashjs?.MediaPlayer) throw new Error('تعذّر تهيئة مشغّل DASH.');
        const dash = global.dashjs.MediaPlayer().create();
        state.dash = dash;
        dash.initialize(video, url, false);
        const errorEvent = global.dashjs.MediaPlayer.events?.ERROR;
        if (errorEvent) dash.on(errorEvent, setError);
        video.addEventListener('loadedmetadata', tryStart, {once:true});
        status.textContent = 'جاري تحميل البث داخل الموقع...';
      }
      return true;
    } catch (error) {
      if (state === current) status.textContent = error.message || 'تعذّر تجهيز المشغّل.';
      return false;
    }
  }
  // المصادر تعود من واجهة العطا الموثّقة للمستخدم، ولا تُحفظ في التخزين أو السجلات.
  function webSource(entry) {
    if (!entry || typeof entry !== 'object') return null;
    const url=checkedUrl(entry.url);
    if (!url) return null; // صفحات HTTPS لا تستطيع بث رابط HTTP غير آمن.
    const headers=entry.headers && typeof entry.headers==='object'?Object.keys(entry.headers):[];
    // المتصفح لا يسمح للتطبيق بفرض Origin أو Referer أو User-Agent المخصصة.
    // المصادر التي تشترط Referer/Origin ليست مناسبة لمشغّل الويب المباشر.
    if (headers.some(key => /^(origin|referer)$/i.test(key))) return null;
    const mime=String(entry.mimeType||'').toLowerCase();
    const pathname=new URL(url).pathname.toLowerCase();
    const format=mime.includes('mpegurl')||pathname.endsWith('.m3u8')?'hls':
      mime.includes('dash+xml')||pathname.endsWith('.mpd')?'dash':null;
    return format?{url,format}:null;
  }
  async function playSources(host, sources) {
    const usable=Array.isArray(sources)?sources.slice(0,10).map(webSource).filter(Boolean):[];
    if(!usable.length) {
      stop();host.replaceChildren();
      const note=document.createElement('p');
      note.textContent='لم يصل رابط بث HTTPS يمكن للمتصفح تشغيله. قد تكون المصادر مخصصة لأندرويد فقط أو تحتاج إعدادات لا يسمح بها المتصفح.';
      host.append(note);
      return false;
    }
    let position=0;
    const next=async()=>{
      const candidate=usable[position++];
      if(!candidate){
        stop();host.replaceChildren();
        const note=document.createElement('p');
        note.setAttribute('role','alert');
        note.textContent='تعذّر تشغيل كل المصادر المتاحة داخل المتصفح. افحص صلاحية الروابط وCORS وحقوق عرض القناة على الويب.';
        host.append(note);
        return false;
      }
      const ok=await play(host,candidate.url,candidate.format,()=>{void next();});
      if(!ok && position<usable.length)return next();
      return ok;
    };
    return next();
  }
  global.AttaSportsPlayer = Object.freeze({play, playSources, stop, active: () => !!current});
})(window);
