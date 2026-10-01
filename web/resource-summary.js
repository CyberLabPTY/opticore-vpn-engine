(function(){
  "use strict";

  var originalFetch=window.fetch;
  var mainButton=null;
  var summaryNode=null;

  function buttons(){
    return [].slice.call(document.querySelectorAll("button"));
  }

  function getMainButton(){
    if(mainButton && document.documentElement.contains(mainButton)){
      return mainButton;
    }

    mainButton=buttons().find(function(b){
      var t=(b.textContent||"").toLowerCase();
      return t.indexOf("recomend")!==-1;
    }) || null;

    return mainButton;
  }

  function getSummaryNode(){
    if(summaryNode && document.documentElement.contains(summaryNode)){
      return summaryNode;
    }

    var nodes=document.querySelectorAll("p,small,span,div");

    for(var i=0;i<nodes.length;i++){
      var n=nodes[i];
      if(n.childElementCount!==0)continue;

      var t=(n.textContent||"").toLowerCase();

      if(t.indexOf("presion de memoria")!==-1 ||
         t.indexOf("presión de memoria")!==-1){
        summaryNode=n;
        summaryNode.classList.add("oc-resource-main-summary");
        return summaryNode;
      }
    }

    return null;
  }

  function one(v){
    var n=Number(v);
    return isFinite(n)?n.toFixed(1):null;
  }

  function state(v){
    var s=String(v||"").toLowerCase();
    if(s==="estable")return "Estable";
    if(s==="vigilar")return "Vigilar";
    if(s==="atencion")return "Atención";
    return "Sin clasificar";
  }

  function setBusy(active){
    var b=getMainButton();
    if(!b)return;

    if(!b.dataset.ocOriginalText){
      b.dataset.ocOriginalText=(b.textContent||"Recomendación").trim();
    }

    if(active){
      b.disabled=true;
      b.classList.add("oc-resource-btn-busy");
      b.textContent="Actualizando…";
    }else{
      b.disabled=false;
      b.classList.remove("oc-resource-btn-busy");
      b.textContent=b.dataset.ocOriginalText||"Recomendación";
    }
  }

  function updateSummary(d){
    if(!d)return;

    var n=getSummaryNode();
    var ram=one(d.ram_available_percent);
    var swap=one(d.swap_used_percent);
    var temp=d.battery_temp_supported?one(d.battery_temp_c):null;

    var parts=[];

    if(ram!==null)parts.push("RAM "+ram+" % disponible");
    if(swap!==null)parts.push("ZRAM/Swap "+swap+" %");
    if(temp!==null)parts.push(temp+" °C");

    parts.push("Estado: "+state(d.overall_state));

    if(n){
      n.textContent=parts.join(" · ");
      n.dataset.ocResourceSummary="true";
      n.title=d.updated_at ? "Última medición: "+d.updated_at : "";
    }

    var b=getMainButton();
    if(b){
      b.dataset.ocResourceState=String(d.overall_state||"");
      if(d.updated_at){
        b.title="Última medición: "+d.updated_at;
      }
    }
  }

  window.fetch=function(input,init){
    var url="";

    if(typeof input==="string"){
      url=input;
    }else if(input && input.url){
      url=input.url;
    }

    var resources=url.indexOf("/api/resources")!==-1;

    if(!resources){
      return originalFetch.apply(window,arguments);
    }

    setBusy(true);

    return originalFetch.apply(window,arguments).then(function(response){
      try{
        var copy=response.clone();

        copy.json().then(function(data){
          updateSummary(data);
        }).catch(function(){}).then(function(){
          setBusy(false);
        });
      }catch(e){
        setBusy(false);
      }

      return response;
    },function(error){
      setBusy(false);
      throw error;
    });
  };

  if(window.__OPTICORE_RESOURCES_LAST__){
    updateSummary(window.__OPTICORE_RESOURCES_LAST__);
  }

  window.OptiCoreResourceSummary={
    update:updateSummary
  };

})();
