(function(){
  "use strict";

  var nativeFetch=window.fetch.bind(window);
  var busy=false;
  var lastValue=null;

  function all(selector){
    return [].slice.call(document.querySelectorAll(selector));
  }

  function cleanNumber(v){
    var n=Number(v);
    if(!isFinite(n) || n<0) return null;
    return Math.round(n*10)/10;
  }

  function findExact(selector,text){
    return all(selector).find(function(el){
      return (el.textContent || "").trim()===text;
    });
  }

  function updateCacheCard(value){
    var title=findExact("h1,h2,h3,strong","Cache visible");
    if(!title) return;

    var card=title.closest(".card");
    if(!card) return;


    var nodes=card.querySelectorAll("*");
    var updated=false;

    for(var i=0;i<nodes.length;i++){
      var text=(nodes[i].textContent || "").trim();

      if(/^[0-9]+(?:\.[0-9]+)?\s*MB$/.test(text)){
        nodes[i].textContent=value.toFixed(1)+" MB";
        updated=true;
        break;
      }
    }

    var badges=card.querySelectorAll(".st,.tag");

    if(updated && badges.length){
      badges[badges.length-1].textContent="MEDIDO";
    }
  }

  function replaceTextPatterns(value,count){
    var walker=document.createTreeWalker(
      document.body,
      NodeFilter.SHOW_TEXT,
      null,
      false
    );

    var nodes=[];
    var node;

    while((node=walker.nextNode())){
      nodes.push(node);
    }

    nodes.forEach(function(n){
      var t=n.nodeValue || "";

      if(/[0-9]+(?:\.[0-9]+)?\s*MB visibles;/.test(t)){
        t=t.replace(
          /[0-9]+(?:\.[0-9]+)?\s*MB visibles;/g,
          value.toFixed(1)+" MB visibles;"
        );
      }

      if(/Total accesible:\s*[0-9]+(?:\.[0-9]+)?\s*MB en [0-9]+ aplicaciones\./.test(t)){
        t=t.replace(
          /Total accesible:\s*[0-9]+(?:\.[0-9]+)?\s*MB en [0-9]+ aplicaciones\./g,
          "Total accesible: "+value.toFixed(1)+" MB en "+count+" aplicaciones."
        );
      }

      n.nodeValue=t;
    });
  }

  function updateUI(data){
    var value=cleanNumber(data.total_visible_mb);

    if(value===null) return;

    var count=Number(data.accessible_cache_count)||0;

    lastValue=value;

    updateCacheCard(value);
    replaceTextPatterns(value,count);

    document.documentElement.setAttribute(
      "data-cache-live-mb",
      value.toFixed(1)
    );

    window.__OPTICORE_CACHE_LIVE__={
      total_visible_mb:value,
      accessible_cache_count:count,
      updated_at:data.updated_at || null
    };
  }

  function refreshStatusFile(){
    return nativeFetch("/api/refresh?t="+Date.now(),{
      cache:"no-store"
    }).catch(function(){
      return null;
    });
  }

  function scan(reason){
    if(busy) return Promise.resolve(null);

    busy=true;

    document.documentElement.classList.add("oc-cache-scanning");

    return nativeFetch("/api/cache-scan?t="+Date.now(),{
      cache:"no-store"
    })
    .then(function(r){
      if(!r.ok) throw new Error("cache-scan HTTP "+r.status);
      return r.json();
    })
    .then(function(data){
      updateUI(data);

      return refreshStatusFile().then(function(){
        window.dispatchEvent(new CustomEvent("opticore-cache-synced",{
          detail:{
            reason:reason || "manual",
            data:data
          }
        }));

        return data;
      });
    })
    .catch(function(err){
      window.__OPTICORE_CACHE_SYNC_ERROR__=String(err);
      return null;
    })
    .then(function(result){
      busy=false;
      document.documentElement.classList.remove("oc-cache-scanning");
      return result;
    });
  }

  window.OptiCoreCacheSync={
    scan:scan,
    getLastValue:function(){return lastValue;}
  };

  window.fetch=function(input,init){
    var url="";

    if(typeof input==="string"){
      url=input;
    }else if(input && input.url){
      url=input.url;
    }

    return nativeFetch(input,init).then(function(response){
      if(url.indexOf("/api/cache-clean")!==-1){
        try{
          var copy=response.clone();

          copy.json().then(function(data){
            if(data && data.ok && Number(data.cleaned_count)>0){
              setTimeout(function(){
                scan("after-clean");
              },350);
            }
          }).catch(function(){});
        }catch(e){}
      }

      return response;
    });
  };

  document.addEventListener("click",function(e){
    var t=e.target;
    if(!t) return;

    var label=(t.textContent || "").trim();

    if(label==="Volver a escanear"){
      setTimeout(function(){
        scan("rescan-button");
      },150);
    }
  },false);

  function boot(){
    setTimeout(function(){
      scan("page-load");
    },600);
  }

  if(document.readyState==="loading"){
    document.addEventListener("DOMContentLoaded",boot);
  }else{
    boot();
  }

})();
