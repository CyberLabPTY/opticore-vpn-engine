(function(){
  const reload=document.getElementById("reload");
  const dns=document.getElementById("dbtn");
  const buttons=[].slice.call(document.querySelectorAll("button"));

  const cacheBtn=buttons.find(function(b){
    return b.textContent.replace(/\s+/g," ").trim().indexOf("Preparar limpieza")!==-1;
  });

  const appNames={
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

  function appName(pkg){
    return appNames[pkg] || pkg;
  }

  function toastSafe(msg){
    if(typeof toast==="function")toast(msg);
  }

  function ensureCachePanel(){
    let p=document.getElementById("cacheInventoryPanel");
    if(p)return p;

    p=document.createElement("div");
    p.id="cacheInventoryPanel";
    p.style.marginTop="14px";
    p.style.padding="16px";
    p.style.border="1px solid rgba(87,190,210,.25)";
    p.style.borderRadius="18px";
    p.style.background="rgba(7,27,41,.76)";
    p.style.lineHeight="1.5";

    const row=cacheBtn ? cacheBtn.closest(".row") : null;
    if(row && row.parentNode){
      row.parentNode.insertBefore(p,row.nextSibling);
    }else if(cacheBtn && cacheBtn.parentNode){
      cacheBtn.parentNode.appendChild(p);
    }

    return p;
  }

  function recalcSelection(){
    const picks=[].slice.call(document.querySelectorAll(".cachePick:checked"));
    let total=0;

    picks.forEach(function(x){
      total+=Number(x.getAttribute("data-size"))||0;
    });

    const label=document.getElementById("cacheSelectedTotal");
    const review=document.getElementById("cacheReviewBtn");

    if(label){
      label.textContent=picks.length+" "+(picks.length===1?"seleccionada":"seleccionadas")+" · "+total.toFixed(1)+" MB";
    }

    if(review){
      review.disabled=picks.length===0;
      review.style.opacity=picks.length===0 ? ".55" : "1";
    }
  }

  function showReview(){
    const picks=[].slice.call(document.querySelectorAll(".cachePick:checked"));
    const panel=document.getElementById("cacheReviewArea");
    if(!panel)return;

    if(!picks.length){
      panel.innerHTML="";
      return;
    }

    let total=0;
    let html="";

    html+="<div style=\"font-size:19px;font-weight:800;margin-bottom:8px\">Revisión de limpieza</div>";
    html+="<div style=\"opacity:.8;margin-bottom:12px\">Esto es solamente una simulación. Todavía no se borrará nada.</div>";

    picks.forEach(function(x){
      const size=Number(x.getAttribute("data-size"))||0;
      const pkg=x.getAttribute("data-package")||"";
      total+=size;

      html+="<div style=\"display:flex;justify-content:space-between;gap:12px;padding:8px 0;border-top:1px solid rgba(120,180,200,.15)\">";
      html+="<span>"+appName(pkg)+"</span>";
      html+="<strong style=\"white-space:nowrap\">"+size.toFixed(1)+" MB</strong>";
      html+="</div>";
    });

    html+="<div style=\"margin-top:14px;font-size:18px\"><strong>Total seleccionado: "+total.toFixed(1)+" MB</strong></div>";
    html+="<div style=\"margin-top:10px;color:#7fe3c5;font-weight:800\">NO SE EJECUTÓ NINGÚN BORRADO</div>";

    panel.innerHTML=html;
    panel.scrollIntoView({behavior:"smooth",block:"nearest"});
    toastSafe("Revisión preparada: "+total.toFixed(1)+" MB");
  }

  function renderCacheInventory(d){
    const panel=ensureCachePanel();
    panel.innerHTML="";

    const title=document.createElement("div");
    title.textContent="Inventario de caché";
    title.style.fontSize="22px";
    title.style.fontWeight="800";
    title.style.marginBottom="5px";
    panel.appendChild(title);

    const summary=document.createElement("div");
    summary.innerHTML="Total accesible: <strong>"+d.total_visible_mb+" MB</strong> en "+d.accessible_cache_count+" aplicaciones.";
    summary.style.opacity=".86";
    summary.style.marginBottom="15px";
    panel.appendChild(summary);

    const hint=document.createElement("div");
    hint.textContent="Selecciona únicamente las aplicaciones cuya caché quieras revisar.";
    hint.style.marginBottom="13px";
    hint.style.fontSize="14px";
    hint.style.opacity=".72";
    panel.appendChild(hint);

    const entries=(d.entries||[]).filter(function(x){
      return Number(x.size_mb)>0;
    });

    entries.forEach(function(x){
      const row=document.createElement("label");
      row.style.display="grid";
      row.style.gridTemplateColumns="32px 1fr auto";
      row.style.alignItems="center";
      row.style.gap="10px";
      row.style.padding="12px 0";
      row.style.borderTop="1px solid rgba(120,180,200,.15)";

      const box=document.createElement("input");
      box.type="checkbox";
      box.className="cachePick";
      box.setAttribute("data-package",x.package);
      box.setAttribute("data-size",x.size_mb);
      box.style.width="20px";
      box.style.height="20px";
      box.addEventListener("change",recalcSelection);

      const info=document.createElement("div");

      const friendly=document.createElement("div");
      friendly.textContent=appName(x.package);
      friendly.style.fontWeight="700";

      const technical=document.createElement("div");
      technical.textContent=x.package;
      technical.style.fontSize="12px";
      technical.style.opacity=".55";
      technical.style.overflowWrap="anywhere";

      info.appendChild(friendly);
      info.appendChild(technical);

      const size=document.createElement("strong");
      size.textContent=Number(x.size_mb).toFixed(1)+" MB";
      size.style.whiteSpace="nowrap";

      row.appendChild(box);
      row.appendChild(info);
      row.appendChild(size);
      panel.appendChild(row);
    });

    const selected=document.createElement("div");
    selected.id="cacheSelectedTotal";
    selected.textContent="0 seleccionadas · 0.0 MB";
    selected.style.marginTop="15px";
    selected.style.fontWeight="800";
    panel.appendChild(selected);

    const review=document.createElement("button");
    review.id="cacheReviewBtn";
    review.textContent="Revisar limpieza";
    review.disabled=true;
    review.style.marginTop="12px";
    review.style.width="100%";
    review.style.padding="13px";
    review.style.borderRadius="14px";
    review.style.opacity=".55";
    review.addEventListener("click",showReview);
    panel.appendChild(review);

    const area=document.createElement("div");
    area.id="cacheReviewArea";
    area.style.marginTop="16px";
    area.style.paddingTop="4px";
    panel.appendChild(area);

    const safe=document.createElement("div");
    safe.textContent="Modo revisión · No existe ninguna orden de borrado en esta fase";
    safe.style.marginTop="15px";
    safe.style.paddingTop="12px";
    safe.style.borderTop="1px solid rgba(120,180,200,.18)";
    safe.style.color="#7fe3c5";
    safe.style.fontWeight="800";
    panel.appendChild(safe);
  }

  if(reload){
    reload.onclick=async function(){
      const old=reload.textContent;
      reload.disabled=true;
      reload.textContent="Analizando...";

      try{
        const r=await fetch("/api/refresh?t="+Date.now(),{cache:"no-store"});
        if(!r.ok)throw new Error("HTTP "+r.status);
        const d=await r.json();
        render(d,"diagnóstico en vivo");
        toastSafe("Diagnóstico actualizado");
      }catch(e){
        toastSafe("No se pudo actualizar");
      }finally{
        reload.disabled=false;
        reload.textContent=old;
      }
    };
  }

  if(dns){
    dns.onclick=async function(){
      const old=dns.textContent;
      dns.disabled=true;
      dns.textContent="Midiendo...";

      try{
        const r=await fetch("/api/dns?t="+Date.now(),{cache:"no-store"});
        if(!r.ok)throw new Error("HTTP "+r.status);
        const d=await r.json();

        document.getElementById("cfp").textContent=d.cloudflare.avg_ms;
        document.getElementById("q9p").textContent=d.quad9.avg_ms;
        document.getElementById("gp").textContent=d.google.avg_ms;

        document.getElementById("netAdvice").textContent=
          "Candidato actual: "+d.latency_stability_candidate+". "+
          "Cloudflare "+d.cloudflare.avg_ms+" ms / pérdida "+d.cloudflare.loss_percent+"%. "+
          "Quad9 "+d.quad9.avg_ms+" ms / pérdida "+d.quad9.loss_percent+"%. "+
          "Google "+d.google.avg_ms+" ms / pérdida "+d.google.loss_percent+"%.";

        toastSafe("Análisis DNS terminado: "+d.latency_stability_candidate);
      }catch(e){
        toastSafe("No se pudo completar el análisis DNS");
      }finally{
        dns.disabled=false;
        dns.textContent=old;
      }
    };
  }

  if(cacheBtn){
    cacheBtn.onclick=async function(){
      const old=cacheBtn.textContent;
      cacheBtn.disabled=true;
      cacheBtn.textContent="Escaneando...";

      const panel=ensureCachePanel();
      panel.innerHTML="<strong>Analizando caché externa accesible...</strong><br><span style=\"opacity:.72\">No se está borrando nada.</span>";

      try{
        const r=await fetch("/api/cache-scan?t="+Date.now(),{cache:"no-store"});
        if(!r.ok)throw new Error("HTTP "+r.status);
        const d=await r.json();
        renderCacheInventory(d);
        toastSafe("Inventario listo para selección");
      }catch(e){
        panel.innerHTML="<strong>No se pudo completar el inventario.</strong>";
        toastSafe("Error al analizar caché");
      }finally{
        cacheBtn.disabled=false;
        cacheBtn.textContent=old;
      }
    };
  }
})();
