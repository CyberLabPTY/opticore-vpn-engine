(function(){
  var deferredPrompt=null;
  var installed=false;

  if(false && localPanel()){
    try{
      if("serviceWorker" in navigator){
        navigator.serviceWorker.getRegistrations()
          .then(function(regs){regs.forEach(function(r){r.unregister();});})
          .catch(function(){});
      }
      if(window.caches && caches.keys){
        caches.keys()
          .then(function(keys){keys.forEach(function(k){caches.delete(k);});})
          .catch(function(){});
      }
    }catch(e){}
    document.addEventListener("DOMContentLoaded",function(){
      var old=document.getElementById("ocInstallIsland");
      if(old)old.remove();
      var tip=document.getElementById("ocPwaTip");
      if(tip)tip.remove();
    });
  }

  function standalone(){
    return window.matchMedia("(display-mode: standalone)").matches ||
           window.navigator.standalone===true;
  }

  function localPanel(){
    return (location.hostname==="127.0.0.1" || location.hostname==="localhost") &&
           (location.port==="8766" || location.port==="8080");
  }

  function style(){
    if(document.getElementById("oc-pwa-style")) return;
    var s=document.createElement("style");
    s.id="oc-pwa-style";
    s.textContent=
      "#ocInstallIsland{position:fixed;left:50%;bottom:18px;transform:translate(-50%,120px);opacity:0;width:min(430px,calc(100% - 24px));z-index:999999;display:flex;align-items:center;gap:11px;padding:11px 12px;border-radius:22px;background:rgba(5,20,31,.96);border:1px solid rgba(83,223,207,.28);box-shadow:0 18px 55px rgba(0,0,0,.42);backdrop-filter:blur(18px);transition:.35s ease;color:#eefaff;font-family:system-ui,-apple-system,Segoe UI,sans-serif}"+
      "#ocInstallIsland.show{transform:translate(-50%,0);opacity:1}"+
      "#ocInstallIsland .ocpi{width:42px;height:42px;flex:0 0 42px;border-radius:13px;background:#09202e;border:1px solid rgba(83,223,207,.25);display:grid;place-items:center;overflow:hidden}"+
      "#ocInstallIsland .ocpi img{width:38px;height:38px}"+
      "#ocInstallIsland .ocpt{min-width:0;flex:1}"+
      "#ocInstallIsland .ocpt b{display:block;font-size:12px;letter-spacing:.04em}"+
      "#ocInstallIsland .ocpt span{display:block;margin-top:3px;font-size:10px;color:#86a1b2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"+
      "#ocInstallIsland button{border:0;border-radius:13px;background:linear-gradient(135deg,#53dfcf,#5bc0eb);color:#041217;font-weight:900;font-size:10px;padding:10px 12px;min-height:38px}"+
      "#ocInstallIsland .occ{background:transparent;color:#7793a4;border:1px solid rgba(255,255,255,.07);padding:8px 10px}"+
      "#ocPwaTip{position:fixed;left:50%;bottom:88px;transform:translateX(-50%);z-index:1000000;width:min(410px,calc(100% - 30px));padding:13px 14px;border-radius:16px;background:#0b2030;border:1px solid #25475b;color:#eaf7ff;font:11px/1.5 system-ui;box-shadow:0 16px 45px rgba(0,0,0,.45)}";
    document.head.appendChild(s);
  }

  function hide(){
    var e=document.getElementById("ocInstallIsland");
    if(e)e.classList.remove("show");
  }

  function tip(){
    var old=document.getElementById("ocPwaTip");
    if(old)old.remove();
    var t=document.createElement("div");
    t.id="ocPwaTip";
    t.innerHTML="<b>Instalar OptiCore</b><br>Si Chrome no abre el instalador automáticamente, toca ⋮ y elige <b>Instalar aplicación</b> o <b>Añadir a pantalla principal</b>.";
    document.body.appendChild(t);
    setTimeout(function(){if(t)t.remove()},6500);
  }

  function create(){
    if(installed || standalone()) return;
    if(document.getElementById("ocInstallIsland")) return;
    style();
    var box=document.createElement("div");
    box.id="ocInstallIsland";
    box.innerHTML=
      "<div class=\"ocpi\"><img src=\"/icon-192.svg\" alt=\"\"></div>"+
      "<div class=\"ocpt\"><b>INSTALAR OPTICORE</b><span>Panel rápido · pantalla completa</span></div>"+
      "<button id=\"ocInstallBtn\">INSTALAR</button>"+
      "<button class=\"occ\" id=\"ocInstallClose\">×</button>";
    document.body.appendChild(box);
    requestAnimationFrame(function(){box.classList.add("show")});

    document.getElementById("ocInstallClose").onclick=hide;
    document.getElementById("ocInstallBtn").onclick=async function(){
      if(!deferredPrompt){tip();return;}
      deferredPrompt.prompt();
      try{
        var choice=await deferredPrompt.userChoice;
        if(choice && choice.outcome==="accepted") hide();
      }catch(e){}
      deferredPrompt=null;
    };
  }

  window.addEventListener("beforeinstallprompt",function(e){
    e.preventDefault();
    deferredPrompt=e;
    create();
  });

  window.addEventListener("appinstalled",function(){
    installed=true;
    localStorage.setItem("opticore-pwa-installed","1");
    hide();
  });

  if("serviceWorker" in navigator){
    window.addEventListener("load",function(){
      navigator.serviceWorker.register("/sw.js",{scope:"/"})
        .catch(function(e){console.log("OptiCore SW",e)});
    });
  }

  document.addEventListener("DOMContentLoaded",function(){
    if(!standalone()){
      setTimeout(function(){
        if(!document.getElementById("ocInstallIsland")) create();
      },2200);
    }
  });
})();
