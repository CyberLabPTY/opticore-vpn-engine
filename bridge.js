(function(){
  "use strict";

  var PUBLIC_HOST="cyberlabpty.github.io";
  var IS_PUBLIC=location.hostname===PUBLIC_HOST;

  if(!IS_PUBLIC)return;

  var LOCAL_API="http://127.0.0.1:8766/api/bridge-status";
  var BOOTSTRAP_API="http://127.0.0.1:8766/api/bridge-bootstrap";
  var TOKEN_KEY="opticore_bridge_token_v1";
  var token="";
  var lastData={};
  var busy=false;
  var failures=0;
  var timer=null;
  var linked=false;

  function byId(id){
    return document.getElementById(id);
  }

  function text(id,value){
    var el=byId(id);
    if(el)el.textContent=value;
  }

  function fmtRate(value){
    var v=Number(value)||0;
    if(v>=1048576)return (v/1048576).toFixed(2)+" MB/s";
    if(v>=1024)return (v/1024).toFixed(1)+" KB/s";
    return Math.round(v)+" B/s";
  }

  function fmtBytes(value){
    var v=Number(value)||0;
    if(v>=1073741824)return (v/1073741824).toFixed(2)+" GB";
    if(v>=1048576)return (v/1048576).toFixed(2)+" MB";
    if(v>=1024)return (v/1024).toFixed(1)+" KB";
    return Math.round(v)+" B";
  }

  function fmtTime(total){
    total=Math.max(0,Number(total)||0);
    var h=Math.floor(total/3600);
    var m=Math.floor((total%3600)/60);
    var s=Math.floor(total%60);
    var pad=function(n){return String(n).padStart(2,"0")};
    return h>0?h+":"+pad(m)+":"+pad(s):pad(m)+":"+pad(s);
  }

  function validToken(value){
    return typeof value==="string" &&
      /^[a-fA-F0-9]{64}$/.test(value);
  }

  function tokenFromHash(){
    var match=(location.hash||"").match(/(?:^#|&)opticore_bridge=([a-fA-F0-9]{64})(?:&|$)/);
    if(!match)return "";
    return match[1].toLowerCase();
  }

  function cleanHash(){
    if((location.hash||"").indexOf("opticore_bridge=")===-1)return;
    try{
      history.replaceState(
        null,
        document.title,
        location.pathname+location.search
      );
    }catch(e){}
  }

  function randomToken(){
    var bytes=new Uint8Array(32);

    if(window.crypto &&
       typeof window.crypto.getRandomValues==="function"){
      window.crypto.getRandomValues(bytes);
    }else{
      throw new Error("secure_random_unavailable");
    }

    return Array.prototype.map.call(
      bytes,
      function(v){
        return v.toString(16).padStart(2,"0");
      }
    ).join("");
  }

  function saveToken(value){
    if(!validToken(value))return;
    token=value.toLowerCase();
    try{
      localStorage.setItem(TOKEN_KEY,token);
    }catch(e){}
  }

  function clearToken(){
    token="";
    linked=false;
    try{
      localStorage.removeItem(TOKEN_KEY);
    }catch(e){}
    syncLaunchers();
  }

  function loadToken(){
    var fromHash=tokenFromHash();

    if(validToken(fromHash)){
      saveToken(fromHash);
      cleanHash();
      return;
    }

    try{
      var stored=localStorage.getItem(TOKEN_KEY)||"";
      if(validToken(stored)){
        token=stored.toLowerCase();
      }
    }catch(e){}
  }

  async function bootstrapToken(){
    var controller=
      typeof AbortController!=="undefined"
        ? new AbortController()
        : null;

    var timeout=setTimeout(
      function(){
        if(controller){
          try{controller.abort();}catch(e){}
        }
      },
      9000
    );

    try{
      var response=
        await fetch(
          BOOTSTRAP_API+
          "?t="+Date.now(),
          {
            method:"GET",
            mode:"cors",
            cache:"no-store",
            signal:controller
              ? controller.signal
              : undefined,
            targetAddressSpace:"loopback"
          }
        );

      if(!response.ok){
        throw new Error(
          "bootstrap_http_"+
          response.status
        );
      }

      var data=
        await response.json();

      if(!data ||
         data.ok!==true ||
         !validToken(data.token)){
        throw new Error(
          "bad_bootstrap_response"
        );
      }

      saveToken(data.token);

      return token;

    }finally{
      clearTimeout(timeout);
    }
  }

  function launchPairing(){
    try{
      var next=randomToken();
      saveToken(next);

      var url=
        "opticorevpn://control?action=pair"+
        "&token="+encodeURIComponent(next)+
        "&origin="+encodeURIComponent(location.origin);

      window.location.href=url;

    }catch(e){
      setBridgeState(
        "No se pudo iniciar el enlace",
        "error"
      );
    }
  }

  function openApp(){
    window.location.href="opticorevpn://control";
  }

  function launcherElements(){
    var out=[];
    var primary=byId("oc-open-android");

    if(primary)out.push(primary);

    Array.prototype.forEach.call(
      document.querySelectorAll(
        'a[href^="opticorevpn://control"]'
      ),
      function(el){
        if(out.indexOf(el)===-1)out.push(el);
      }
    );

    return out;
  }

  function syncLaunchers(){
    launcherElements().forEach(function(el){
      if(!el.dataset.opticoreBridgeBound){
        el.dataset.opticoreBridgeBound="1";

        el.addEventListener("click",function(event){
          event.preventDefault();
          openApp();
        });
      }

      el.setAttribute(
        "href",
        "opticorevpn://control"
      );

      el.textContent=
        "Abrir OptiCore Android";
    });
  }

  function setBridgeState(label,kind){
    var state=byId("oc-engine-state");
    if(state){
      state.textContent=label;
      state.className=
        kind==="ok"
          ? "oc-state-ok"
          : kind==="warn"
          ? "oc-state-warn"
          : "oc-state-error";
    }
  }

  function fillWidth(id,rate){
    var el=byId(id);
    if(!el)return;

    var v=Math.max(0,Number(rate)||0);
    var pct=v<=0
      ? 0
      : Math.min(
          100,
          Math.max(
            4,
            Math.log10(v+1)/6*100
          )
        );

    el.style.width=pct.toFixed(1)+"%";
  }

  function applyData(incoming){
    if(!incoming || incoming.ok!==true)return;

    failures=0;
    linked=true;

    lastData=Object.assign(
      {},
      lastData,
      incoming
    );

    window.__OPTICORE_BRIDGE_DATA__=
      lastData;

    syncLaunchers();

    setBridgeState(
      "Motor Android enlazado",
      "ok"
    );

    text(
      "oc-device-model",
      lastData.model || "Android"
    );

    text(
      "oc-last-update",
      lastData.updated_at ||
      "Enlazado"
    );

    if(typeof window.render==="function"){
      try{
        window.render(
          lastData,
          "motor Android enlazado"
        );
      }catch(e){}
    }

    var connected=
      !!lastData.vpn_connected;

    var pill=byId("ocMainPill");
    if(pill){
      pill.className=
        "oc-pill "+
        (connected?"on":"off");
    }

    text(
      "ocMainText",
      connected
        ? "VPN PROTEGIDA"
        : "VPN DESCONECTADA"
    );

    var bars=byId("ocBars");
    if(bars){
      bars.className=
        connected
          ? "oc-bars"
          : "oc-bars idle";
    }

    text(
      "ocFlowTitle",
      connected
        ? "CANAL CIFRADO ACTIVO"
        : "TÚNEL INACTIVO"
    );

    text(
      "ocFlowSub",
      connected
        ? "Estado leído desde OptiCore Android"
        : "WireGuard no está conectado"
    );

    text(
      "ocTunnelPro",
      connected
        ? "WireGuard conectado"
        : "Desconectado"
    );

    text(
      "ocSessionPro",
      connected
        ? fmtTime(
            lastData
              .vpn_connected_seconds
          )
        : "00:00"
    );

    var live=
      document.querySelector(
        "#ocSecurityMonitor .oc-live"
      );

    if(live){
      live.textContent="ENLAZADO";
    }

    var qfReal=
      document.querySelector(
        "#qflowLiveV2 .qf-real"
      );

    if(qfReal){
      qfReal.textContent=
        "WIREGUARD ENLAZADO";
    }

    text(
      "qfRxRate",
      fmtRate(
        lastData.vpn_rx_bps
      )
    );

    text(
      "qfTxRate",
      fmtRate(
        lastData.vpn_tx_bps
      )
    );

    text(
      "qfRxTotal",
      fmtBytes(
        lastData.vpn_rx_bytes
      )
    );

    text(
      "qfTxTotal",
      fmtBytes(
        lastData.vpn_tx_bytes
      )
    );

    text(
      "qfRxSmooth",
      fmtRate(
        lastData
          .vpn_rx_ewma_bps
      )
    );

    text(
      "qfTxSmooth",
      fmtRate(
        lastData
          .vpn_tx_ewma_bps
      )
    );

    fillWidth(
      "qfRxFill",
      lastData
        .vpn_rx_ewma_bps
    );

    fillWidth(
      "qfTxFill",
      lastData
        .vpn_tx_ewma_bps
    );

    text(
      "qfSamples",
      "Motor Android · OptiCore "+
      (lastData.version||"")
    );

    text(
      "qfActivity",
      connected
        ? "VPN activa"
        : "VPN inactiva"
    );

    var burst=byId("qfBurst");
    if(burst){
      burst.className=
        "qf-burst"+
        (lastData.vpn_burst
          ? " hot"
          : "");

      burst.textContent=
        lastData.vpn_burst
          ? "RÁFAGA"
          : "FLUJO ESTABLE";
    }

    var network=byId(
      "opticoreNetwork"
    );

    if(network &&
       navigator.onLine){
      network.textContent=
        "INTERNET + MOTOR ANDROID ENLAZADOS";
    }

    try{
      window.dispatchEvent(
        new CustomEvent(
          "opticore-bridge-status",
          {detail:lastData}
        )
      );
    }catch(e){}
  }

  function markStale(){
    if(failures<3)return;

    setBridgeState(
      "Motor Android sin respuesta",
      "warn"
    );

    var updated=
      byId("oc-last-update");

    if(updated &&
       lastData.updated_at){
      updated.textContent=
        "Último dato: "+
        lastData.updated_at;
    }

    var live=
      document.querySelector(
        "#ocSecurityMonitor .oc-live"
      );

    if(live){
      live.textContent="ENLACE EN PAUSA";
    }
  }

  async function requestBridge(detail){
    if(!validToken(token)){
      await bootstrapToken();
    }

    var controller=
      typeof AbortController!=="undefined"
        ? new AbortController()
        : null;

    var timeout=setTimeout(
      function(){
        if(controller){
          try{
            controller.abort();
          }catch(e){}
        }
      },
      detail?30000:9000
    );

    var options={
      method:"GET",
      mode:"cors",
      cache:"no-store",
      headers:{
        "X-OptiCore-Bridge":token
      },
      signal:controller
        ? controller.signal
        : undefined,
      targetAddressSpace:"loopback"
    };

    try{
      var response=
        await fetch(
          LOCAL_API+
          "?detail="+
          (detail?"1":"0")+
          "&t="+Date.now(),
          options
        );

      if(response.status===403){
        clearToken();
        throw new Error(
          "bridge_not_paired"
        );
      }

      if(!response.ok){
        throw new Error(
          "HTTP "+
          response.status
        );
      }

      var data=
        await response.json();

      if(!data ||
         data.ok!==true){
        throw new Error(
          "bad_bridge_response"
        );
      }

      return data;

    }finally{
      clearTimeout(timeout);
    }
  }

  async function requestReadOnlyEndpoint(path,timeoutMs){
    if(!validToken(token)){
      await bootstrapToken();
    }

    var controller=
      typeof AbortController!=="undefined"
        ? new AbortController()
        : null;

    var timeout=setTimeout(
      function(){
        if(controller){
          try{ controller.abort(); }catch(e){}
        }
      },
      timeoutMs||30000
    );

    try{
      var response=
        await fetch(
          "http://127.0.0.1:8766"+path+
          (path.indexOf("?")>=0?"&":"?")+
          "t="+Date.now(),
          {
            method:"GET",
            mode:"cors",
            cache:"no-store",
            headers:{
              "X-OptiCore-Bridge":token
            },
            signal:controller
              ? controller.signal
              : undefined,
            targetAddressSpace:"loopback"
          }
        );

      if(response.status===403){
        clearToken();
        throw new Error("bridge_not_paired");
      }

      if(!response.ok){
        throw new Error("HTTP "+response.status);
      }

      var data=await response.json();

      if(!data || data.ok===false){
        throw new Error(
          data&&data.error
            ? data.error
            : "bad_bridge_response"
        );
      }

      return data;

    }finally{
      clearTimeout(timeout);
    }
  }

  window.OptiCoreBridge={
    fetchDns:function(){
      return requestReadOnlyEndpoint(
        "/api/bridge-dns",
        35000
      );
    },
    isLinked:function(){
      return linked &&
        validToken(token);
    }
  };

  function schedule(){
    clearTimeout(timer);

    var delay;

    if(document.hidden){
      delay=30000;
    }else if(failures===0){
      delay=5000;
    }else{
      delay=Math.min(
        30000,
        3000*Math.pow(
          2,
          Math.min(
            failures-1,
            3
          )
        )
      );
    }

    timer=setTimeout(
      function(){
        refresh(false);
      },
      delay
    );
  }

  async function refresh(detail){
    if(busy){
      schedule();
      return;
    }

    busy=true;

    try{
      var data=
        await requestBridge(
          !!detail
        );

      applyData(data);

    }catch(e){
      if(e &&
         e.message==="bridge_not_paired"){
        try{
          var recovered=
            await requestBridge(
              !!detail
            );

          applyData(recovered);
          return;

        }catch(recoveryError){}
      }

      failures++;
      markStale();

    }finally{
      busy=false;
      schedule();
    }
  }

  function bindRefreshButton(){
    document.addEventListener(
      "click",
      function(event){
        var target=event.target;

        if(!target ||
           target.id!=="reload" ||
           !linked){
          return;
        }

        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation();

        refresh(true);
      },
      true
    );
  }

  function boot(){
    loadToken();
    syncLaunchers();
    bindRefreshButton();

    refresh(true);

    window.addEventListener(
      "online",
      function(){
        failures=0;
        refresh(false);
      }
    );

    document.addEventListener(
      "visibilitychange",
      function(){
        if(!document.hidden){
          refresh(false);
        }
      }
    );

    window.addEventListener(
      "focus",
      function(){
        refresh(false);
      }
    );
  }

  if(document.readyState==="loading"){
    document.addEventListener(
      "DOMContentLoaded",
      boot
    );
  }else{
    boot();
  }

})();
