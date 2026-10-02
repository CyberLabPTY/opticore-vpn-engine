'use strict';

const $ = (id) => document.getElementById(id);
const state = {
  embedded: location.hostname === '127.0.0.1' || location.hostname === 'localhost',
  installPrompt: null,
  stream: null,
  lastPhoto: null,
  cacheEntries: [],
  deviceInfo: null,
  vpnTimer: null
};

function setText(id, value){ const el=$(id); if(el) el.textContent = value == null || value === '' ? '—' : String(value); }
function fmtMb(v){ const n=Number(v); return Number.isFinite(n) ? n.toFixed(1)+' MB' : '—'; }
function fmtMhz(v){ const n=Number(v); return Number.isFinite(n) && n>0 ? Math.round(n)+' MHz' : 'N/D'; }
function escapeHtml(s){ return String(s).replace(/[&<>"']/g, m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[m])); }

async function fetchJson(url, timeout=12000, options={}){
  const c = new AbortController();
  const t = setTimeout(()=>c.abort(), timeout);
  try{
    const r = await fetch(url,{cache:'no-store',signal:c.signal,...options});
    if(!r.ok) throw new Error('HTTP '+r.status);
    return await r.json();
  } finally { clearTimeout(t); }
}

function browserNetwork(){
  const c = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
  setText('netType', c && c.effectiveType ? c.effectiveType.toUpperCase() : (navigator.onLine?'En línea':'Sin conexión'));
  setText('netRtt', c && Number.isFinite(c.rtt) ? c.rtt+' ms' : 'N/D');
  setText('netDown', c && Number.isFinite(c.downlink) ? c.downlink+' Mbps' : 'N/D');
  setText('networkState', navigator.onLine ? 'Conectada' : 'Sin conexión');
}

function formatDuration(seconds){
  const total=Math.max(0,Number(seconds)||0);
  const h=Math.floor(total/3600), m=Math.floor((total%3600)/60), s=Math.floor(total%60);
  return h>0 ? h+' h '+String(m).padStart(2,'0')+' min' : m>0 ? m+' min '+String(s).padStart(2,'0')+' s' : s+' s';
}

function formatRate(v){
  const n=Math.max(0,Number(v)||0);
  if(n>=1024*1024) return (n/(1024*1024)).toFixed(1)+' MB/s';
  if(n>=1024) return (n/1024).toFixed(1)+' KB/s';
  return Math.round(n)+' B/s';
}

function renderWebVpn(){
  setText('vpnState','Requiere app');
  $('vpnState').className='mini warn';
  setText('vpnProtection','VPN disponible mediante el motor Android');
  setText('vpnDetail','La PWA pública conserva la misma interfaz, pero una página web no puede crear ni controlar por sí sola un túnel WireGuard del sistema.');
  setText('vpnEndpoint','Motor Android');
  setText('vpnConfig','Desde la app');
  setText('vpnUptime','—');
  setText('vpnTraffic','—');
  $('vpnShield').className='vpn-shield wait';
  $('vpnConnectBtn').disabled=true;
  $('vpnDisconnectBtn').disabled=true;
  $('vpnRefreshBtn').disabled=true;
}

function renderVpnStatus(d){
  const connected=!!d.connected;
  const configured=!!d.has_config;
  setText('vpnState',connected?'Activa':'Desconectada');
  $('vpnState').className='mini '+(connected?'ok':'off');
  setText('vpnProtection',connected?'PROTECCIÓN ACTIVA':'VPN DESCONECTADA');
  setText('vpnEndpoint',configured?(d.endpoint&&d.endpoint!=='--'?d.endpoint:'Guardado'):'Sin configuración');
  setText('vpnConfig',configured?'GUARDADA':'PENDIENTE');
  setText('vpnUptime',connected?formatDuration(d.connected_seconds):'—');
  setText('vpnTraffic',connected?formatRate(d.rx_bps)+' / '+formatRate(d.tx_bps):'0 B/s / 0 B/s');
  $('vpnShield').className='vpn-shield '+(connected?'':'off');
  $('vpnConnectBtn').disabled=connected||!configured;
  $('vpnDisconnectBtn').disabled=!connected;
  $('vpnRefreshBtn').disabled=false;
  let detail=connected?'WireGuard está activo y el motor Android está reportando telemetría local.':configured?'La configuración WireGuard está guardada. Puedes conectar la protección.':'Abre el motor Android para autorizar la VPN y guardar la configuración WireGuard.';
  if(d.last_error){
    detail=d.last_error==='VPN_PERMISSION_REQUIRED'?'Falta autorizar la VPN en Android. Abre el motor Android y toca “Autorizar VPN en Android”.':d.last_error;
  }
  setText('vpnDetail',detail);
  setText('vpnMessage',d.last_error?detail:'Los controles de esta sección actúan únicamente sobre el motor VPN local de OptiCore.');
}

async function refreshVpn(){
  if(!state.embedded){ renderWebVpn(); return; }
  try{
    const d=await fetchJson('./api/vpn-status',6000);
    renderVpnStatus(d);
  }catch(e){
    setText('vpnState','Motor sin respuesta');
    $('vpnState').className='mini warn';
    setText('vpnProtection','No se pudo leer el estado de la VPN');
    setText('vpnDetail','El panel continúa disponible. Abre el motor Android si necesitas reactivar el servicio local.');
    $('vpnShield').className='vpn-shield wait';
    $('vpnConnectBtn').disabled=true;
    $('vpnDisconnectBtn').disabled=true;
  }
}

async function vpnAction(action){
  if(!state.embedded) return;
  const connect=action==='connect';
  const btn=connect?$('vpnConnectBtn'):$('vpnDisconnectBtn');
  btn.disabled=true;
  setText('vpnMessage',connect?'Conectando protección…':'Desconectando VPN…');
  try{
    await fetchJson(connect?'./api/vpn-connect':'./api/vpn-disconnect',8000,{method:'POST'});
    await new Promise(r=>setTimeout(r,connect?1500:600));
    await refreshVpn();
  }catch(e){
    setText('vpnMessage','No se pudo enviar la orden al motor Android.');
    await refreshVpn();
  }
}

async function refreshEmbedded(){
  const [device, resources] = await Promise.all([
    fetchJson('./api/refresh').catch(()=>null),
    fetchJson('./api/resources').catch(()=>null)
  ]);
  if(!device && !resources) throw new Error('Motor local no disponible');

  $('resourceSource').textContent='Motor Android';
  if(device){
    state.deviceInfo=device;
    setText('ramValue', Number(device.ram_available_percent).toFixed(1)+'%');
    setText('swapValue', Number(device.swap_used_percent).toFixed(1)+'%');
    setText('cpuValue', fmtMhz(device.cpu_performance_current_mhz || device.cpu_efficiency_current_mhz));
    setText('gpuValue', fmtMhz(device.gpu_current_mhz));
    setText('tempValue', device.battery_celsius === 'N/D' ? 'N/D' : device.battery_celsius+' °C');
    setText('ifaceValue', device.network_interface);
    setText('versionText', 'Motor '+(device.engine_version || 'embebido'));
  }
  if(resources){
    const label = resources.overall_state === 'atencion' ? 'Requiere atención' : resources.overall_state === 'vigilar' ? 'Conviene vigilar' : 'Estado estable';
    setText('overallState', label);
    setText('overallDetail', resources.recommendation || 'Diagnóstico local actualizado.');
    setText('resourceNote', 'Lecturas del motor Android. Las frecuencias de CPU/GPU no equivalen a porcentaje de uso.');
  }
}

function refreshBrowser(){
  $('resourceSource').textContent='Navegador';
  const dm = navigator.deviceMemory;
  const cores = navigator.hardwareConcurrency;
  setText('ramValue', dm ? '~'+dm+' GB expuestos' : 'No expuesta');
  setText('swapValue', 'No disponible');
  setText('cpuValue', cores ? cores+' hilos lógicos' : 'N/D');
  setText('gpuValue', 'No expuesta');
  setText('tempValue', 'No disponible');
  setText('ifaceValue', 'Navegador');
  setText('overallState', navigator.onLine ? 'OptiCore listo' : 'Modo sin conexión');
  setText('overallDetail', 'La versión web usa APIs del navegador. Para métricas Android ampliadas abre el panel desde la app OptiCore.');
}

async function refreshAll(){
  browserNetwork();
  $('refreshAll').disabled=true;
  $('refreshAll').textContent='Diagnosticando…';
  try{
    if(state.embedded) await refreshEmbedded(); else refreshBrowser();
    await refreshVpn();
  }catch(e){
    refreshBrowser();
    $('resourceSource').textContent='Modo web';
    setText('overallDetail','El panel local no respondió; OptiCore continúa con las capacidades seguras del navegador.');
  }finally{
    $('refreshAll').disabled=false;
    $('refreshAll').textContent='Diagnosticar ahora';
  }
}

async function analyzeDns(){
  $('dnsBtn').disabled=true; $('dnsBtn').textContent='Analizando…';
  try{
    let d;
    if(state.embedded){
      d = await fetchJson('./api/dns',35000);
    }else if(window.OptiCoreBridge &&
             typeof window.OptiCoreBridge.fetchDns==='function' &&
             window.OptiCoreBridge.isLinked()){
      d = await window.OptiCoreBridge.fetchDns();
    }else{
      setText('dnsCandidate','Enlaza OptiCore Android');
      setText('dnsDetail','La web pública necesita el puente seguro con la app para medir DNS reales desde el dispositivo.');
      return;
    }
    setText('dnsCandidate', d.latency_stability_candidate || d.doh_candidate || '—');
    const c=d.cloudflare||{}, q=d.quad9||{}, g=d.google||{}, cd=d.controld_hagezi_pro||{};
    const cdDoh=Number(d.controld_hagezi_pro_doh_average_ms);
    setText('dnsDetail',
      'Cloudflare '+(c.avg_ms||'N/D')+' ms · '+
      'Quad9 '+(q.avg_ms||'N/D')+' ms · '+
      'Google '+(g.avg_ms||'N/D')+' ms · '+
      'Control D HaGeZi Pro '+(cd.avg_ms||'N/D')+' ms'+
      (Number.isFinite(cdDoh)&&cdDoh<9999?' · DoH '+cdDoh+' ms':'')+
      '. Mejor muestra: '+(d.latency_stability_candidate||'—')+
      '. No se cambia el DNS automáticamente.');
  }catch(e){ setText('dnsDetail','No se pudo completar el análisis DNS en este momento.'); }
  finally{ $('dnsBtn').disabled=false; $('dnsBtn').textContent='Analizar DNS'; }
}

async function scanCache(){
  $('scanCacheBtn').disabled=true; setText('cleanState','Escaneando…'); $('cacheList').innerHTML='';
  try{
    if(!state.embedded){
      const est = navigator.storage && navigator.storage.estimate ? await navigator.storage.estimate() : null;
      const used=est&&Number.isFinite(est.usage)?fmtMb(est.usage/(1024*1024)):'N/D';
      setText('cleanState','Solo OptiCore');
      setText('cleanResult','El navegador reporta '+used+' de almacenamiento usado por este origen. No puede borrar cachés de otras aplicaciones.');
      return;
    }
    const d=await fetchJson('./api/cache-scan',20000);
    state.cacheEntries=Array.isArray(d.entries)?d.entries:[];
    setText('cleanState',fmtMb(d.total_visible_mb));
    if(d.access_error){
      setText('cleanResult',d.access_error==='permission_required'?'Concede a OptiCore el permiso de almacenamiento desde la app Android.':'Android protege esas carpetas en esta versión.');
    }
    renderCacheEntries();
  }catch(e){ setText('cleanState','No disponible'); setText('cleanResult','No fue posible leer la caché accesible.'); }
  finally{ $('scanCacheBtn').disabled=false; }
}

function renderCacheEntries(){
  const box=$('cacheList'); box.innerHTML='';
  state.cacheEntries.forEach((e,i)=>{
    const row=document.createElement('div'); row.className='cache-item';
    row.innerHTML='<label><input type="checkbox" class="cacheCheck" data-index="'+i+'"><span>'+escapeHtml(e.package)+'</span></label><small>'+fmtMb(e.size_mb)+'</small>';
    box.appendChild(row);
  });
  $('cleanSelectedBtn').hidden=state.cacheEntries.length===0;
  if(state.cacheEntries.length===0) setText('cleanResult','No se encontraron cachés externas accesibles.');
}

async function cleanSelected(){
  const selected=[...document.querySelectorAll('.cacheCheck:checked')].map(x=>state.cacheEntries[Number(x.dataset.index)]?.package).filter(Boolean);
  if(!selected.length){ setText('cleanResult','Selecciona al menos una entrada.'); return; }
  if(!window.confirm('OptiCore limpiará únicamente las cachés seleccionadas que Android permita. ¿Continuar?')) return;
  $('cleanSelectedBtn').disabled=true; $('cleanSelectedBtn').textContent='Limpiando…';
  try{
    const q=new URLSearchParams({token:'CACHE-CLEAN-2026',mode:'manual',packages:selected.join(',')});
    const d=await fetchJson('./api/cache-clean?'+q.toString(),20000);
    if(!d.ok) throw new Error(d.error||'clean_failed');
    setText('cleanResult','Liberados '+Number(d.freed_mb||0).toFixed(1)+' MB · '+d.cleaned_count+' procesadas · '+d.skipped_count+' omitidas.');
    await scanCache();
  }catch(e){ setText('cleanResult','La limpieza no se pudo completar: '+e.message); }
  finally{ $('cleanSelectedBtn').disabled=false; $('cleanSelectedBtn').textContent='Limpiar seleccionadas'; }
}

async function cleanOpticore(){
  try{
    if('caches' in window){
      const keys=await caches.keys(); await Promise.all(keys.map(k=>caches.delete(k)));
    }
    localStorage.clear(); sessionStorage.clear();
    setText('cleanResult','Datos temporales propios de OptiCore limpiados. Se conservarán únicamente los datos que gestione la app Android fuera del navegador.');
    if('serviceWorker' in navigator) navigator.serviceWorker.getRegistration().then(r=>r&&r.update());
  }catch(e){ setText('cleanResult','No se pudieron limpiar todos los datos temporales del sitio.'); }
}

async function startCamera(){
  try{
    if(state.stream) state.stream.getTracks().forEach(t=>t.stop());
    state.stream=await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:'environment'},width:{ideal:1920},height:{ideal:1080}},audio:false});
    $('cameraVideo').srcObject=state.stream; $('cameraVideo').hidden=false; $('cameraPreview').hidden=true;
    $('cameraCapture').disabled=false; setText('cameraState','Activa');
    const track=state.stream.getVideoTracks()[0]; const settings=track.getSettings?track.getSettings():{};
    const d=state.deviceInfo;
    const nativeCaps=d ? ' · RAW '+(Number(d.camera_raw)?'sí':'no')+' · manual '+(Number(d.camera_manual_sensor)?'sí':'no') : '';
    setText('cameraInfo','Captura web '+(settings.width||'—')+'×'+(settings.height||'—')+'. El procesamiento permanece local'+nativeCaps+'.');
  }catch(e){ setText('cameraState','Sin permiso'); setText('cameraInfo','No se pudo abrir la cámara. Revisa el permiso de cámara del navegador.'); }
}

