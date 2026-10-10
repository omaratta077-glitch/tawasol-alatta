const CACHE="tawasol-alatta-v13-46";
self.addEventListener("install",()=>self.skipWaiting());
self.addEventListener("activate",event=>{event.waitUntil(caches.keys().then(keys=>Promise.all(keys.map(k=>caches.delete(k)))).then(()=>self.clients.claim()));});
self.addEventListener("fetch",event=>{const r=event.request;if(r.method!=="GET")return;const u=new URL(r.url);if(u.origin!==self.location.origin)return;if(u.pathname.startsWith("/api/")||u.pathname==="/ws"||u.pathname.startsWith("/media/"))return;event.respondWith(fetch(new Request(r,{cache:"no-store"})).catch(()=>caches.match(r)));});
