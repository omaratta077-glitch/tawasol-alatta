/* هذا ليس مشغّلًا. إنه يتحكم بواجهة YouTube الحالية في الموقع والتي تعمل بالفعل.
 * ينشئ الموقع (youtube-section.js) إطار iframe الوحيد عبر المصنع المشترك.
 * لا ينشئ Android إطارًا ثانيًا أو YT.Player أو مستند HTML. */
(function () {
  'use strict';
  const videoId = '__ALATTA_VIDEO_ID__';
  const PREFIX = 'ALATTA_YT:';
  const log = (s) => console.log(PREFIX + s);
  if (!/^[A-Za-z0-9_-]{11}$/.test(videoId)) { log('ERROR:INVALID_VIDEO_ID'); return; }
  if (window.__attaAndroidYoutubeVideo === videoId) return;
  window.__attaAndroidYoutubeVideo = videoId;
  let attempts = 0;
  let ready = false;

  // راقب رسائل iframe الرسمية؛ فهذا لا ينشئ مشغّلًا آخر.
  window.addEventListener('message', function (e) {
    try {
      const host = new URL(e.origin).hostname;
      if (host !== 'www.youtube.com' && host !== 'www.youtube-nocookie.com' &&
          host !== 'youtube.com' && host !== 'youtube-nocookie.com') return;
      const d = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
      if (!d || typeof d !== 'object') return;
      if (d.event === 'onStateChange' && d.info === 1) log('PLAYING');
      if (d.event === 'onStateChange' && d.info === 2) log('PAUSED');
      if (d.event === 'onStateChange' && d.info === 0) log('ENDED');
      if (d.event === 'onError') log('PLAYER_ERROR:' + String(d.info));
    } catch (_) { /* non-YouTube messages are ignored */ }
  });

  function mount() {
    if (ready) return;
    const btn = document.getElementById('attaYouTubeBtn');
    if (!btn) return false;
    // دع معالج النقر الخاص بالموقع يعرض النافذة الموجودة أو ينشئها.
    btn.click();
    const overlay = document.querySelector('.atta-youtube-overlay');
    const form = overlay && overlay.querySelector('.atta-youtube-form');
    const input = form && form.querySelector('input');
    if (!overlay || !form || !input) return false;
    const panel = overlay.querySelector('.atta-youtube-panel');
    if (!panel) return false;
    const css = document.createElement('style');
    css.id = 'atta-android-youtube-style';
    css.textContent = `
      html,body{width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important;margin:0!important;padding:0!important}
      body > :not(.atta-youtube-overlay):not(script):not(style){display:none!important}
      .atta-youtube-overlay{position:fixed!important;inset:0!important;width:100%!important;height:100%!important;padding:0!important;margin:0!important;display:flex!important;background:#000!important}
      .atta-youtube-panel{box-sizing:border-box!important;width:100%!important;height:100%!important;min-height:100%!important;max-width:none!important;max-height:none!important;padding:0!important;margin:0!important;border:0!important;border-radius:0!important;overflow:hidden!important;box-shadow:none!important;background:#000!important}
      .atta-youtube-panel > :not(.atta-youtube-frame){display:none!important}
      .atta-youtube-frame{display:block!important;width:100%!important;height:100%!important;min-height:100%!important;max-height:none!important;margin:0!important;padding:0!important;border:0!important;border-radius:0!important;overflow:hidden!important;background:#000!important}
      .atta-youtube-frame iframe{position:absolute!important;inset:0!important;display:block!important;width:100%!important;height:100%!important;border:0!important}`;
    document.head.appendChild(css);
    overlay.hidden = false;
    // يضع الموقع عادةً التركيز في حقل البحث؛ لا تفتح لوحة المفاتيح فوق الفيديو.
    try { document.activeElement && document.activeElement.blur(); } catch (_) {}
    input.value = 'https://www.youtube.com/watch?v=' + videoId;
    form.dispatchEvent(new Event('submit', {bubbles:true, cancelable:true}));
    const iframe = overlay.querySelector('.atta-youtube-frame iframe');
    if (!iframe) { log('ERROR:SITE_DID_NOT_CREATE_FRAME'); return true; }
    iframe.addEventListener('load', () => {
      log('FRAME_LOADED');
      // فعّل الاشتراك بأحداث YouTube عبر postMessage إذا كانت الميزة مدعومة.
      try { iframe.contentWindow.postMessage(JSON.stringify({event:'listening',id:'atta'}), '*'); }
      catch (_) { /* YouTube may not allow event subscription */ }
    });
    ready = true;
    log('SITE_PLAYER_USED');
    return true;
  }
  function waitForSite() {
    try {
      if (mount()) return;
      attempts += 1;
      if (attempts >= 80) {log('ERROR:SITE_YOUTUBE_SCRIPT_NOT_READY'); return;}
      setTimeout(waitForSite, 125);
    } catch (e) { log('ERROR:' + String(e && e.message || e)); }
  }
  waitForSite();
})();