function captureCamera(){
  const v=$('cameraVideo'); if(!v.videoWidth) return;
  const c=$('cameraCanvas'); c.width=v.videoWidth; c.height=v.videoHeight;
  const ctx=c.getContext('2d',{alpha:false}); ctx.filter='none'; ctx.drawImage(v,0,0,c.width,c.height);
  state.lastPhoto=c.toDataURL('image/jpeg',Number($('jpegQuality').value)/100);
  $('cameraPreview').src=state.lastPhoto; $('cameraPreview').hidden=false; v.hidden=true; $('cameraEnhance').disabled=false; $('cameraSave').disabled=false;
  setText('cameraState','Capturada');
}

function saveCamera(){
  if(!state.lastPhoto) return;
  const a=document.createElement('a');
  a.href=state.lastPhoto;
  a.download='opticore-photo-'+new Date().toISOString().replace(/[:.]/g,'-')+'.jpg';
  document.body.appendChild(a);
  a.click();
  a.remove();
}

function enhanceCamera(){
  if(!state.lastPhoto) return;
  const img=new Image();
  img.onload=()=>{
    const c=$('cameraCanvas'); c.width=img.width; c.height=img.height;
    const ctx=c.getContext('2d',{alpha:false});
    ctx.filter='contrast(1.08) saturate(1.06) brightness(1.02)';
    ctx.drawImage(img,0,0,c.width,c.height); ctx.filter='none';
    state.lastPhoto=c.toDataURL('image/jpeg',Number($('jpegQuality').value)/100);
    $('cameraPreview').src=state.lastPhoto; setText('cameraState','Mejora local aplicada');
  };
  img.src=state.lastPhoto;
}

