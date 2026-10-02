const CACHE="opticore-pwa-v7";
const STATIC=[
  "/",
  "/index.html",
  "/professional-ui.css",
  "/professional-ui.js",
  "/live.js",
  "/smart-clean.js",
  "/cache-sync.js",
  "/connection-guard.js",
  "/resource-panel.js",
  "/resource-summary.js",
  "/manifest.webmanifest",
  "/pwa-install.js",
  "/icon-192.svg",
  "/icon-512.svg",
  "/camera-lab-pro.html",
  "/optimizer-pro.html"
];

self.addEventListener("install",event=>{
  event.waitUntil(
    caches.open(CACHE)
      .then(cache=>cache.addAll(STATIC))
      .then(()=>self.skipWaiting())
  );
});

self.addEventListener("activate",event=>{
  event.waitUntil(
    caches.keys()
      .then(keys=>Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k))))
      .then(()=>self.clients.claim())
  );
});

self.addEventListener("fetch",event=>{
  if(event.request.method!=="GET") return;
  const url=new URL(event.request.url);

  if(url.origin!==self.location.origin) return;

  if(url.pathname.startsWith("/api/") ||
     url.pathname.startsWith("/data/")) {
    return;
  }

  if(event.request.mode==="navigate") {
    event.respondWith(
      fetch(event.request)
        .then(response=>{
          const copy=response.clone();
          caches.open(CACHE).then(cache=>cache.put("/index.html",copy));
          return response;
        })
        .catch(()=>caches.match("/index.html"))
    );
    return;
  }

  if(["script","style"].includes(event.request.destination)){
    event.respondWith(
      fetch(event.request)
        .then(response=>{
          if(response && response.ok){
            const copy=response.clone();
            caches.open(CACHE).then(cache=>cache.put(event.request,copy));
          }
          return response;
        })
        .catch(()=>caches.match(event.request))
    );
    return;
  }

  event.respondWith(
    caches.match(event.request).then(cached=>{
      if(cached) return cached;
      return fetch(event.request).then(response=>{
        if(response && response.ok){
          const copy=response.clone();
          caches.open(CACHE).then(cache=>cache.put(event.request,copy));
        }
        return response;
      });
    })
  );
});
