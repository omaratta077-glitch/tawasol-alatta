/* Retain screen light for an actively playing, visible YouTube embed only. */
(() => {
  'use strict';
  let lock = null;
  let playing = false;
  let trackedFrame = null;
  const visible = () => document.visibilityState === 'visible' &&
    !!document.querySelector('.atta-youtube-overlay:not([hidden]) .atta-youtube-frame iframe');
  async function sync() {
    if (!playing || !visible()) {
      if (lock) { const held = lock; lock = null; try { await held.release(); } catch (_) {} }
      return;
    }
    if (lock || !navigator.wakeLock?.request) return;
    try {
      lock = await navigator.wakeLock.request('screen');
      lock.addEventListener('release', () => { lock = null; }, {once:true});
    }
    catch (_) { /* Unsupported or blocked: normal screen dimming applies */ }
  }
  // Ask the existing iframe for player-state events. This only subscribes to
  // state; it never creates or replaces a YouTube player.
  function trackFrame() {
    const frame = document.querySelector('.atta-youtube-frame iframe');
    if (frame === trackedFrame) return;
    trackedFrame = frame;
    playing = false;
    sync();
    if (!frame) return;
    frame.addEventListener('load', () => {
      try { frame.contentWindow?.postMessage(JSON.stringify({event:'listening',id:'atta-screen'}),'*'); }
      catch (_) {}
    });
  }
  function start() {
    const observer = new MutationObserver(trackFrame);
    observer.observe(document.body,{childList:true,subtree:true});
    trackFrame();
  }
  if (document.readyState === 'loading')document.addEventListener('DOMContentLoaded',start,{once:true});
  else start();
  window.addEventListener('message', event => {
    try {
      if (!['www.youtube.com','youtube.com','www.youtube-nocookie.com',
        'youtube-nocookie.com'].includes(new URL(event.origin).hostname)) return;
      const frame = document.querySelector('.atta-youtube-frame iframe');
      if (!frame || event.source !== frame.contentWindow) return;
      const item = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
      if (item?.event === 'onStateChange') {
        playing = Number(item.info) === 1;
        sync();
      }
    } catch (_) { /* Unrelated messages */ }
  });
  document.addEventListener('visibilitychange', sync);
  document.addEventListener('click', event => {
    if (event.target.closest('.atta-youtube-close, .atta-yt-card')) {
      playing = false;
      sync();
    }
  });
})();