function renderCapabilities(){
  const caps=[
    ['Diagnóstico de red','Sí','Estado online, tipo de red y métricas expuestas por el navegador.'],
    ['Métricas Android ampliadas',state.embedded?'Sí':'Requiere app','RAM disponible, ZRAM, CPU/GPU y temperatura cuando el motor local está activo.'],
    ['Limpiar otras apps',state.embedded?'Limitado':'No','Solo cachés externas accesibles y únicamente donde Android lo permite.'],
    ['Forzar liberación de RAM','No','Android administra la memoria; OptiCore no mata procesos del sistema.'],
    ['Cambiar DNS global','No desde web','El diagnóstico puede sugerir candidatos, pero el cambio global requiere controles del sistema/VPN.'],
    ['Cámara web','Sí','Captura y mejora local mediante APIs del navegador, sin alterar el firmware de cámara.']
  ];
  $('capabilities').innerHTML=caps.map(c=>'<div class="cap"><strong>'+escapeHtml(c[0])+' · '+escapeHtml(c[1])+'</strong><span>'+escapeHtml(c[2])+'</span></div>').join('');
}

async function clearEmbeddedWebCache(){
  if(!state.embedded) return;
  try{
    if('serviceWorker' in navigator){
      const regs=await navigator.serviceWorker.getRegistrations();
      await Promise.all(regs.map(r=>r.unregister()));
    }
    if('caches' in window){
      const keys=await caches.keys();
      await Promise.all(keys.filter(k=>k.startsWith('opticore-web-')).map(k=>caches.delete(k)));
    }
  }catch(e){}
}

