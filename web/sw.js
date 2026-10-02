const CACHE="opticore-pwa-v13-pages";
const ROOT=new URL("./",self.location.href).href;
const INDEX=new URL("./index.html",self.location.href).href;
const STATIC=[
  ROOT,
  INDEX,
  new URL("./professional-ui.css",self.location.href).href,
  new URL("./professional-ui.js",self.location.href).href,
  new URL("./live.js",self.location.href).href,
  new URL("./smart-clean.js",self.location.href).href,
  new URL("./cache-sync.js",self.location.href).href,
  new URL("./connection-guard.js",self.location.href).href,
  new URL("./resource-panel.js",self.location.href).href,
  new URL("./resource-summary.js",self.location.href).href,
  new URL("./manifest.webmanifest",self.location.href).href,
  new URL("./pwa-install.js",self.location.href).href,
  new URL("./icon-192.svg",self.location.href).href,
  new URL("./icon-512.svg",self.location.href).href,
  new URL("./camera-lab-pro.html",self.location.href).href,
  new URL("./optimizer-pro.html",self.location.href).href
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

  if(url.pathname.includes("/api/") ||
     url.pathname.includes("/data/")) {
    return;
  }

  if(event.request.mode==="navigate") {
    event.respondWith(
      fetch(event.request)
        .then(response=>{
          const copy=response.clone();
          caches.open(CACHE).then(cache=>cache.put(INDEX,copy));
          return response;
        })
        .catch(()=>caches.match(INDEX))
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
