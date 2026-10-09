const CACHE = "tawasol-alatta-v13-30";
const STATIC = [
  "/",
  "/style.css?v=13.30",
  "/app.js?v=13.30",
  "/icons.js?v=13.30",
  "/logo-premium.png",
  "/logo.svg",
  "/icons/icon-192.png",
  "/icons/icon-512.png"
];

self.addEventListener("install", event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(STATIC)).catch(() => null));
  self.skipWaiting();
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
  );
  self.clients.claim();
});

self.addEventListener("fetch", event => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname === "/ws" || url.pathname.startsWith("/api/")) return;

  event.respondWith(
    fetch(req).then(res => {
      const copy = res.clone();
      if (res.ok) caches.open(CACHE).then(cache => cache.put(req, copy)).catch(() => {});
      return res;
    }).catch(() => caches.match(req).then(hit => hit || caches.match("/")))
  );
});
