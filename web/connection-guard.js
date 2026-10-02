(function(){
  "use strict";

  var checking=false;
  var failures=0;
  var timer=null;
  var lastOnline=0;

  function stateEl(){
    return document.getElementById("oc-engine-state");
  }

  function setState(text,kind){
    var el=stateEl();
    if(!el)return;

    el.textContent=text;

    if(kind==="ok"){
      el.className="oc-state-ok";
    }else if(kind==="warn"){
      el.className="oc-state-warn";
    }else{
      el.className="oc-state-error";
    }
  }

  function delayForFailures(){
    if(failures===0)return document.hidden?60000:30000;
    if(failures===1)return 2000;
    if(failures===2)return 4000;
    if(failures===3)return 7000;
    return 12000;
  }

  function schedule(){
    clearTimeout(timer);
    timer=setTimeout(check,delayForFailures());
  }

  function check(){
    if(checking)return;

    checking=true;

    var controller=null;
    var timeout=null;

    if(typeof AbortController!=="undefined"){
      controller=new AbortController();
      timeout=setTimeout(function(){
        try{controller.abort()}catch(e){}
      },3500);
    }

    fetch("/data/device-status.json?health="+Date.now(),{
      cache:"no-store",
      signal:controller ? controller.signal : undefined
    })
    .then(function(r){
      if(!r.ok)throw new Error("HTTP "+r.status);
      return r.json();
    })
    .then(function(){
      failures=0;
      lastOnline=Date.now();
      setState("Motor local activo","ok");
      document.documentElement.classList.remove("oc-engine-offline");
      document.documentElement.classList.remove("oc-engine-reconnecting");
    })
    .catch(function(){
      failures++;

      document.documentElement.classList.add("oc-engine-reconnecting");

      if(failures<3){
        setState("Reconectando...","warn");
      }else{
        setState("Motor local sin conexión","error");
        document.documentElement.classList.add("oc-engine-offline");
      }
    })
    .then(function(){
      if(timeout)clearTimeout(timeout);
      checking=false;
      schedule();
    });
  }

  window.OptiCoreConnection={
    check:check,
    getFailures:function(){return failures;},
    getLastOnline:function(){return lastOnline;}
  };

  window.addEventListener("online",function(){
    if(location.hostname!=="127.0.0.1"&&location.hostname!=="localhost"){
      setState("Modo web público","warn");
      return;
    }
    failures=0;
    setState("Reconectando...","warn");
    setTimeout(check,150);
  });

  document.addEventListener("visibilitychange",function(){
    if(!document.hidden &&
       (location.hostname==="127.0.0.1"||location.hostname==="localhost")){
      setTimeout(check,250);
    }
  });

  function boot(){
    var localPanel=
      location.hostname==="127.0.0.1" ||
      location.hostname==="localhost";
    if(!localPanel){
      failures=0;
      setState("Modo web público","warn");
      document.documentElement.classList.remove("oc-engine-offline");
      document.documentElement.classList.remove("oc-engine-reconnecting");
      return;
    }
    setTimeout(check,900);
  }

  if(document.readyState==="loading"){
    document.addEventListener("DOMContentLoaded",boot);
  }else{
    boot();
  }

})();
