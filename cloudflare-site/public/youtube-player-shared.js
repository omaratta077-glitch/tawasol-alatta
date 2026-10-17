/* Single YouTube embed factory shared by the website and the Android WebView page. */
(function(global){
  'use strict';
  function create(id,title){
    if(!/^[A-Za-z0-9_-]{11}$/.test(id))throw new Error('Invalid YouTube ID');
    const frame=document.createElement('iframe');
    frame.src='https://www.youtube-nocookie.com/embed/'+encodeURIComponent(id)+'?playsinline=1&rel=1&enablejsapi=1&origin='+encodeURIComponent(location.origin);
    frame.title=title||'فيديو YouTube';
    frame.allow='accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share';
    frame.referrerPolicy='strict-origin-when-cross-origin';
    frame.allowFullscreen=true;
    return frame;
  }
  global.AttaYouTubeEmbed=Object.freeze({create});
})(window);
