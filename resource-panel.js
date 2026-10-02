(function(){
  "use strict";

  var busy=false;

  function qsa(s){
    return [].slice.call(document.querySelectorAll(s));
  }

  function byId(id){
    return document.getElementById(id);
  }

  function esc(v){
    return String(v==null?"":v)
      .replace(/&/g,"&amp;")
      .replace(/</g,"&lt;")
      .replace(/>/g,"&gt;")
      .replace(/"/g,"&quot;");
  }

  function num(v,d){
    var n=Number(v);
    if(!isFinite(n))return null;
    return n.toFixed(d==null?1:d);
  }

  function word(v){
    var x=String(v||"").toLowerCase();
    if(x==="normal")return "Normal";
    if(x==="moderada")return "Moderada";
    if(x==="elevada")return "Elevada";
    if(x==="alta")return "Alta";
    if(x==="estable")return "Estable";
    if(x==="vigilar")return "Vigilar";
    if(x==="atencion")return "Atención";
    if(x==="unavailable")return "No disponible";
    return x || "No disponible";
  }

  function badge(text,type){
    return "<span class=\"oc-r-badge oc-r-"+type+"\">"+esc(text)+"</span>";
  }

  function card(title,value,badgeHtml,detail,note){
    return "<article class=\"oc-r-card\">"+
      "<div class=\"oc-r-card-head\"><span>"+esc(title)+"</span>"+badgeHtml+"</div>"+
      "<strong class=\"oc-r-value\">"+value+"</strong>"+
      "<div class=\"oc-r-detail\">"+detail+"</div>"+
      (note?"<div class=\"oc-r-note\">"+note+"</div>":"")+
    "</article>";
  }

  function findButton(){
    return byId("rbtn") || qsa("button").find(function(b){
      var t=(b.textContent||"").toLowerCase();
      return t.indexOf("recomend")!==-1 || t.indexOf("diagn")!==-1;
    }) || null;
  }

  function ensurePanel(btn){
    var p=byId("oc-resource-panel");
    if(p)return p;

    p=document.createElement("section");
    p.id="oc-resource-panel";
    p.className="oc-resource-panel";
    p.setAttribute("aria-live","polite");

    var host=btn ? btn.closest(".card,.panel,.hero") : null;
    if(!host)host=btn ? btn.parentNode : document.body;

    if(host && host.parentNode){
      host.parentNode.insertBefore(p,host.nextSibling);
    }else{
      document.body.appendChild(p);
    }

    return p;
  }

  function loading(panel){
    panel.classList.add("oc-r-open");
    panel.innerHTML=
      "<div class=\"oc-r-top\">"+
        "<div><span class=\"oc-r-kicker\">DIAGNÓSTICO EN TIEMPO REAL</span><h2>Estado de recursos</h2></div>"+
        "<span class=\"oc-r-badge oc-r-measured\">MIDIENDO</span>"+
      "</div>"+
      "<div class=\"oc-r-loading\"><span class=\"oc-r-spinner\"></span><div><strong>Analizando recursos...</strong><span>RAM, ZRAM, frecuencias, GPU y temperatura.</span></div></div>";
  }

  function errorPanel(panel,msg){
    panel.classList.add("oc-r-open");
    panel.innerHTML=
      "<div class=\"oc-r-top\"><div><span class=\"oc-r-kicker\">OPTICORE</span><h2>Estado de recursos</h2></div>"+badge("SIN CONEXIÓN","restricted")+"</div>"+
      "<div class=\"oc-r-error\"><strong>No se pudo completar la medición.</strong><span>"+esc(msg)+"</span></div>"+
      "<button id=\"oc-resource-retry\" class=\"oc-r-action\" type=\"button\">Intentar nuevamente</button>";

    var r=byId("oc-resource-retry");
    if(r)r.onclick=load;
  }

  function render(panel,d){
    var ram=num(d.ram_available_percent,1);
    var ramMb=num(d.ram_available_mb,1);
    var swap=num(d.swap_used_percent,1);
    var cpuAvg=num(d.cpu_avg_mhz,0);
    var cpuMax=num(d.cpu_max_avg_mhz,0);
    var gpuCur=num(d.gpu_current_mhz,0);
    var gpuMax=num(d.gpu_max_mhz,0);
    var gpuBusy=d.gpu_busy_supported ? num(d.gpu_busy_sample_percent,1) : null;
    var temp=d.battery_temp_supported ? num(d.battery_temp_c,1) : null;

    var html="";

    html+="<div class=\"oc-r-top\">";
    html+="<div><span class=\"oc-r-kicker\">DIAGNÓSTICO EN TIEMPO REAL</span><h2>Estado de recursos</h2><p>"+esc(d.updated_at||"")+"</p></div>";
    html+=badge(word(d.overall_state),d.overall_state==="atencion"?"warning":d.overall_state==="vigilar"?"estimate":"measured");
    html+="</div>";

    html+="<div class=\"oc-r-grid\">";

    html+=card(
      "RAM",
      (ram!==null?ram+" %":"—"),
      badge("MEDIDO","measured"),
      (ramMb!==null?ramMb+" MB disponibles":"Disponibilidad no accesible"),
      "Presión estimada: <strong>"+esc(word(d.memory_pressure))+"</strong> · basada en MemAvailable."
    );

    html+=card(
      "ZRAM / Swap",
      (swap!==null?swap+" %":"—"),
      badge("MEDIDO","measured"),
      "Ocupación: <strong>"+esc(word(d.swap_occupancy_state))+"</strong>",
      "Una ocupación alta no equivale por sí sola a falta de RAM."
    );

    html+=card(
      "CPU",
      esc(String(d.cpu_active_cores||0))+" núcleos",
      badge("MEDIDO","measured"),
      (cpuAvg!==null?cpuAvg+" MHz promedio":"Frecuencia no disponible")+
        (cpuMax!==null?" · máx. promedio "+cpuMax+" MHz":""),
      "Carga total: <strong>restringida por Android</strong>. La frecuencia no representa utilización."
    );

    html+=card(
      "GPU",
      (gpuCur!==null&&gpuMax!==null?gpuCur+" / "+gpuMax+" MHz":"—"),
      badge("MEDIDO","measured"),
      gpuBusy!==null?
        "Actividad de la muestra: <strong>"+gpuBusy+" %</strong>":
        "Actividad: no disponible",
      "La relación de frecuencia no se utiliza como porcentaje de uso."
    );

    html+=card(
      "Batería",
      (temp!==null?temp+" °C":"—"),
      d.battery_temp_supported?badge("MEDIDO","measured"):badge("NO DISPONIBLE","restricted"),
      "Estado térmico: <strong>"+esc(word(d.thermal_state))+"</strong>",
      "La temperatura corresponde al sensor de batería accesible por Android."
    );

    html+=card(
      "Carga CPU",
      d.cpu_load_supported&&d.cpu_load_percent!=null?esc(num(d.cpu_load_percent,1))+" %":"No disponible",
      d.cpu_load_supported?badge("MEDIDO","measured"):badge("RESTRINGIDO","restricted"),
      d.cpu_load_supported?"Lectura disponible":"Android bloquea la fuente necesaria en este entorno.",
      "OptiCore no inventa un valor cuando la medición no está permitida."
    );

    html+="</div>";

    html+="<div class=\"oc-r-rec\">"+
      "<div class=\"oc-r-rec-head\"><span>Recomendación de OptiCore</span>"+badge("BASADA EN MEDICIÓN","estimate")+"</div>"+
      "<p>"+esc(d.recommendation||"Sin recomendación disponible.")+"</p>"+
    "</div>";

    html+="<div class=\"oc-r-truth\">"+
      "<div><span class=\"oc-r-dot oc-r-dot-measured\"></span><strong>MEDIDO</strong><small>Dato leído del dispositivo.</small></div>"+
      "<div><span class=\"oc-r-dot oc-r-dot-estimate\"></span><strong>ESTIMADO</strong><small>Interpretación conservadora de una medición.</small></div>"+
      "<div><span class=\"oc-r-dot oc-r-dot-restricted\"></span><strong>RESTRINGIDO</strong><small>Android no permite esa lectura o acción.</small></div>"+
    "</div>";

    html+="<div class=\"oc-r-actions\"><button id=\"oc-resource-refresh\" class=\"oc-r-action oc-r-primary\" type=\"button\">Volver a medir</button><button id=\"oc-resource-close\" class=\"oc-r-action\" type=\"button\">Cerrar</button></div>";

    panel.innerHTML=html;
    panel.classList.add("oc-r-open");

    var refresh=byId("oc-resource-refresh");
    var close=byId("oc-resource-close");
    if(refresh)refresh.onclick=load;
    if(close)close.onclick=function(){panel.classList.remove("oc-r-open");};
  }

  function load(){
    if(busy)return;

    var btn=findButton();
    var panel=ensurePanel(btn);

    busy=true;
    loading(panel);

    fetch("/api/resources?t="+Date.now(),{cache:"no-store"})
      .then(function(r){
        if(!r.ok)throw new Error("HTTP "+r.status);
        return r.json();
      })
      .then(function(d){
        render(panel,d);
        window.__OPTICORE_RESOURCES_LAST__=d;
      })
      .catch(function(e){
        errorPanel(panel,"Motor local no disponible o medición interrumpida.");
        window.__OPTICORE_RESOURCES_ERROR__=String(e);
      })
      .then(function(){
        busy=false;
      });

    setTimeout(function(){
      try{panel.scrollIntoView({behavior:"smooth",block:"start"});}catch(e){}
    },80);
  }

  document.addEventListener("click",function(e){
    var b=e.target && e.target.closest ? e.target.closest("button") : null;
    if(!b)return;

    var text=(b.textContent||"").toLowerCase();
    if(b.id==="rbtn" || text.indexOf("recomend")!==-1 || text.indexOf("diagn")!==-1){
      e.preventDefault();
      e.stopPropagation();
      load();
    }
  },true);

  window.OptiCoreResources={load:load};

})();
