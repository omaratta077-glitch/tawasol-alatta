const CACHE="tawasol-alatta-web14-6";
const STATIC_RE=/\.(?:css|js|png|jpg|jpeg|webp|svg|ico|wav|mp3|woff2?)$/i;

self.addEventListener("install",event=>{
  self.skipWaiting();
});

self.addEventListener("activate",event=>{
  event.waitUntil(
    caches.keys()
      .then(keys=>Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k))))
      .then(()=>self.clients.claim())
  );
});

self.addEventListener("fetch",event=>{
  const r=event.request;
  if(r.method!=="GET")return;
  const u=new URL(r.url);
  if(u.origin!==self.location.origin)return;
  if(u.pathname.startsWith("/api/")||u.pathname==="/ws"||u.pathname.startsWith("/media/"))return;

  // Generated Quran/Hadith data is fetched on demand; do not grow the shell cache by hundreds of MB.
  if(u.pathname.startsWith("/knowledge/generated/"))return;

  if(STATIC_RE.test(u.pathname)){
    event.respondWith((async()=>{
      const cache=await caches.open(CACHE);
      const hit=await cache.match(r);
      if(hit){
        event.waitUntil(fetch(r).then(resp=>{
          if(resp.ok) return cache.put(r,resp.clone());
        }).catch(()=>{}));
        return hit;
      }
      try{
        const resp=await fetch(r);
        if(resp.ok) await cache.put(r,resp.clone());
        return resp;
      }catch(e){
        return hit||Response.error();
      }
    })());
    return;
  }

  // HTML/navigation: network first so deployments appear immediately.
  event.respondWith(fetch(r).catch(()=>caches.match(r)));
});
