(function(){
const btn=[].slice.call(document.querySelectorAll("button")).find(function(b){return b.textContent.replace(/\s+/g," ").trim().indexOf("Preparar limpieza")!==-1});
if(!btn)return;
const oldClick=btn.onclick;
const names={"org.telegram.messenger":"Telegram","com.zhiliaoapp.musically":"TikTok","com.mixplorer.silver":"MiXplorer","com.whatsapp":"WhatsApp","com.pixonic.wwr":"War Robots","com.google.android.apps.maps":"Google Maps","com.linkedin.android":"LinkedIn","com.qrcode.barcode.scanner.reader.generator.pro":"QR Scanner","com.sec.android.app.launcher":"Samsung Launcher","com.instagram.android":"Instagram","com.facebook.katana":"Facebook","com.waze":"Waze"};
const autoSafe=["com.zhiliaoapp.musically","com.mixplorer.silver","com.google.android.apps.maps","com.linkedin.android","com.qrcode.barcode.scanner.reader.generator.pro","com.instagram.android","com.facebook.katana","com.waze"];
let mode="intelligent";
function nm(p){return names[p]||p}
function isSystem(p){return p.indexOf("com.sec.")===0||p.indexOf("com.samsung.")===0||p==="com.android.systemui"||p==="com.google.android.gms"||p==="com.google.android.gsf"}
function isProtectedAuto(p){return p==="org.telegram.messenger"||p==="com.whatsapp"||p==="com.pixonic.wwr"}
function picks(){return [].slice.call(document.querySelectorAll(".cachePick:checked"))}
function recount(){let t=0;const a=picks();a.forEach(function(x){t+=Number(x.dataset.size)||0});const l=document.getElementById("cacheSelectedTotal"),r=document.getElementById("cacheReviewBtn");if(l)l.textContent=a.length+" "+(a.length===1?"seleccionada":"seleccionadas")+" · "+t.toFixed(1)+" MB";if(r){r.disabled=!a.length;r.style.opacity=a.length?"1":".55"}}
function setMode(m){
 mode=m;
 document.getElementById("smartAi").style.outline=m==="intelligent"?"2px solid #54d7cb":"none";
 document.getElementById("smartManual").style.outline=m==="manual"?"2px solid #54d7cb":"none";
 document.querySelectorAll(".cachePick").forEach(function(x){
  const p=x.dataset.package,s=Number(x.dataset.size)||0;
  x.disabled=isSystem(p);
  x.checked=m==="intelligent"&&!isSystem(p)&&!isProtectedAuto(p)&&autoSafe.indexOf(p)!==-1&&s>=1;
 });
 document.getElementById("smartInfo").textContent=m==="intelligent"?"Motor conservador: selecciona solo cachés de bajo riesgo. Telegram, WhatsApp, juegos y sistema quedan fuera.":"Modo manual: tú eliges; los componentes del sistema continúan bloqueados.";
 const area=document.getElementById("cacheReviewArea");if(area)area.innerHTML="";
 recount();
}
function smartReview(){
 const a=picks(),area=document.getElementById("cacheReviewArea");if(!a.length||!area)return;let total=0,h="<div style=\"font-size:19px;font-weight:800\">Confirmación de limpieza</div><div style=\"opacity:.8;margin:6px 0 10px\">Solo se eliminará contenido temporal dentro de /Android/data/&lt;paquete&gt;/cache/.</div>";
 a.forEach(function(x){const s=Number(x.dataset.size)||0;total+=s;h+="<div style=\"display:flex;justify-content:space-between;padding:8px 0;border-top:1px solid rgba(120,180,200,.15)\"><span>"+nm(x.dataset.package)+"</span><strong>"+s.toFixed(1)+" MB</strong></div>"});
 h+="<div style=\"margin-top:10px;font-weight:800\">Total estimado: "+total.toFixed(1)+" MB</div><label style=\"display:flex;gap:10px;margin-top:12px;padding:12px;border:1px solid rgba(255,190,90,.25);border-radius:12px\"><input id=\"smartAck\" type=\"checkbox\" style=\"width:20px;height:20px\"><span>Entiendo que algunas apps pueden volver a descargar miniaturas o recursos temporales.</span></label><button id=\"smartRun\" disabled style=\"width:100%;margin-top:12px;padding:13px;border-radius:14px;opacity:.55\">Limpiar caché seleccionada</button><div style=\"margin-top:10px;color:#7fe3c5;font-weight:700\">No se tocarán chats, fotos personales, documentos, cuentas ni configuraciones.</div>";
 area.innerHTML=h;
 const ack=document.getElementById("smartAck"),run=document.getElementById("smartRun");ack.onchange=function(){run.disabled=!ack.checked;run.style.opacity=ack.checked?"1":".55"};run.onclick=doClean;area.scrollIntoView({behavior:"smooth",block:"nearest"});
}
async function doClean(){
 const a=picks();if(!a.length)return;
 if(!confirm("CONFIRMACIÓN FINAL\n\nSe eliminará únicamente caché temporal de:\n"+a.map(function(x){return nm(x.dataset.package)}).join(", ")+"\n\n¿Continuar?"))return;
 const run=document.getElementById("smartRun");run.disabled=true;run.textContent="Limpiando...";
 const pk=a.map(function(x){return x.dataset.package}).join(",");
 try{
  const r=await fetch("/api/cache-clean?mode="+mode+"&packages="+pk+"&token=CACHE-CLEAN-2026&t="+Date.now(),{cache:"no-store"});if(!r.ok)throw 0;const d=await r.json();if(!d.ok)throw 0;
  let h="<div style=\"font-size:20px;font-weight:800\">Limpieza terminada</div><div style=\"margin:8px 0;color:#7fe3c5;font-weight:800\">Espacio liberado realmente: "+d.freed_mb+" MB</div>";
  (d.results||[]).forEach(function(x){h+="<div style=\"display:flex;justify-content:space-between;padding:8px 0;border-top:1px solid rgba(120,180,200,.15)\"><span>"+nm(x.package)+" · "+x.status+"</span><strong>"+x.freed_mb+" MB</strong></div>"});
  h+="<button id=\"rescanSmart\" style=\"width:100%;margin-top:12px;padding:13px;border-radius:14px\">Volver a escanear</button>";
  document.getElementById("cacheReviewArea").innerHTML=h;document.getElementById("rescanSmart").onclick=function(){btn.click()};if(typeof toast==="function")toast("Limpieza completada: "+d.freed_mb+" MB liberados");
 }catch(e){run.disabled=false;run.textContent="Limpiar caché seleccionada";if(typeof toast==="function")toast("La limpieza no pudo completarse")}
}
function enhance(){
 const panel=document.getElementById("cacheInventoryPanel"),first=document.querySelector(".cachePick");if(!panel||!first)return;
 let box=document.getElementById("smartModes");
 if(!box){
  box=document.createElement("div");box.id="smartModes";box.innerHTML="<div style=\"font-size:18px;font-weight:800;margin-bottom:8px\">Modo de limpieza</div><div style=\"display:grid;grid-template-columns:1fr 1fr;gap:8px\"><button id=\"smartAi\">Inteligente</button><button id=\"smartManual\">Manual</button></div><div id=\"smartInfo\" style=\"font-size:13px;opacity:.75;margin:9px 0 12px\"></div>";
  panel.insertBefore(box,first.closest("label"));
  document.getElementById("smartAi").onclick=function(){setMode("intelligent")};document.getElementById("smartManual").onclick=function(){setMode("manual")};
 }
 document.querySelectorAll(".cachePick").forEach(function(x){const row=x.closest("label");if(isSystem(x.dataset.package)){x.disabled=true;row.style.opacity=".55"}});
 const review=document.getElementById("cacheReviewBtn");if(review)review.onclick=smartReview;
 setMode("intelligent");
}
btn.onclick=async function(e){await oldClick.call(this,e);enhance()};
})();