function setupPwa(){
  if(state.embedded){
    clearEmbeddedWebCache();
  }else if('serviceWorker' in navigator){
    navigator.serviceWorker.register('./sw.js').catch(()=>{});
  }
  window.addEventListener('beforeinstallprompt',(e)=>{ e.preventDefault(); state.installPrompt=e; $('installBtn').hidden=false; });
  $('installBtn').addEventListener('click',async()=>{ if(!state.installPrompt)return; state.installPrompt.prompt(); await state.installPrompt.userChoice; state.installPrompt=null; $('installBtn').hidden=true; });
}

function setup(){
  setText('modeBadge',state.embedded?'Motor Android + PWA':'Web / PWA');
  setText('onlineBadge',navigator.onLine?'Red disponible':'Sin conexión');
  browserNetwork(); renderCapabilities(); setupPwa(); refreshAll();

  $('refreshAll').addEventListener('click',refreshAll);
  $('vpnConnectBtn').addEventListener('click',()=>vpnAction('connect'));
  $('vpnDisconnectBtn').addEventListener('click',()=>vpnAction('disconnect'));
  $('vpnRefreshBtn').addEventListener('click',refreshVpn);
  $('dnsBtn').addEventListener('click',analyzeDns);
  $('scanCacheBtn').addEventListener('click',scanCache);
  $('cleanSelectedBtn').addEventListener('click',cleanSelected);
  $('cleanOpticoreBtn').addEventListener('click',cleanOpticore);
  $('cameraStart').addEventListener('click',startCamera);
  $('cameraCapture').addEventListener('click',captureCamera);
  $('cameraEnhance').addEventListener('click',enhanceCamera);
  $('cameraSave').addEventListener('click',saveCamera);
  $('jpegQuality').addEventListener('input',()=>setText('qualityValue',$('jpegQuality').value+'%'));
  window.addEventListener('online',()=>{setText('onlineBadge','Red disponible');browserNetwork();});
  window.addEventListener('offline',()=>{setText('onlineBadge','Sin conexión');browserNetwork();});
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refreshAll();});
  if(state.embedded) state.vpnTimer=setInterval(refreshVpn,2500);
}
document.addEventListener('DOMContentLoaded',setup);
