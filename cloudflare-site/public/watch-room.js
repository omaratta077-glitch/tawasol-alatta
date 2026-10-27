/* Private sports watch-room client: reuses the signed-in API/TURN and the browser's existing WebRTC stack. */
(()=>{'use strict';
const bridge=window.AttaWeb,mount=document.getElementById('watchRoomMount');if(!bridge||!mount)return;
let active=false,room=null,stream=null,clientId='',cursor=0,timer=null,muted=false,cameraOff=false,busy=false,generation=0;
const peers=new Map(),tracks=new Map(),seen=new Set();let signaling=Promise.resolve();
const request=payload=>bridge.watchRequest({...payload,clientId});
const notice=document.createElement('p');notice.className='native-muted';
const entry=document.createElement('div');entry.className='native-card';
entry.innerHTML='<button class="native-button" data-watch-open>شاهد مع أصحابك</button><div data-watch-form hidden><p>غرفة فيديو وصوت من شخصين حتى ستة. الدخول لأصدقاء صاحب الغرفة المضافين في العطا.</p><p>شغّل القناة عندك واستمر في الكلام مع أصحابك.</p><button class="native-button" data-watch-create>إنشاء غرفة</button><input maxlength="32" data-watch-code placeholder="كود غرفة صاحبك" aria-label="كود غرفة صاحبك"><button class="native-button" data-watch-join>دخول الغرفة</button></div>';
const panel=document.createElement('section');panel.className='native-card watch-room';panel.hidden=true;
panel.innerHTML='<div class="watch-heading"><strong>غرفة المشاهدة <span data-watch-count></span></strong><button class="native-button" data-watch-copy>نسخ الكود</button><button class="native-button" data-watch-fold>تصغير</button></div><p data-watch-label></p><div class="watch-stage"></div><button class="native-button" data-watch-play>تشغيل القناة</button><div class="watch-tools"><button class="native-button" data-watch-mic>كتم الميكروفون</button><button class="native-button" data-watch-camera>إغلاق الكاميرا</button><button class="native-button" data-watch-pip>نافذة صغيرة</button><button class="native-button" data-watch-leave>مغادرة</button></div>';
mount.append(entry,panel,notice);
const bar=document.createElement('div');bar.className='watch-ongoing';bar.hidden=true;
bar.innerHTML='<button class="native-button" data-watch-return>العودة لغرفة المشاهدة</button><button class="native-button" data-watch-stop>مغادرة</button>';document.body.append(bar);
function status(text){notice.textContent=text;}
function render(){
 entry.hidden=active;panel.hidden=!active;
 if(!room)return;
 panel.querySelector('[data-watch-count]').textContent=room.members.length+'/6';
 panel.querySelector('[data-watch-label]').textContent=room.channelName+' • '+room.code;
 panel.querySelector('[data-watch-play]').hidden=!room.channelId;
 const stage=panel.querySelector('.watch-stage'),ids=new Set(room.members.map(m=>m.peerId));
 stage.querySelectorAll('[data-watch-peer]').forEach(node=>{if(!ids.has(node.dataset.watchPeer)){node.querySelector('video').srcObject=null;node.remove();}});
 for(const member of room.members){
  let tile=[...stage.children].find(n=>n.dataset.watchPeer===member.peerId);
  if(!tile){tile=document.createElement('div');tile.dataset.watchPeer=member.peerId;tile.className='watch-tile';
   const video=document.createElement('video');video.autoplay=true;video.playsInline=true;video.muted=member.peerId===room.myPeerId;
   tile.append(video,document.createElement('span'));stage.append(tile);}
  const video=tile.querySelector('video'),source=member.peerId===room.myPeerId?stream:tracks.get(member.peerId);
  if(source&&video.srcObject!==source){video.srcObject=source;video.play().catch(()=>status('اضغط على فيديو صاحبك لتفعيل الصوت'));video.onclick=()=>video.play().catch(()=>{});}
  video.style.opacity=member.cameraOn?'1':'0';tile.querySelector('span').textContent=member.name+(!member.micOn?' • صامت':'');
 }
}
async function send(to,type,extra={}){
 const state=room,ownClient=clientId,epoch=generation;if(!active||!state)return;
 const packet={type,to,signalId:crypto.randomUUID(),...extra};
 for(let i=0;;i++)try{if(!active||epoch!==generation)return;return await bridge.watchRequest({action:'signal',clientId:ownClient,code:state.code,peerId:state.myPeerId,signal:packet});}
 catch(error){if(i>=2)throw error;await new Promise(r=>setTimeout(r,350));}
}
async function offer(id,restart=false){const link=peers.get(id);if(!link||link.pc.signalingState!=='stable')return;
 const description=await link.pc.createOffer(restart?{iceRestart:true}:{});await link.pc.setLocalDescription(description);await send(id,'offer',{sdp:{type:'offer',sdp:description.sdp}});}
async function makePeer(id,ice){
 if(peers.has(id))return peers.get(id);
 const pc=new RTCPeerConnection(ice),link={pc,pending:[],attempts:0};peers.set(id,link);
 for(const track of stream.getTracks()){const sender=pc.addTrack(track,stream);if(track.kind==='video'){const p=sender.getParameters();p.encodings??=[{}];for(const encoding of p.encodings){encoding.maxBitrate=200000;encoding.maxFramerate=15;}sender.setParameters(p).catch(()=>{});}}
 pc.onicecandidate=e=>{if(e.candidate&&active)send(id,'ice',{candidate:e.candidate.toJSON()}).catch(()=>{});};
 pc.ontrack=e=>{tracks.set(id,e.streams[0]||new MediaStream([e.track]));render();};
 pc.oniceconnectionstatechange=()=>{if(pc.iceConnectionState==='connected')status('متصل');if(pc.iceConnectionState==='failed'&&link.attempts++<2){if(room.myPeerId<id)offer(id,true).catch(()=>{});else send(id,'restart').catch(()=>{});}};
 return link;
}
async function receive(packet,ice){
 if(!active||!room||packet.code!==room.code||packet.to!==room.myPeerId||!room.members.some(m=>m.peerId===packet.from)||seen.has(packet.signalId))return;
 seen.add(packet.signalId);if(seen.size>512)seen.delete(seen.values().next().value);
 const link=await makePeer(packet.from,ice),pc=link.pc;
 if(packet.type==='offer'&&packet.from<room.myPeerId){await pc.setRemoteDescription(packet.sdp);const answer=await pc.createAnswer();await pc.setLocalDescription(answer);await send(packet.from,'answer',{sdp:{type:'answer',sdp:answer.sdp}});}
 else if(packet.type==='answer'&&pc.signalingState==='have-local-offer')await pc.setRemoteDescription(packet.sdp);
 else if(packet.type==='ice'){if(pc.remoteDescription)await pc.addIceCandidate(packet.candidate);else if(link.pending.length<128)link.pending.push(packet.candidate);}
 else if(packet.type==='restart'&&room.myPeerId<packet.from)await offer(packet.from,true);
 if(pc.remoteDescription)for(const candidate of link.pending.splice(0))await pc.addIceCandidate(candidate);
}
async function join(action){
 if(busy||active)return;if(bridge.callBusy())return status('غادر المكالمة الحالية أولًا');
 const code=entry.querySelector('[data-watch-code]').value.replace(/[\s-]/g,'').toUpperCase();
 if(action==='join'&&!/^[A-F0-9]{12}$/.test(code))return status('الصق كود الغرفة كاملًا');
 if(!navigator.mediaDevices?.getUserMedia||!window.RTCPeerConnection)return status('المتصفح لا يدعم مكالمات الفيديو؛ افتح تطبيق العطا');
 busy=true;const epoch=++generation;clientId=crypto.randomUUID();const ownClient=clientId;status('جارٍ تجهيز الغرفة');
 try{
  const local=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:{width:{ideal:480},height:{ideal:270},frameRate:{ideal:15,max:15}}});
  if(epoch!==generation){local.getTracks().forEach(t=>t.stop());return;}stream=local;
  const selected=document.querySelector('[data-football-channel]')?.dataset.footballChannel||'';
  const initial=await request({action,code,channelId:action==='create'?selected:''});
  if(epoch!==generation){local.getTracks().forEach(t=>t.stop());await bridge.watchRequest({action:'leave',clientId:ownClient}).catch(()=>{});return;}
  room=initial;active=true;cursor=0;render();
  const ice=await bridge.watchIce();let failures=0;
  async function poll(){if(!active||epoch!==generation)return;
   try{const result=await request({action:'poll',code:room.code,peerId:room.myPeerId,after:cursor,micOn:!muted,cameraOn:!cameraOff});if(epoch!==generation||!active)return;room=result;
    const remote=new Set(room.members.filter(m=>m.peerId!==room.myPeerId).map(m=>m.peerId));
    for(const [id,link]of peers)if(!remote.has(id)){link.pc.close();peers.delete(id);tracks.delete(id);}
    for(const id of remote)if(!peers.has(id)){await makePeer(id,ice);if(room.myPeerId<id)signaling=signaling.then(()=>offer(id)).catch(()=>status('تعذر توصيل أحد المشاركين'));}
    for(const event of result.signals||[])signaling=signaling.then(()=>receive(event,ice)).catch(()=>status('جارٍ استعادة الاتصال'));
    cursor=result.cursor;failures=0;render();status(room.members.length===1?'بانتظار أصحابك':'مكالمة الغرفة جارية');
   }catch(error){if(epoch!==generation)return;if(++failures>=3){await leave();return status(error.message||'تعذر الاتصال بالغرفة');}}
   if(active)timer=setTimeout(poll,1500);
  }poll();
 }catch(error){if(epoch===generation){await leave();status(error.message||'اسمح بالكاميرا والميكروفون');}}
 finally{busy=false;}
}
async function leave(keepalive=false){
 const hadClient=clientId;generation++;clientId='';active=false;clearTimeout(timer);timer=null;
 for(const link of peers.values())link.pc.close();peers.clear();tracks.clear();seen.clear();
 stream?.getTracks().forEach(t=>t.stop());stream=null;
 panel.querySelectorAll('video').forEach(v=>{v.pause();v.srcObject=null;});panel.querySelector('.watch-stage').replaceChildren();
 room=null;entry.hidden=false;panel.hidden=true;bar.hidden=true;muted=false;cameraOff=false;
 panel.querySelector('.watch-stage').hidden=false;panel.querySelector('[data-watch-mic]').textContent='كتم الميكروفون';panel.querySelector('[data-watch-camera]').textContent='إغلاق الكاميرا';
 if(hadClient)try{await bridge.watchRequest({action:'leave',clientId:hadClient},keepalive);}catch{}

}
entry.querySelector('[data-watch-open]').onclick=()=>{const form=entry.querySelector('[data-watch-form]');form.hidden=!form.hidden;};
entry.querySelector('[data-watch-create]').onclick=()=>join('create');entry.querySelector('[data-watch-join]').onclick=()=>join('join');
panel.querySelector('[data-watch-copy]').onclick=()=>navigator.clipboard?.writeText(room?.code||'').catch(()=>status('تعذر نسخ الكود'));
panel.querySelector('[data-watch-fold]').onclick=()=>{panel.querySelector('.watch-stage').hidden=!panel.querySelector('.watch-stage').hidden;};
panel.querySelector('[data-watch-mic]').onclick=e=>{muted=!muted;stream?.getAudioTracks().forEach(t=>t.enabled=!muted);e.target.textContent=muted?'فتح الميكروفون':'كتم الميكروفون';};
panel.querySelector('[data-watch-camera]').onclick=e=>{cameraOff=!cameraOff;stream?.getVideoTracks().forEach(t=>t.enabled=!cameraOff);e.target.textContent=cameraOff?'فتح الكاميرا':'إغلاق الكاميرا';};
panel.querySelector('[data-watch-pip]').onclick=()=>{const video=[...panel.querySelectorAll('video')].find(v=>!v.muted&&v.srcObject)||panel.querySelector('video');video?.requestPictureInPicture?.().catch(()=>status('يمكنك تصغير لوحة الغرفة داخل الصفحة'));};
panel.querySelector('[data-watch-play]').onclick=()=>{const button=[...document.querySelectorAll('[data-football-channel]')].find(b=>b.dataset.footballChannel===room?.channelId);if(button)button.click();else status('افتح تبويب القنوات واختر '+(room?.channelName||'القناة'));};
panel.querySelector('[data-watch-leave]').onclick=()=>leave();bar.querySelector('[data-watch-stop]').onclick=()=>leave();bar.querySelector('[data-watch-return]').onclick=()=>bridge.navigate('sportsPage');
document.addEventListener('atta:page',e=>{bar.hidden=!active||e.detail.pageId==='sportsPage';});
window.addEventListener('beforeunload',()=>leave(true));
window.AttaWatchRoom=Object.freeze({get active(){return active;},leave});
})();
