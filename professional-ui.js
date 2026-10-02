(function(){
  "use strict";

  window.__OPTICORE_UI_VERSION__="1.0";

  var APP_NAMES={
    "org.telegram.messenger":"Telegram",
    "com.zhiliaoapp.musically":"TikTok",
    "com.mixplorer.silver":"MiXplorer",
    "com.whatsapp":"WhatsApp",
    "com.pixonic.wwr":"War Robots",
    "com.google.android.apps.maps":"Google Maps",
    "com.linkedin.android":"LinkedIn",
    "com.qrcode.barcode.scanner.reader.generator.pro":"QR Scanner",
    "com.sec.android.app.launcher":"Samsung Launcher",
    "com.samsung.android.bixby.agent":"Bixby",
    "com.aimp.player":"AIMP",
    "com.android.youtube.music.premium":"YouTube Music",
    "com.waze":"Waze",
    "com.instagram.android":"Instagram",
    "com.facebook.katana":"Facebook",
    "com.samsung.android.messaging":"Mensajes Samsung"
  };

  var protectedAuto=[
    "org.telegram.messenger",
    "com.whatsapp",
    "com.pixonic.wwr"
  ];

  var currentMode="intelligent";
  var modalTarget=null;
  var observerBusy=false;

  function qs(s){
    return document.querySelector(s);
  }

  function qsa(s){
    return [].slice.call(document.querySelectorAll(s));
  }

  function appName(pkg){
    return APP_NAMES[pkg] || pkg;
  }

  function isSystem(pkg){
    return pkg.indexOf("com.sec.")===0 ||
           pkg.indexOf("com.samsung.")===0 ||
           pkg==="com.android.systemui" ||
           pkg==="com.google.android.gms" ||
           pkg==="com.google.android.gsf";
  }

  function isProtectedAuto(pkg){
    return protectedAuto.indexOf(pkg)!==-1;
  }

  function safeToast(msg){
    if(typeof window.toast==="function"){
      window.toast(msg);
    }
  }

  function createHeader(){
    if(qs("#oc-professional-header")) return;

    var host=qs("main") || qs(".container") || qs(".wrap") || document.body;
    var card=document.createElement("section");

    card.id="oc-professional-header";
    card.setAttribute("aria-label","Estado de OptiCore Lab");

    card.innerHTML=
      "<div class=\"oc-brand-row\">"+
        "<div class=\"oc-logo\" aria-hidden=\"true\">"+
          "<svg viewBox=\"0 0 64 64\" width=\"42\" height=\"42\">"+
            "<path d=\"M32 6 51 14v15c0 13-8 23-19 29-11-6-19-16-19-29V14z\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"4\"/>"+
            "<path d=\"M20 32h9l4-11 5 22 4-11h7\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"4\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"+
          "</svg>"+
        "</div>"+
        "<div class=\"oc-brand-text\">"+
          "<strong>OptiCore Lab</strong>"+
          "<span>Android Diagnostics & Intelligent Optimization</span>"+
        "</div>"+
      "</div>"+
      "<div class=\"oc-engine-grid\">"+
        "<div><span>Motor</span><strong id=\"oc-engine-state\">Verificando...</strong></div>"+
        "<div><span>Dispositivo</span><strong id=\"oc-device-model\">Detectando...</strong></div>"+
        "<div><span>Última medición</span><strong id=\"oc-last-update\">--</strong></div>"+
      "</div>";

    if(host.firstChild){
      host.insertBefore(card,host.firstChild);
    }else{
      host.appendChild(card);
    }
  }

  function updateHeader(){
    var state=qs("#oc-engine-state");
    var model=qs("#oc-device-model");
    var updated=qs("#oc-last-update");
    var localPanel=
      location.hostname==="127.0.0.1" ||
      location.hostname==="localhost";

    if(!localPanel){
      if(state){
        state.textContent="Modo web público";
        state.className="oc-state-warn";
      }
      if(model){
        model.textContent="Navegador";
      }
      if(updated){
        updated.textContent="Sin datos Android locales";
      }
      return;
    }

    fetch("./data/device-status.json?t="+Date.now(),{
      cache:"no-store"
    })
    .then(function(r){
      if(!r.ok) throw new Error("HTTP "+r.status);
      return r.json();
    })
    .then(function(d){
      if(state){
        state.textContent="Motor local activo";
        state.className="oc-state-ok";
      }

      if(model){
        model.textContent=d.model || "Android";
      }

      if(updated){
        updated.textContent=d.updated_at || "Disponible";
      }
    })
    .catch(function(){
      if(state){
        state.textContent="Motor no disponible";
        state.className="oc-state-error";
      }

      if(model){
        model.textContent="Solo interfaz web";
      }

      if(updated){
        updated.textContent="Sin datos locales";
      }
    });
  }

  function recalcSelection(){
    var boxes=qsa(".cachePick:checked").filter(function(x){
      return !x.disabled;
    });

    var total=0;

    boxes.forEach(function(x){
      total+=Number(x.dataset.size)||0;
    });

    var label=qs("#cacheSelectedTotal");
    var review=qs("#cacheReviewBtn");

    if(label){
      label.textContent=boxes.length+
        " "+(boxes.length===1?"seleccionada":"seleccionadas")+" · "+total.toFixed(1)+" MB";
    }

    if(review){
      review.disabled=boxes.length===0;
      review.style.opacity=boxes.length?".98":".48";
    }
  }

  function policyText(pkg){
    if(isSystem(pkg)){
      return "Bloqueado por seguridad";
    }

    if(isProtectedAuto(pkg)){
      return currentMode==="intelligent"
        ? "Protegido automáticamente"
        : "Disponible en modo manual";
    }

    return currentMode==="intelligent"
      ? "Apto para optimización conservadora"
      : "Selección manual";
  }

  function decorateRows(){
    qsa(".cachePick").forEach(function(box){
      var pkg=box.dataset.package || "";
      var row=box.closest("label");

      if(!row) return;

      if(isSystem(pkg)){
        box.checked=false;
        box.disabled=true;
        row.classList.add("oc-row-protected");
      }else{
        row.classList.remove("oc-row-protected");
      }

      if(currentMode==="intelligent" && isProtectedAuto(pkg)){
        box.checked=false;
      }

      var badge=row.querySelector(".oc-policy-badge");

      if(!badge){
        badge=document.createElement("div");
        badge.className="oc-policy-badge";

        var info=row.children[1];

        if(info){
          info.appendChild(badge);
        }
      }

      if(badge){
        badge.textContent=policyText(pkg);

        if(isSystem(pkg) ||
           (currentMode==="intelligent" && isProtectedAuto(pkg))){
          badge.classList.add("oc-policy-protected");
        }else{
          badge.classList.remove("oc-policy-protected");
        }
      }
    });

    recalcSelection();
  }

  function setMode(mode){
    currentMode=mode;

    var ai=qs("#smartAi");
    var manual=qs("#smartManual");

    if(ai){
      ai.classList.toggle("oc-mode-active",mode==="intelligent");
    }

    if(manual){
      manual.classList.toggle("oc-mode-active",mode==="manual");
    }

    if(mode==="intelligent"){
      qsa(".cachePick").forEach(function(box){
        var pkg=box.dataset.package || "";

        if(isSystem(pkg) || isProtectedAuto(pkg)){
          box.checked=false;
        }
      });
    }

    decorateRows();
  }

  function createModal(){
    if(qs("#oc-modal-backdrop")) return;

    var back=document.createElement("div");
    back.id="oc-modal-backdrop";
    back.className="oc-modal-backdrop";
    back.setAttribute("aria-hidden","true");

    back.innerHTML=
      "<div class=\"oc-modal\" role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"oc-modal-title\">"+
        "<div class=\"oc-modal-icon\">✓</div>"+
        "<h2 id=\"oc-modal-title\">Confirmar optimización</h2>"+
        "<p class=\"oc-modal-subtitle\">Se eliminará solamente caché temporal autorizada.</p>"+
        "<div id=\"oc-modal-list\" class=\"oc-modal-list\"></div>"+
        "<div class=\"oc-safe-box\">"+
          "<strong>No se modificarán</strong>"+
          "<span>Chats · Fotos · Documentos · Cuentas · Configuración · Sistema</span>"+
        "</div>"+
        "<div class=\"oc-modal-actions\">"+
          "<button id=\"oc-modal-cancel\" type=\"button\">Cancelar</button>"+
          "<button id=\"oc-modal-confirm\" type=\"button\">Optimizar ahora</button>"+
        "</div>"+
      "</div>";

    document.body.appendChild(back);

    qs("#oc-modal-cancel").onclick=closeModal;
    qs("#oc-modal-confirm").onclick=confirmModal;

    back.addEventListener("click",function(e){
      if(e.target===back){
        closeModal();
      }
    });
  }

  function selectedCache(){
    return qsa(".cachePick:checked").filter(function(x){
      return !x.disabled;
    });
  }

  function openModal(target){
    createModal();

    var list=selectedCache();

    if(!list.length){
      safeToast("No hay cachés autorizadas seleccionadas");
      return;
    }

    modalTarget=target;

    var total=0;
    var html="";

    list.forEach(function(x){
      var size=Number(x.dataset.size)||0;
      var pkg=x.dataset.package || "";

      total+=size;

      html+=
        "<div class=\"oc-modal-item\">"+
          "<span>"+appName(pkg)+"</span>"+
          "<strong>"+size.toFixed(1)+" MB</strong>"+
        "</div>";
    });

    html+=
      "<div class=\"oc-modal-total\">"+
        "<span>Total estimado</span>"+
        "<strong>"+total.toFixed(1)+" MB</strong>"+
      "</div>";

    qs("#oc-modal-list").innerHTML=html;

    var back=qs("#oc-modal-backdrop");
    back.classList.add("oc-modal-visible");
    back.setAttribute("aria-hidden","false");
    document.body.classList.add("oc-modal-open");
  }

  function closeModal(){
    var back=qs("#oc-modal-backdrop");

    if(back){
      back.classList.remove("oc-modal-visible");
      back.setAttribute("aria-hidden","true");
    }

    document.body.classList.remove("oc-modal-open");
    modalTarget=null;
  }

  function confirmModal(){
    if(!modalTarget) return;

    var target=modalTarget;

    closeModal();

    window.__OPTICORE_ALLOW_NATIVE_CONFIRM__=true;

    var originalConfirm=window.confirm;

    window.confirm=function(){
      return true;
    };

    try{
      if(typeof target.onclick==="function"){
        target.onclick();
      }
    }finally{
      window.confirm=originalConfirm;
      window.__OPTICORE_ALLOW_NATIVE_CONFIRM__=false;
    }
  }

  function translateTechnicalStates(){
    var area=qs("#cacheReviewArea");

    if(area){
      var walker=document.createTreeWalker(
        area,
        NodeFilter.SHOW_TEXT,
        null,
        false
      );

      var nodes=[];
      var n;

      while((n=walker.nextNode())){
        nodes.push(n);
      }

      nodes.forEach(function(node){
        var t=node.nodeValue;

        t=t.replace(/protected_auto/g,"🔒 Protegido automáticamente");
        t=t.replace(/blocked_system/g,"🔒 Bloqueado por seguridad");
        t=t.replace(/cleaned/g,"✓ Limpiado");
        t=t.replace(/not_in_inventory/g,"No autorizado");
        t=t.replace(/invalid_path/g,"Ruta rechazada");
        t=t.replace(/missing/g,"Sin caché accesible");
        t=t.replace(/empty/g,"Sin contenido temporal");

        node.nodeValue=t;
      });
    }

    qsa("div").forEach(function(el){
      var t=(el.textContent || "").trim();

      if(t==="Modo revisión · No existe ninguna orden de borrado en esta fase"){
        el.textContent=
          "Protección activa · solo caché temporal autorizada";
        el.classList.add("oc-protection-note");
      }
    });
  }

  function polishButtons(){
    qsa("button").forEach(function(b){
      if(!b.classList.contains("oc-polished")){
        b.classList.add("oc-polished");
      }
    });

    var prepare=qsa("button").find(function(b){
      return (b.textContent || "").indexOf("Preparar limpieza")!==-1;
    });

    if(prepare){
      prepare.setAttribute("aria-label","Analizar caché disponible");
    }
  }

  function professionalPass(){
    decorateRows();
    translateTechnicalStates();
    polishButtons();
  }

  function schedulePass(){
    if(observerBusy) return;

    observerBusy=true;

    requestAnimationFrame(function(){
      observerBusy=false;
      professionalPass();
    });
  }

  function bindEvents(){
    document.addEventListener("click",function(e){
      var target=e.target;

      if(!target) return;

      if(target.id==="smartAi"){
        currentMode="intelligent";
        setTimeout(function(){
          setMode("intelligent");
        },0);
        return;
      }

      if(target.id==="smartManual"){
        currentMode="manual";
        setTimeout(function(){
          setMode("manual");
        },0);
        return;
      }

      if(target.id==="reload"){
        setTimeout(updateHeader,900);
      }
    },false);

    document.addEventListener("change",function(e){
      var box=e.target;

      if(!box || !box.classList ||
         !box.classList.contains("cachePick")) return;

      var pkg=box.dataset.package || "";

      if(isSystem(pkg)){
        box.checked=false;
        box.disabled=true;
        safeToast("Componente protegido por seguridad");
      }

      if(currentMode==="intelligent" && isProtectedAuto(pkg)){
        box.checked=false;
        safeToast(appName(pkg)+" está protegido en modo Inteligente");
      }

      recalcSelection();
    },true);

    document.addEventListener("click",function(e){
      var t=e.target;

      if(!t || t.id!=="smartRun") return;

      if(window.__OPTICORE_ALLOW_NATIVE_CONFIRM__) return;

      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();

      openModal(t);
    },true);
  }

  function startObserver(){
    var observer=new MutationObserver(function(){
      schedulePass();
    });

    observer.observe(document.body,{
      childList:true,
      subtree:true
    });
  }

  function normalizeBrand(){
    document.title=
      "OptiCore Lab | Android Diagnostics & Optimization";

    qsa("h1").forEach(function(h){
      if((h.textContent || "").indexOf("Device Optimizer Lab")!==-1){
        h.textContent="OptiCore Lab";
      }
    });
  }

  function boot(){
    normalizeBrand();
    createHeader();
    createModal();
    bindEvents();
    professionalPass();
    updateHeader();
    startObserver();

    document.documentElement.classList.add("oc-ui-ready");
    window.__OPTICORE_UI_READY__=true;
  }

  if(document.readyState==="loading"){
    document.addEventListener("DOMContentLoaded",boot);
  }else{
    boot();
  }

})();
