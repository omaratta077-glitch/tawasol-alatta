
(()=>{
const $=id=>document.getElementById(id);

let token="",me=null,ws=null,reconnectTimer=null,syncTimer=null,lastConversationSignature="";
let callSignalPollTimer=null,callSignalPollBusy=false;
let callFallbackScanBusy=false;
const handledCallSignalIds=new Set();
let viewedProfileUsername="";
let viewedFriendState="none";
let selectedUser="",selectedGroup=null,users=[],groups=[],chatFriends=[];
let pc=null,localStream=null,currentCallType="video",incomingFrom="";
let videoSender=null,audioSender=null,remoteMediaStream=null;
let callState="idle",activeCallId="",activeCallPeer="",outgoingCallTimer=null;

let pendingIceCandidates=[];


let rtcConfig={
  iceServers:(window.TAWASOL_CONFIG?.iceServers)||[
    {urls:"stun:stun.cloudflare.com:3478"},
    {urls:"stun:stun.l.google.com:19302"}
  ],
  iceTransportPolicy:"all"
};

let turnLoadedAt=0;
let turnCredentialTtlMs=0;
let turnReady=false;

async function ensureTurnIceServers(force=false){
  if(!token)return false;

  const now=Date.now();
  const refreshAt=Math.max(5*60*1000,Math.floor(turnCredentialTtlMs*.60));

  if(
    !force &&
    turnReady &&
    turnLoadedAt &&
    (now-turnLoadedAt)<refreshAt
  ){
    return true;
  }

  try{
    const d=await api(`/api/turn-credentials?token=${encodeURIComponent(token)}`);

    if(!Array.isArray(d.iceServers) || !d.iceServers.length){
      throw new Error("لم يتم استلام خوادم TURN");
    }

    rtcConfig={
      iceServers:d.iceServers,
      iceTransportPolicy:"all"
    };

    turnLoadedAt=Date.now();
    turnCredentialTtlMs=Number(d.ttl||86400)*1000;
    turnReady=true;

    console.log("TURN enabled",d.provider||"cloudflare",rtcConfig.iceServers);

    return true;
  }catch(err){
    console.warn("TURN unavailable; using STUN fallback",err?.message||err);

    rtcConfig={
      iceServers:(window.TAWASOL_CONFIG?.iceServers)||[
        {urls:"stun:stun.cloudflare.com:3478"},
        {urls:"stun:stun.l.google.com:19302"}
      ],
      iceTransportPolicy:"all"
    };

    turnReady=false;
    return false;
  }
}

async function api(path,opts={}){
  const r=await fetch(path,{
    ...opts,
    headers:{"content-type":"application/json",...(opts.headers||{})}
  });
  const d=await r.json().catch(()=>({}));
  if(!r.ok)throw new Error(d.error||"حدث خطأ");
  return d;
}


const SESSION_STORAGE_KEY="tawasol_alatta_session_v1";
let appSessionStarted=false;

function saveSession(sessionToken){
  try{
    localStorage.setItem(SESSION_STORAGE_KEY,JSON.stringify({
      token:sessionToken,
      savedAt:Date.now()
    }));
  }catch(err){
    console.warn("تعذر حفظ الجلسة",err);
  }
}

function clearSavedSession(){
  try{localStorage.removeItem(SESSION_STORAGE_KEY)}catch{}
}

function readSavedSession(){
  try{
    const raw=localStorage.getItem(SESSION_STORAGE_KEY);
    if(!raw)return null;
    const data=JSON.parse(raw);
    if(!data?.token)return null;
    return data;
  }catch{
    return null;
  }
}

async function startAuthenticatedApp(sessionToken,user=null){
  if(appSessionStarted)return;

  token=String(sessionToken||"");
  if(!token)throw new Error("الجلسة غير صالحة");

  if(!user){
    const d=await api(`/api/me?token=${encodeURIComponent(token)}`);
    user=d.user;
  }

  me=user;
  viewedProfileUsername=me.username;
  appSessionStarted=true;
  saveSession(token);

  $("authView").classList.add("hidden");
  $("appView").classList.remove("hidden");

  renderProfile();
  $("homeAvatar").src=me.avatar||avatarFallback(me.fullName||me.displayName||me.username);
  renderProfilePage();

  ensureTurnIceServers().catch(()=>{});

  connect();
  startCallSignalPolling();
  loadGroups();
  resetMessagesInbox();
  loadChatContacts();
  loadPosts();
  loadStories();
  loadDesktopSuggestions();
  loadFriendRequests().catch(()=>{});
  startSocialSync();
  askNotifications();
}

async function restoreSavedLogin(){
  const saved=readSavedSession();
  if(!saved?.token)return false;

  try{
    $("authMsg").textContent="جاري فتح حسابك...";
    await startAuthenticatedApp(saved.token);
    $("authMsg").textContent="";
    return true;
  }catch(err){
    console.warn("Saved login is no longer valid",err);
    clearSavedSession();
    token="";
    me=null;
    appSessionStarted=false;
    $("appView").classList.add("hidden");
    $("authView").classList.remove("hidden");
    $("authMsg").textContent="";
    return false;
  }
}

function esc(s){
  return String(s??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");
}

function uiIcon(name,extraClass=""){
  if(window.AttaIcons?.icon)return window.AttaIcons.icon(name,extraClass);
  return "";
}

function setRoundCallButton(id,iconName,label){
  const btn=$(id);
  if(!btn)return;
  btn.innerHTML=`<span class="round-icon">${uiIcon(iconName)}</span><small>${esc(label)}</small>`;
}

function setVoiceRecordButton(recording=false){
  const btn=$("recordVoiceBtn");
  if(!btn)return;
  btn.innerHTML=uiIcon(recording?"square":"mic");
  btn.classList.toggle("is-recording",recording);
  btn.setAttribute("aria-label",recording?"إيقاف التسجيل":"رسالة صوتية");
  btn.title=recording?"إيقاف التسجيل":"رسالة صوتية";
}


function timeAgoAr(ts){
  const diff=Math.max(1,Date.now()-Number(ts||Date.now()));
  const m=Math.floor(diff/60000);
  if(m<1)return "الآن";
  if(m<60)return `منذ ${m} ${m===1?"دقيقة":m===2?"دقيقتين":m<11?"دقائق":"دقيقة"}`;
  const h=Math.floor(m/60);
  if(h<24)return `منذ ${h} ${h===1?"ساعة":h===2?"ساعتين":h<11?"ساعات":"ساعة"}`;
  const d=Math.floor(h/24);
  if(d<30)return `منذ ${d} ${d===1?"يوم":d===2?"يومين":d<11?"أيام":"يوم"}`;
  const mo=Math.floor(d/30);
  if(mo<12)return `منذ ${mo} ${mo===1?"شهر":mo===2?"شهرين":mo<11?"أشهر":"شهر"}`;
  const y=Math.floor(mo/12);
  return `منذ ${y} ${y===1?"سنة":y===2?"سنتين":y<11?"سنوات":"سنة"}`;
}

function totalReactionsCount(reactionCounts){
  return Object.values(reactionCounts||{}).reduce((a,b)=>a+Number(b||0),0);
}

function iconHeart(){return uiIcon("heart")}
function iconComment(){return uiIcon("message-circle")}
function iconRepeat(){return uiIcon("repeat")}
function iconBookmark(){return uiIcon("bookmark")}
function iconShare(){return uiIcon("share")}
function iconGlobe(){return uiIcon("globe")}
function iconDots(){return uiIcon("ellipsis")}

async function askNotifications(){
  if("Notification" in window && Notification.permission==="default"){
    try{await Notification.requestPermission()}catch{}
  }
}

function notify(title,body){
  if("Notification" in window && Notification.permission==="granted"){
    new Notification(title,{body});
  }
}

function wsUrl(){
  const p=location.protocol==="https:"?"wss":"ws";
  return `${p}://${location.host}/ws?token=${encodeURIComponent(token)}`;
}



const CALL_SIGNAL_TYPES=new Set([
  "call-request",
  "call-accept",
  "call-reject",
  "offer",
  "answer",
  "ice",
  "hangup"
]);

function setCallState(state,peer=activeCallPeer,callId=activeCallId){
  callState=state;
  if(peer!==undefined)activeCallPeer=peer||"";
  if(callId!==undefined)activeCallId=callId||"";

  const busy=state!=="idle";
  [
    "audioCallBtn",
    "videoCallBtn",
    "composerAudioCallBtn",
    "composerVideoCallBtn"
  ].forEach(id=>{
    const el=$(id);
    if(el)el.disabled=busy;
  });

  document.body.classList.toggle("call-busy",busy);
}

function clearOutgoingCallTimer(){
  if(outgoingCallTimer){
    clearTimeout(outgoingCallTimer);
    outgoingCallTimer=null;
  }
}

function resetCallState(){
  clearOutgoingCallTimer();
  callState="idle";
  activeCallId="";
  activeCallPeer="";
  incomingFrom="";
  setCallState("idle","","");
}

function callIdMatches(msg){
  if(!activeCallId || !msg?.callId)return true;
  return msg.callId===activeCallId;
}

function hasUsableLocalStream(type=currentCallType){
  if(!localStream)return false;
  const audio=localStream.getAudioTracks().some(t=>t.readyState==="live");
  const video=localStream.getVideoTracks().some(t=>t.readyState==="live");
  return audio && (type!=="video" || video);
}

async function waitForIceGatheringComplete(peer,timeoutMs=9000){
  if(!peer || peer.iceGatheringState==="complete")return;

  await new Promise(resolve=>{
    let finished=false;

    const finish=()=>{
      if(finished)return;
      finished=true;
      clearTimeout(timer);
      peer.removeEventListener("icegatheringstatechange",onChange);
      resolve();
    };

    const onChange=()=>{
      if(peer.iceGatheringState==="complete")finish();
    };

    const timer=setTimeout(finish,timeoutMs);
    peer.addEventListener("icegatheringstatechange",onChange);
  });
}


async function sendCallSignal(signal){
  if(!signal?.to)return {ok:false};

  const normalized={
    ...signal,
    callId:signal.callId||activeCallId||crypto.randomUUID(),
    signalId:signal.signalId||crypto.randomUUID(),
    createdAt:signal.createdAt||Date.now()
  };

  let wsSent=false;

  if(ws?.readyState===WebSocket.OPEN){
    try{
      ws.send(JSON.stringify(normalized));
      wsSent=true;
    }catch(err){
      console.warn("call websocket send failed",err);
    }
  }

  let stored=null;

  try{
    stored=await api("/api/message-send",{
      method:"POST",
      body:JSON.stringify({
        token,
        to:normalized.to,
        text:"",
        media:JSON.stringify(normalized),
        mediaType:"call-signal"
      })
    });
  }catch(err){
    if(!wsSent)throw err;
    console.warn("stored call signal failed",err);
  }

  return {
    ok:wsSent||!!stored?.ok,
    online:wsSent||!!stored?.delivered,
    transport:wsSent?"websocket+stored":"stored"
  };
}
async function handleCallSignal(msg){
  if(!msg || !CALL_SIGNAL_TYPES.has(msg.type))return false;

  if(msg.signalId){
    if(handledCallSignalIds.has(msg.signalId))return true;
    handledCallSignalIds.add(msg.signalId);

    if(handledCallSignalIds.size>700){
      const keep=[...handledCallSignalIds].slice(-350);
      handledCallSignalIds.clear();
      keep.forEach(id=>handledCallSignalIds.add(id));
    }
  }

  const msgCallId=msg.callId||msg.signalId||`${msg.from||"peer"}:${msg.createdAt||Date.now()}`;

  if(msg.type==="call-request"){
    if(callState!=="idle"){
      if(msgCallId===activeCallId)return true;

      await sendCallSignal({
        type:"call-reject",
        to:msg.from,
        callId:msgCallId,
        callType:msg.callType||"video",
        reason:"busy"
      }).catch(()=>{});

      return true;
    }

    incomingFrom=msg.from;
    currentCallType=msg.callType||"video";
    setCallState("incoming",msg.from,msgCallId);

    $("incomingText").textContent=`${incomingFrom} يتصل بك`;
    setCallPeerIdentity(incomingFrom);
    $("incomingModal").classList.remove("hidden");
    playIncomingRing();
    notify("مكالمة واردة",`${incomingFrom} يتصل بك`);
    return true;
  }

  if(!callIdMatches({...msg,callId:msgCallId})){
    return true;
  }

  if(msg.type==="call-accept"){
    if(callState!=="outgoing" && callState!=="preparing")return true;

    clearOutgoingCallTimer();
    selectedUser=msg.from;
    activeCallPeer=msg.from;
    setCallState("connecting",msg.from,msgCallId);

    stopCallSounds();
    showCallOverlay(msg.from,"تم الرد — جاري توصيل الوسائط...");
    await startOffer();
    return true;
  }

  if(msg.type==="call-reject"){
    stopCallSounds();
    await teardownPeer(false);
    hideCallOverlay(false);
    $("incomingModal").classList.add("hidden");
    resetCallState();

    alert(
      msg.reason==="busy"
        ?"المستخدم مشغول في مكالمة أخرى"
        :"تم رفض المكالمة"
    );

    return true;
  }

  if(msg.type==="offer"){
    if(callState==="idle"){
      currentCallType=msg.callType||"video";
      selectedUser=msg.from;
      setCallState("connecting",msg.from,msgCallId);
    }

    selectedUser=msg.from;
    activeCallPeer=msg.from;
    currentCallType=msg.callType||currentCallType||"video";

    stopCallSounds();
    showCallOverlay(
      msg.from,
      msg.renegotiate?"إعادة توصيل الوسائط...":"جاري توصيل المكالمة..."
    );

    await ensurePeer();

    if(pc.signalingState!=="stable" && pc.signalingState!=="have-local-offer"){
      console.warn("offer received in state",pc.signalingState);
    }

    await pc.setRemoteDescription(msg.sdp);
    await flushPendingIce();

    const answer=await pc.createAnswer();
    await pc.setLocalDescription(answer);
    await waitForIceGatheringComplete(pc);

    await sendCallSignal({
      type:"answer",
      to:msg.from,
      callId:msgCallId,
      sdp:pc.localDescription,
      callType:currentCallType,
      iceComplete:true
    });

    setCallState("connecting",msg.from,msgCallId);
    return true;
  }

  if(msg.type==="answer"){
    stopCallSounds();

    if(pc && pc.signalingState==="have-local-offer"){
      await pc.setRemoteDescription(msg.sdp);
      await flushPendingIce();
    }

    return true;
  }

  if(msg.type==="ice"){
    if(msg.candidate){
      if(pc && pc.remoteDescription){
        try{
          await pc.addIceCandidate(msg.candidate);
        }catch(err){
          console.warn("ICE candidate failed",err);
        }
      }else{
        pendingIceCandidates.push(msg.candidate);
      }
    }

    return true;
  }

  if(msg.type==="hangup"){
    stopCallSounds();
    await teardownPeer(false);
    hideCallOverlay(false);
    $("incomingModal").classList.add("hidden");
    resetCallState();
    return true;
  }

  return false;
}
async function pollStoredCallSignals(){
  if(!token || !me || callFallbackScanBusy)return;

  callFallbackScanBusy=true;

  try{
    const names=new Set();

    if(selectedUser)names.add(selectedUser);
    if(incomingFrom)names.add(incomingFrom);

    for(const f of chatFriends||[]){
      if(f?.username)names.add(f.username);
    }

    for(const u of users||[]){
      if(u?.username && u.username!==me.username)names.add(u.username);
    }

    const targets=[...names].filter(Boolean).slice(0,35);

    for(const username of targets){
      try{
        const d=await api(`/api/conversation?token=${encodeURIComponent(token)}&with=${encodeURIComponent(username)}`);

        for(const m of d.messages||[]){
          if(m.mediaType!=="call-signal" || m.to!==me.username || !m.media)continue;

          let signal;
          try{
            signal=JSON.parse(m.media);
          }catch{
            continue;
          }

          if(!signal || !CALL_SIGNAL_TYPES.has(signal.type))continue;

          signal.from=signal.from||m.from;
          signal.to=signal.to||m.to;
          signal.signalId=signal.signalId||`stored:${m.id}`;

          const age=Date.now()-Number(signal.createdAt||m.ts||0);
          if(signal.type==="call-request" && age>45000)continue;
          if(signal.type!=="call-request" && age>120000)continue;

          await handleCallSignal(signal);
        }
      }catch(err){
        console.warn("stored call signal scan failed",username,err);
      }
    }
  }finally{
    callFallbackScanBusy=false;
  }
}

async function pollCallSignals(){
  if(!token || callSignalPollBusy)return;

  callSignalPollBusy=true;

  try{
    // Always use the compatible stored-message fallback. This works with the
    // currently deployed backend and does not require /api/call-signals.
    await pollStoredCallSignals();
  }finally{
    callSignalPollBusy=false;
  }
}
function startCallSignalPolling(){
  clearInterval(callSignalPollTimer);

  // First check immediately, then keep checking quickly enough for ringing.
  pollCallSignals();
  callSignalPollTimer=setInterval(pollCallSignals,800);
}

function connect(){
  clearTimeout(reconnectTimer);
  ws=new WebSocket(wsUrl());
  $("connectionState").textContent="جاري الاتصال...";

  ws.onopen=()=>{$("connectionState").textContent="متصل"};
  ws.onclose=()=>{
    $("connectionState").textContent="إعادة الاتصال...";
    reconnectTimer=setTimeout(connect,1500);
  };

  ws.onmessage=async e=>{
    const msg=JSON.parse(e.data);

    if(CALL_SIGNAL_TYPES.has(msg.type)){
      await handleCallSignal(msg);
      return;
    }

    if(msg.type==="joined"){
      me=msg.user;
      renderProfile();
      await loadChatContacts();
      await loadFriendRequests().catch(()=>{});
      return;
    }

    if(msg.type==="users"){
      users=msg.users||[];
      renderUsers();
      return;
    }

    if(msg.type==="chat"){
      if(msg.mediaType==="call-signal" && msg.media){
        try{
          const signal=JSON.parse(msg.media);
          signal.from=signal.from||msg.from;
          signal.to=signal.to||msg.to;
          signal.signalId=signal.signalId||`live:${msg.id}`;
          await handleCallSignal(signal);
        }catch(err){
          console.warn("hidden call signal parse failed",err);
        }
        return;
      }

      const counterpart=msg.from===me.username?msg.to:msg.from;
      const isOpen=selectedUser===counterpart && !selectedGroup;

      if(msg.from!==me.username){
        notify("رسالة جديدة",`${msg.from}: ${msg.text||"مرفق"}`);
      }

      if(isOpen){
        const exists=msg.id && document.querySelector(`[data-message-id="${msg.id}"]`);
        if(!exists)addRichMessage(msg);

        if(msg.from===counterpart && msg.to===me.username){
          send({type:"read",with:msg.from});
        }
      }

      return;
    }

    if(msg.type==="delivered"){
      if(msg.messageId){
        const el=document.querySelector(`[data-message-id="${msg.messageId}"] .message-read-state`);
        if(el)el.textContent="✓✓ تم التسليم";
      }
      return;
    }

    if(msg.type==="friend-request"){
      notify("طلب صداقة جديد",`${msg.from} أرسل لك طلب صداقة`);
      await loadFriendRequests().catch(()=>{});
      return;
    }

    if(msg.type==="friend-accepted"){
      notify("تم قبول طلب الصداقة",`${msg.from} أصبح صديقًا لك`);
      await loadChatContacts();
      await loadFriendRequests().catch(()=>{});
      if(me?.username)await loadFriends(me.username).catch(()=>{});
      if(viewedProfileUsername===me?.username){
        renderProfilePage(me.username).catch(()=>{});
      }
      return;
    }

    if(msg.type==="friends-changed"){
      await loadChatContacts();
      if(me?.username)await loadFriends(me.username).catch(()=>{});
      await loadFriendRequests().catch(()=>{});
      return;
    }
    if(msg.type==="typing"){if(msg.from===selectedUser)$("typingIndicator").classList.toggle("hidden",!msg.active);return;}
    if(msg.type==="read"){document.querySelectorAll(".message-read-state").forEach(el=>el.textContent="✓✓ تمت القراءة");return;}

    if(msg.type==="group-chat"){
      const g=groups.find(x=>x.id===msg.groupId);
      if(g && selectedGroup?.id===g.id){
        addMessage(msg.from,msg.text,msg.from===me.username);
      }
      if(msg.from!==me.username)notify(g?.name||"مجموعة","رسالة جديدة");
      return;
    }

    if(msg.type==="call-request"){
      incomingFrom=msg.from;
      currentCallType=msg.callType||"video";
      $("incomingText").textContent=`${incomingFrom} يتصل بك`;
      setCallPeerIdentity(incomingFrom);
      $("incomingModal").classList.remove("hidden");
      playIncomingRing();
      notify("مكالمة واردة",`${incomingFrom} يتصل بك`);
      return;
    }

    if(msg.type==="call-accept"){
      selectedUser=msg.from;
      stopCallSounds();
      showCallOverlay(msg.from,"تم الاتصال");
      await startOffer();
      return;
    }

    if(msg.type==="call-reject"){
      stopCallSounds();
      await hangup(false);
      hideCallOverlay(false);
      alert("تم رفض المكالمة");
      return;
    }

    if(msg.type==="offer"){
      selectedUser=msg.from;
      currentCallType=msg.callType||currentCallType||"video";
      stopCallSounds();
      showCallOverlay(msg.from,msg.renegotiate?"إعادة توصيل الفيديو...":"مكالمة جارية");
      await ensurePeer();

      // في إعادة التفاوض تأكد أن مسار الفيديو المحلي ما زال مربوطًا.
      if(currentCallType==="video" && videoSender && !videoSender.track && localStream?.getVideoTracks()[0]){
        await videoSender.replaceTrack(localStream.getVideoTracks()[0]);
      }
      await pc.setRemoteDescription(msg.sdp);
      await flushPendingIce();
      const answer=await pc.createAnswer();
      await pc.setLocalDescription(answer);

      send({
        type:"answer",
        to:msg.from,
        sdp:pc.localDescription,
        callType:currentCallType
      });
      return;
    }

    if(msg.type==="answer"){
      stopCallSounds();
      if(pc){
        await pc.setRemoteDescription(msg.sdp);
        await flushPendingIce();
      }
      return;
    }

    if(msg.type==="ice"){
      if(msg.candidate){
        if(pc && pc.remoteDescription){
          try{await pc.addIceCandidate(msg.candidate)}catch(err){
            console.warn("ICE candidate failed",err);
          }
        }else{
          pendingIceCandidates.push(msg.candidate);
        }
      }
      return;
    }

    if(msg.type==="hangup"){
      stopCallSounds();
      await hangup(false);
      hideCallOverlay(false);
      return;
    }

    if(msg.type==="user-offline"){
      // This event is now used for live calls only.
      if(document.body.classList.contains("call-open") || pc){
        alert("المستخدم غير متصل الآن ولا يمكن بدء المكالمة.");
      }
      return;
    }
  };
}

function send(obj){
  if(ws?.readyState===WebSocket.OPEN)ws.send(JSON.stringify(obj));
}

function avatarFallback(name){
  const ch=(name||"A").slice(0,1).toUpperCase();
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="#fff"/><text x="50" y="64" font-size="48" text-anchor="middle" fill="#245b84">${ch}</text></svg>`)}`;
}

function renderProfile(){
  if(!me)return;
  $("meDisplay").textContent=me.displayName||me.username;
  $("meUsername").textContent="@"+me.username;
  $("avatarImg").src=me.avatar||avatarFallback(me.displayName||me.username);
  if($("headerAvatar"))$("headerAvatar").src=me.avatar||avatarFallback(me.fullName||me.displayName||me.username);
  if($("headerUserName"))$("headerUserName").textContent=me.fullName||me.displayName||me.username;
  if($("composerUserName"))$("composerUserName").textContent=me.fullName||me.displayName||me.username;
}

async function loadChatContacts(){
  if(!token || !me)return;

  try{
    const d=await api(`/api/friends?token=${encodeURIComponent(token)}&username=${encodeURIComponent(me.username)}`);
    chatFriends=d.friends||[];
    renderUsers();
  }catch(e){
    console.error("loadChatContacts",e);
  }
}

function renderUsers(){
  if(!me || !$("usersList"))return;

  const onlineMap=new Map(
    users
      .filter(u=>u.username!==me.username)
      .map(u=>[u.username,u])
  );

  // الرسائل الخاصة تعرض الأصدقاء فقط.
  const friendMap=new Map();

  for(const friend of chatFriends){
    if(!friend?.username || friend.username===me.username)continue;

    const live=onlineMap.get(friend.username);

    friendMap.set(friend.username,{
      ...friend,
      ...(live||{}),
      isOnline:!!live
    });
  }

  const all=[...friendMap.values()].sort((a,b)=>{
    const ao=a.isOnline?1:0;
    const bo=b.isOnline?1:0;
    return bo-ao ||
      String(a.fullName||a.displayName||a.username)
        .localeCompare(String(b.fullName||b.displayName||b.username),"ar");
  });

  $("usersList").innerHTML=all.length
    ? all.map(u=>{
        const avatar=u.avatar||avatarFallback(u.fullName||u.displayName||u.username);

        return `
          <button class="user-item chat-contact-item" data-user="${esc(u.username)}">
            <img class="chat-contact-avatar" src="${avatar}" alt="">
            <span class="chat-contact-copy">
              <strong>${esc(u.fullName||u.displayName||u.username)}</strong>
              <small>${u.isOnline?"متصل الآن":"غير متصل"}</small>
            </span>
            <span class="presence-dot ${u.isOnline?"online":"offline"}"></span>
            <span class="contact-open-arrow">${uiIcon("chevron-left")}</span>
          </button>`;
      }).join("")
    : `
      <div class="chat-empty-contacts premium-empty-inbox">
        ${uiIcon("message-circle")}
        <strong>لا توجد محادثات مفتوحة</strong>
        <span>أضف أصدقاء أولًا، ثم اضغط على الصديق لبدء المحادثة.</span>
      </div>`;

  document.querySelectorAll("[data-user]").forEach(btn=>{
    btn.onclick=()=>{
      const username=btn.dataset.user;
      const user=friendMap.get(username);
      openDirectChat(username,user);
    };
  });
}

function resetMessagesInbox(){
  selectedUser="";
  selectedGroup=null;
  lastConversationSignature="";
  document.body.classList.remove("chat-fullscreen-open");
  $("messagesLayout")?.classList.remove("has-chat");
  $("messages").innerHTML="";
  $("selectedLabel").textContent="اختر صديقًا";
  $("typingIndicator")?.classList.add("hidden");
  clearPendingChatMedia?.();
}

function openDirectChat(username,userInfo=null){
  if(!username)return;

  selectedUser=username;
  selectedGroup=null;
  lastConversationSignature="";

  const user=userInfo ||
    chatFriends.find(u=>u.username===username) ||
    users.find(u=>u.username===username);

  $("selectedLabel").textContent=
    user?.fullName||user?.displayName||username;

  $("messages").innerHTML="";
  $("messagesLayout")?.classList.add("has-chat");
  document.body.classList.add("chat-fullscreen-open");

  loadConversation(username);

  setTimeout(()=>$("messageInput")?.focus(),120);
}
function addMessage(from,text,mine){addRichMessage({from,to:mine?selectedUser:me?.username,text,media:"",mediaType:"text",read:false,ts:Date.now()})}
function addRichMessage(msg){
  if(msg?.mediaType==="call-signal")return;
  const mine=msg.from===me?.username;
  const div=document.createElement("div");

  div.className="bubble"+(mine?" mine":"");
  if(msg.id)div.dataset.messageId=msg.id;

  let media="";
  if(msg.media){
    media=msg.mediaType==="image"
      ? `<img class="chat-media-image" src="${msg.media}">`
      : msg.mediaType==="audio"
        ? `<audio class="chat-audio" controls src="${msg.media}"></audio>`
        : `<a class="chat-file-link" href="${msg.media}" target="_blank">${uiIcon("paperclip")}<span>فتح المرفق</span></a>`;
  }

  const state=msg.read
    ?"✓✓ تمت القراءة"
    : msg.deliveredAt
      ?"✓✓ تم التسليم"
      :"✓ تم الإرسال";

  div.innerHTML=`
    <div class="meta">${esc(msg.from)}</div>
    ${msg.text?`<div>${esc(msg.text)}</div>`:""}
    ${media}
    ${mine?`<div class="message-read-state">${state}</div>`:""}
  `;

  $("messages").appendChild(div);
  $("messages").scrollTop=$("messages").scrollHeight;
}

function conversationSignature(messages){
  return (messages||[])
    .filter(m=>m.mediaType!=="call-signal")
    .map(m=>`${m.id||""}:${m.read?1:0}:${m.deliveredAt||0}`)
    .join("|");
}

function renderConversationMessages(messages,{preserveScroll=false}={}){
  const box=$("messages");
  if(!box)return;

  const wasNearBottom=box.scrollHeight-box.scrollTop-box.clientHeight<90;
  box.innerHTML="";
  (messages||[]).filter(m=>m.mediaType!=="call-signal").forEach(addRichMessage);

  if(!preserveScroll || wasNearBottom){
    box.scrollTop=box.scrollHeight;
  }
}

async function loadConversation(username,{silent=false}={}){
  if(!username)return;

  try{
    const d=await api(`/api/conversation?token=${encodeURIComponent(token)}&with=${encodeURIComponent(username)}`);
    const messages=d.messages||[];
    const sig=conversationSignature(messages);

    if(!silent || sig!==lastConversationSignature){
      renderConversationMessages(messages,{preserveScroll:silent});
      lastConversationSignature=sig;
    }

    if(ws?.readyState===WebSocket.OPEN){
      send({type:"read",with:username});
    }
  }catch(e){
    console.error("loadConversation",e);
  }
}


async function syncSocialState(){
  if(!token || !me)return;

  await Promise.allSettled([
    loadChatContacts(),
    loadFriendRequests(),
    selectedUser && !selectedGroup ? loadConversation(selectedUser,{silent:true}) : Promise.resolve(),
    loadNotifications()
  ]);
}

function startSocialSync(){
  clearInterval(syncTimer);
  syncTimer=setInterval(syncSocialState,3000);
}

async function loadGroups(){
  const d=await api(`/api/groups?token=${encodeURIComponent(token)}`);
  groups=d.groups||[];

  $("groupsList").innerHTML=groups.map(g=>
    `<button class="group-item" data-group="${g.id}">${esc(g.name)}</button>`
  ).join("");

  document.querySelectorAll("[data-group]").forEach(btn=>{
    btn.onclick=()=>{
      selectedGroup=groups.find(g=>g.id===btn.dataset.group)||null;
      selectedUser="";
      $("selectedLabel").textContent=selectedGroup?.name||"مجموعة";
      $("messagesLayout")?.classList.add("has-chat");
      document.body.classList.add("chat-fullscreen-open");
      $("messages").innerHTML="";
    };
  });
}

async function loadCalls(){
  const d=await api(`/api/calls?token=${encodeURIComponent(token)}`);
  $("callsList").innerHTML=(d.calls||[]).map(c=>`
    <div class="call-item">
      ${esc(c.with)} — ${c.kind==="audio"?"صوت":"فيديو"}<br>
      <small>${new Date(c.ts).toLocaleString("ar-SA")}</small>
    </div>`).join("") || "لا يوجد سجل";
}

async function getMedia(type,{force=false}={}){
  if(!force && hasUsableLocalStream(type)){
    const localVideo=$("localVideo");
    localVideo.srcObject=localStream;
    localVideo.muted=true;
    localVideo.playsInline=true;
    try{await localVideo.play()}catch{}
    return localStream;
  }

  localStream?.getTracks().forEach(t=>t.stop());
  localStream=null;

  const audioConstraints={
    echoCancellation:true,
    noiseSuppression:true,
    autoGainControl:true
  };

  const primaryConstraints=type==="audio"
    ? {audio:audioConstraints,video:false}
    : {
        audio:audioConstraints,
        video:{
          facingMode:{ideal:"user"},
          width:{ideal:960},
          height:{ideal:540},
          frameRate:{ideal:24,max:30}
        }
      };

  try{
    try{
      localStream=await navigator.mediaDevices.getUserMedia(primaryConstraints);
    }catch(firstErr){
      if(type!=="video")throw firstErr;

      console.warn("Primary video constraints failed; retrying simple video",firstErr);
      localStream=await navigator.mediaDevices.getUserMedia({
        audio:audioConstraints,
        video:true
      });
    }

    const audioTrack=localStream.getAudioTracks()[0]||null;
    const videoTrack=localStream.getVideoTracks()[0]||null;

    if(!audioTrack){
      throw new Error("لم يتم العثور على مايك فعال");
    }

    if(type==="video" && !videoTrack){
      throw new Error("لم يتم العثور على كاميرا فعالة");
    }

    audioTrack.enabled=true;
    if(videoTrack)videoTrack.enabled=true;

    const localVideo=$("localVideo");
    localVideo.srcObject=localStream;
    localVideo.muted=true;
    localVideo.playsInline=true;
    localVideo.autoplay=true;

    try{await localVideo.play()}catch{}

    return localStream;
  }catch(err){
    console.error("getUserMedia error",err);

    let msg="تعذر تشغيل الكاميرا أو المايك.";

    if(err?.name==="NotAllowedError"){
      msg="اسمح للموقع باستخدام الكاميرا والمايك ثم جرّب مرة أخرى.";
    }else if(err?.name==="NotFoundError"){
      msg="لم يتم العثور على كاميرا أو مايك على هذا الجهاز.";
    }else if(err?.name==="NotReadableError"){
      msg="الكاميرا أو المايك مستخدمان في تطبيق آخر.";
    }else if(err?.name==="OverconstrainedError"){
      msg="إعدادات الكاميرا غير مدعومة على هذا الجهاز.";
    }

    if($("callPeerState"))$("callPeerState").textContent=msg;
    alert(msg);
    throw err;
  }
}
async function flushPendingIce(){
  if(!pc || !pc.remoteDescription)return;

  const queue=[...pendingIceCandidates];
  pendingIceCandidates=[];

  for(const candidate of queue){
    try{
      await pc.addIceCandidate(candidate);
    }catch(err){
      console.warn("ICE add failed",err);
    }
  }
}

async function ensurePeer(){
  if(pc)return pc;

  const turnOk=await ensureTurnIceServers();
  const stream=await getMedia(currentCallType);

  pendingIceCandidates=[];
  videoSender=null;
  audioSender=null;
  remoteMediaStream=new MediaStream();

  pc=new RTCPeerConnection({
    ...rtcConfig,
    iceCandidatePoolSize:6,
    bundlePolicy:"max-bundle",
    rtcpMuxPolicy:"require"
  });

  for(const track of stream.getTracks()){
    const sender=pc.addTrack(track,stream);

    if(track.kind==="audio")audioSender=sender;
    if(track.kind==="video")videoSender=sender;
  }

  // We wait for complete ICE in offer/answer, so trickle ICE is only a bonus.
  pc.onicecandidate=e=>{
    if(!e.candidate || !selectedUser || !activeCallId)return;

    sendCallSignal({
      type:"ice",
      to:selectedUser,
      callId:activeCallId,
      candidate:e.candidate
    }).catch(err=>console.warn("ICE signal failed",err));
  };

  pc.ontrack=async e=>{
    if(!remoteMediaStream){
      remoteMediaStream=new MediaStream();
    }

    if(!remoteMediaStream.getTracks().some(t=>t.id===e.track.id)){
      remoteMediaStream.addTrack(e.track);
    }

    const remoteVideo=$("remoteVideo");
    remoteVideo.srcObject=remoteMediaStream;
    remoteVideo.playsInline=true;
    remoteVideo.autoplay=true;
    remoteVideo.muted=false;
    remoteVideo.volume=1;

    const revealVideo=()=>{
      if(e.track.kind==="video"){
        const empty=document.querySelector(".remote-empty-state");
        if(empty)empty.style.display="none";
        $("callPeerState").textContent="الفيديو متصل";
        $("callStatusText").textContent="مكالمة فيديو جارية";
      }
    };

    e.track.onunmute=async()=>{
      revealVideo();
      try{await remoteVideo.play()}catch{}
    };

    revealVideo();

    try{
      await remoteVideo.play();
      $("resumeRemoteMediaBtn")?.classList.add("hidden");
    }catch(err){
      console.warn("remote autoplay blocked",err);
      $("resumeRemoteMediaBtn")?.classList.remove("hidden");
    }

    if(e.track.kind==="audio" && currentCallType==="audio"){
      $("callPeerState").textContent="الصوت متصل";
    }
  };

  pc.oniceconnectionstatechange=()=>{
    const state=pc?.iceConnectionState||"";

    const labels={
      checking:turnOk?"فحص الاتصال عبر STUN / TURN...":"فحص الاتصال...",
      connected:"تم ربط الوسائط",
      completed:"تم توصيل الوسائط",
      disconnected:"الاتصال ضعيف — إعادة المحاولة...",
      failed:"فشل مسار الوسائط",
      closed:"انتهت المكالمة"
    };

    if(labels[state] && $("callPeerState")){
      $("callPeerState").textContent=labels[state];
    }
  };

  pc.onconnectionstatechange=async()=>{
    const state=pc?.connectionState||"";

    if(state==="connected"){
      setCallState("connected",activeCallPeer,activeCallId);

      if(currentCallType==="video"){
        const remoteHasVideo=remoteMediaStream?.getVideoTracks().some(t=>t.readyState==="live");

        if(!remoteHasVideo){
          $("callPeerState").textContent="الصوت متصل — جاري تشغيل الفيديو...";
        }
      }else{
        $("callPeerState").textContent="الصوت متصل";
      }
    }

    if(state==="failed"){
      $("callPeerState").textContent="إعادة توصيل المكالمة عبر TURN...";

      try{
        await ensureTurnIceServers(true);

        if(pc && selectedUser){
          pc.setConfiguration({
            ...rtcConfig,
            iceCandidatePoolSize:6,
            bundlePolicy:"max-bundle",
            rtcpMuxPolicy:"require"
          });

          const restartOffer=await pc.createOffer({
            iceRestart:true,
            offerToReceiveAudio:true,
            offerToReceiveVideo:currentCallType==="video"
          });

          await pc.setLocalDescription(restartOffer);
          await waitForIceGatheringComplete(pc);

          await sendCallSignal({
            type:"offer",
            to:selectedUser,
            callId:activeCallId,
            sdp:pc.localDescription,
            callType:currentCallType,
            renegotiate:true,
            turnRetry:true,
            iceComplete:true
          });
        }
      }catch(err){
        console.error("ICE restart failed",err);
        $("callPeerState").textContent="تعذر توصيل المكالمة";
      }
    }
  };

  console.log("Local call media",{
    turnReady,
    audio:!!audioSender?.track,
    video:!!videoSender?.track,
    audioState:audioSender?.track?.readyState,
    videoState:videoSender?.track?.readyState
  });

  return pc;
}
async function requestCall(type){
  if(!selectedUser)return alert("اختر مستخدمًا أولًا");

  if(callState!=="idle"){
    return alert("في مكالمة أو اتصال جاري بالفعل.");
  }

  currentCallType=type;
  const peer=selectedUser;
  const callId=crypto.randomUUID();

  setCallState("preparing",peer,callId);

  try{
    const turnOk=await ensureTurnIceServers();
    await getMedia(type);

    const empty=document.querySelector(".remote-empty-state");
    if(empty)empty.style.display="grid";

    showCallOverlay(
      peer,
      turnOk?"TURN جاهز — جاري الاتصال...":"جاري الاتصال..."
    );

    playOutgoingRing();
    setCallState("outgoing",peer,callId);

    const result=await sendCallSignal({
      type:"call-request",
      to:peer,
      callId,
      callType:type
    });

    $("callPeerState").textContent=result?.online
      ?"يرن الآن..."
      :"تم إرسال الاتصال — في انتظار الطرف الآخر...";

    clearOutgoingCallTimer();

    outgoingCallTimer=setTimeout(async()=>{
      if(callState==="outgoing" && activeCallId===callId){
        stopCallSounds();

        await sendCallSignal({
          type:"hangup",
          to:peer,
          callId,
          callType:type,
          reason:"timeout"
        }).catch(()=>{});

        await teardownPeer(false);
        hideCallOverlay(false);
        resetCallState();
      }
    },45000);
  }catch(err){
    stopCallSounds();
    await teardownPeer(false).catch(()=>{});
    hideCallOverlay(false);
    resetCallState();

    if(err?.message){
      console.warn("Call start failed",err);
    }
  }
}
async function startOffer(){
  await ensurePeer();

  const offer=await pc.createOffer({
    offerToReceiveAudio:true,
    offerToReceiveVideo:currentCallType==="video"
  });

  await pc.setLocalDescription(offer);
  await waitForIceGatheringComplete(pc);

  await sendCallSignal({
    type:"offer",
    to:selectedUser,
    callId:activeCallId,
    sdp:pc.localDescription,
    callType:currentCallType,
    iceComplete:true
  });
}
async function teardownPeer(stopLocal=true){
  try{pc?.close()}catch{}
  pc=null;
  videoSender=null;
  audioSender=null;
  pendingIceCandidates=[];
  remoteMediaStream=null;

  const empty=document.querySelector(".remote-empty-state");
  if(empty)empty.style.display="grid";

  if(stopLocal){
    localStream?.getTracks().forEach(t=>t.stop());
    localStream=null;
  }

  $("localVideo").srcObject=null;
  $("remoteVideo").srcObject=null;
}

async function hangup(notifyPeer=true){
  const peer=activeCallPeer||selectedUser;
  const callId=activeCallId;

  if(notifyPeer && peer && callId){
    await sendCallSignal({
      type:"hangup",
      to:peer,
      callId,
      callType:currentCallType
    }).catch(()=>{});
  }

  await teardownPeer(true);
  resetCallState();
}
async function shareScreen(){
  if(!pc)return alert("ابدأ مكالمة أولاً");

  const ds=await navigator.mediaDevices.getDisplayMedia({video:true});
  const track=ds.getVideoTracks()[0];
  const sender=pc.getSenders().find(s=>s.track?.kind==="video");

  if(sender)await sender.replaceTrack(track);
  $("localVideo").srcObject=ds;

  track.onended=async()=>{
    const cam=localStream?.getVideoTracks()[0];
    if(sender && cam)await sender.replaceTrack(cam);
    $("localVideo").srcObject=localStream;
  };
}


let postImageData="";

const reactionEmoji={like:"👍",love:"❤️",haha:"😂",wow:"😮",sad:"😢"};



let callAudioCtx=null;
let ringtoneTimer=null;
let activeRingType="";

function ensureCallAudio(){
  if(!callAudioCtx){
    const AC=window.AudioContext||window.webkitAudioContext;
    if(AC)callAudioCtx=new AC();
  }
  if(callAudioCtx && callAudioCtx.state==="suspended"){
    callAudioCtx.resume().catch(()=>{});
  }
}

function chimeTone(freq,duration=0.20,volume=0.035,delay=0){
  if(!callAudioCtx)return;

  const now=callAudioCtx.currentTime+delay;
  const master=callAudioCtx.createGain();
  const osc1=callAudioCtx.createOscillator();
  const osc2=callAudioCtx.createOscillator();

  osc1.type="sine";
  osc2.type="triangle";

  osc1.frequency.setValueAtTime(freq,now);
  osc2.frequency.setValueAtTime(freq*2,now);

  master.gain.setValueAtTime(0.0001,now);
  master.gain.exponentialRampToValueAtTime(volume,now+0.018);
  master.gain.exponentialRampToValueAtTime(volume*0.62,now+duration*0.42);
  master.gain.exponentialRampToValueAtTime(0.0001,now+duration);

  const overtone=callAudioCtx.createGain();
  overtone.gain.setValueAtTime(0.13,now);

  osc1.connect(master);
  osc2.connect(overtone);
  overtone.connect(master);
  master.connect(callAudioCtx.destination);

  osc1.start(now);
  osc2.start(now);
  osc1.stop(now+duration+0.02);
  osc2.stop(now+duration+0.02);
}

function playIncomingPattern(){
  if(activeRingType!=="incoming")return;
  ensureCallAudio();

  // نمط أصلي لامع وخفيف مستوحى من إحساس تطبيقات المراسلة الحديثة.
  chimeTone(783.99,0.18,0.040,0.00);   // G5
  chimeTone(987.77,0.18,0.040,0.19);   // B5
  chimeTone(1174.66,0.22,0.037,0.38);  // D6
  chimeTone(987.77,0.17,0.032,0.67);   // B5
  chimeTone(1318.51,0.28,0.035,0.86);  // E6
}

function playOutgoingPattern(){
  if(activeRingType!=="outgoing")return;
  ensureCallAudio();

  // انتظار أهدأ للطرف الذي يجري الاتصال.
  chimeTone(523.25,0.22,0.024,0.00);   // C5
  chimeTone(659.25,0.22,0.022,0.28);   // E5
}

function stopCallSounds(){
  activeRingType="";

  if(ringtoneTimer){
    clearInterval(ringtoneTimer);
    ringtoneTimer=null;
  }

  // لا نشغّل ملفات WAV بالتوازي مع WebAudio حتى لا تتداخل الأصوات.
  ["incomingRingAudio","outgoingRingAudio"].forEach(id=>{
    const audio=$(id);
    if(!audio)return;
    try{
      audio.pause();
      audio.currentTime=0;
    }catch{}
  });

  if(navigator.vibrate){
    try{navigator.vibrate(0)}catch{}
  }
}

async function playIncomingRing(){
  stopCallSounds();
  ensureCallAudio();

  activeRingType="incoming";
  playIncomingPattern();
  ringtoneTimer=setInterval(playIncomingPattern,2050);

  if(!callAudioCtx){
    const audio=$("incomingRingAudio");
    if(audio){
      audio.volume=0.35;
      try{await audio.play()}catch{}
    }
  }

  if(navigator.vibrate){
    try{navigator.vibrate([220,140,220,1000])}catch{}
  }
}

async function playOutgoingRing(){
  stopCallSounds();
  ensureCallAudio();

  activeRingType="outgoing";
  playOutgoingPattern();
  ringtoneTimer=setInterval(playOutgoingPattern,2450);

  if(!callAudioCtx){
    const audio=$("outgoingRingAudio");
    if(audio){
      audio.volume=0.25;
      try{await audio.play()}catch{}
    }
  }
}

["pointerdown","touchstart","keydown"].forEach(evt=>{
  document.addEventListener(evt,ensureCallAudio,{once:true,capture:true});
});

let storyMediaData="", pendingChatMedia=null, typingTimer=null, mediaRecorder=null, voiceChunks=[], profileCoverData="";

let callOverlayOpen=false;


async function setCallPeerIdentity(username){
  const name=String(username||"").trim();
  if(!name)return;

  let peer=users.find(u=>u.username===name)||null;

  if(!peer && token){
    try{
      const d=await api(`/api/profile-public?token=${encodeURIComponent(token)}&username=${encodeURIComponent(name)}`);
      peer=d.user||null;
    }catch{}
  }

  const display=peer?.fullName||peer?.displayName||peer?.username||name;
  const avatar=peer?.avatar||avatarFallback(display);

  if($("callPeerName"))$("callPeerName").textContent=display;
  if($("remoteEmptyName"))$("remoteEmptyName").textContent=display;

  if($("callPeerAvatar")){
    $("callPeerAvatar").src=avatar;
    $("callPeerAvatar").alt=display;
  }

  if($("remoteEmptyAvatar")){
    $("remoteEmptyAvatar").src=avatar;
    $("remoteEmptyAvatar").alt=display;
  }
}

function showCallOverlay(peerName,stateText="مكالمة جارية"){
  callOverlayOpen=true;
  $("callOverlay").classList.remove("hidden");
  $("callStatusBar").classList.remove("hidden");

  const peer=peerName||selectedUser||incomingFrom||"";
  $("callPeerName").textContent=peer||"مكالمة";
  $("remoteEmptyName").textContent=peer||"الطرف الآخر";
  $("callPeerState").textContent=stateText;
  $("callStatusText").textContent=stateText;

  setCallPeerIdentity(peer);
}

function hideCallOverlay(keepBar=false){
  callOverlayOpen=false;
  $("callOverlay").classList.add("hidden");
  if(!keepBar)$("callStatusBar").classList.add("hidden");
}

$("minimizeCallBtn").onclick=()=>hideCallOverlay(true);
$("openCallViewBtn").onclick=()=>showCallOverlay(selectedUser||incomingFrom||"مكالمة","مكالمة جارية");



function showPage(pageId){
  document.querySelectorAll(".page").forEach(p=>p.classList.remove("active-page"));
  $(pageId).classList.add("active-page");
  document.querySelectorAll(".nav-btn").forEach(b=>b.classList.toggle("active",b.dataset.page===pageId));
  document.querySelectorAll(".rail-btn").forEach(b=>b.classList.toggle("active",b.dataset.page===pageId));

  if(pageId==="homePage")loadPosts();
  if(pageId==="notificationsPage")loadNotifications();
  if(pageId==="profilePage"){
    if(!viewedProfileUsername)viewedProfileUsername=me?.username||"";
    renderProfilePage(viewedProfileUsername);
  }
  if(pageId==="searchPage")loadExplore();
  if(pageId==="savedPage")loadSavedPostsPage();
  if(pageId==="messagesPage"){
    resetMessagesInbox();
    loadChatContacts();
  }
}


if($("headerSearchBtn")){
  $("headerSearchBtn").onclick=()=>{
    showPage("searchPage");
    setTimeout(()=>$("userSearchInput")?.focus(),50);
  };
}

document.querySelectorAll(".rail-btn").forEach(btn=>{
  btn.onclick=()=>{
    document.querySelectorAll(".rail-btn").forEach(x=>x.classList.remove("active"));
    btn.classList.add("active");
    if(btn.dataset.page==="profilePage")viewedProfileUsername=me?.username||"";
    showPage(btn.dataset.page);
  };
});

if($("railStoryBtn")){
  $("railStoryBtn").onclick=()=>$("addStoryBtn")?.click();
}



if($("brandProfileBtn")){
  $("brandProfileBtn").onclick=()=>{
    viewedProfileUsername=me?.username||"";
    showPage("profilePage");
  };
}

document.querySelectorAll("[data-top-page]").forEach(btn=>{
  btn.onclick=()=>showPage(btn.dataset.topPage);
});

if($("headerFriendsBtn")){
  $("headerFriendsBtn").onclick=()=>{
    viewedProfileUsername=me?.username||"";
    showPage("profilePage");
    setTimeout(()=>$("friendRequestsSection")?.scrollIntoView({behavior:"smooth",block:"start"}),120);
  };
}

if($("sideAddStoryBtn"))$("sideAddStoryBtn").onclick=()=>$("addStoryBtn")?.click();
if($("sideShowStoriesBtn"))$("sideShowStoriesBtn").onclick=()=>showPage("homePage");

if($("railSavedBtn"))$("railSavedBtn").onclick=()=>showPage("savedPage");
if($("railMoreBtn"))$("railMoreBtn").onclick=()=>alert("قائمة المزيد سيتم توسيعها تدريجيًا.");

if($("postFeelingBtn")){
  $("postFeelingBtn").onclick=()=>{
    const feeling=prompt("اختر شعورك: سعيد، متحمس، ممتن، فخور، هادئ","متحمس");
    if(feeling){
      const current=$("postText").value.trim();
      $("postText").value=(current?current+" ":"")+`— أشعر أنني ${feeling}`;
      $("postText").focus();
    }
  };
}

if($("postPollBtn")){
  $("postPollBtn").onclick=()=>{
    const q=prompt("اكتب سؤال الاستطلاع:");
    if(!q)return;
    const a=prompt("الخيار الأول:","نعم")||"نعم";
    const b=prompt("الخيار الثاني:","لا")||"لا";
    const current=$("postText").value.trim();
    $("postText").value=(current?current+"\n\n":"")+`📊 ${q}\n1) ${a}\n2) ${b}`;
  };
}

if($("postVideoBtn")){
  $("postVideoBtn").onclick=()=>alert("شكل زر الفيديو جاهز؛ رفع الفيديو الكبير يحتاج تخزين ملفات منفصل حتى لا نثقل التطبيق.");
}

document.querySelectorAll(".nav-btn").forEach(btn=>{
  btn.onclick=()=>{
    if(btn.dataset.page==="profilePage")viewedProfileUsername=me?.username||"";
    showPage(btn.dataset.page);
  };
});

if($("openProfileBtn")){
  $("openProfileBtn").onclick=()=>{
    viewedProfileUsername=me?.username||"";
    showPage("profilePage");
  };
}

async function renderProfilePage(username=me?.username){
  if(!me || !username)return;
  viewedProfileUsername=username;

  try{
    const d=await api(`/api/profile-public?token=${encodeURIComponent(token)}&username=${encodeURIComponent(username)}`);
    const p=d.user;
    const self=username===me.username;
    viewedFriendState=d.friendState||"none";

    $("profileAvatarLarge").src=p.avatar||avatarFallback(p.fullName||p.displayName||p.username);
    $("profileName").textContent=p.fullName||p.displayName||p.username;
    $("profileUsername").textContent="@"+p.username;
    $("profileBio").textContent=p.bio||"لا توجد نبذة بعد";
    $("profileCover").style.backgroundImage=p.cover?`url("${p.cover}")`:"";
    $("friendsCount").textContent=d.friendCount||0;

    $("friendActionBtn").classList.toggle("hidden",self);
    $("messageProfileBtn").classList.toggle("hidden",self);
    $("myProfileBtn").classList.toggle("hidden",self);
    $("profileEditArea").classList.toggle("hidden",!self);
    $("privateInfoCard").classList.toggle("hidden",!self);
    $("friendRequestsSection").classList.toggle("hidden",!self);

    if(self){
      const mine=await api(`/api/me?token=${encodeURIComponent(token)}`);
      me={...me,...mine.user};
      $("bioInput").value=me.bio||"";
      $("privateAccountInput").checked=!!me.accountPrivate;
      const rows=[];
      if(me.phone)rows.push(`<div><span>رقم الهاتف</span><strong>${esc(me.phone)}</strong></div>`);
      if(me.email)rows.push(`<div><span>البريد الإلكتروني</span><strong>${esc(me.email)}</strong></div>`);
      if(me.age)rows.push(`<div><span>العمر</span><strong>${esc(me.age)}</strong></div>`);
      $("profilePrivateMeta").innerHTML=rows.join("")||"<div>لا توجد بيانات خاصة.</div>";
      await loadFriendRequests();
    }else{
      updateFriendButton(viewedFriendState);
    }

    $("profileMeta").innerHTML=p.country?`<div><span>الدولة</span><strong>${esc(p.country)}</strong></div>`:"";
    await loadFriends(username);
  }catch(e){
    alert(e.message||"تعذر تحميل الملف الشخصي");
  }
}

function updateFriendButton(state){
  const b=$("friendActionBtn");
  if(state==="friends"){b.textContent="✓ صديق";b.className="dark-outline";}
  else if(state==="outgoing"){b.textContent="تم إرسال الطلب";b.className="dark-outline";}
  else if(state==="incoming"){b.textContent="قبول طلب الصداقة";b.className="gold-action";}
  else{b.textContent="إضافة صديق";b.className="gold-action";}
}

async function runFriendAction(username,state){
  try{
    let d;
    if(state==="none"){
      d=await api("/api/friend-request",{method:"POST",body:JSON.stringify({token,username})});
    }else{
      const action=state==="incoming"?"accept":state==="outgoing"?"cancel":"remove";
      if((action==="cancel"||action==="remove")&&!confirm(action==="remove"?"إزالة الصديق؟":"إلغاء طلب الصداقة؟"))return;
      d=await api("/api/friend-action",{method:"POST",body:JSON.stringify({token,username,action})});
    }
    viewedFriendState=d.state;
    updateFriendButton(viewedFriendState);
    await loadFriends(viewedProfileUsername);
    await loadChatContacts();
  }catch(e){alert(e.message)}
}

async function loadFriendRequests(){
  const d=await api(`/api/friend-requests?token=${encodeURIComponent(token)}`);
  const list=d.requests||[];
  $("friendRequestsCount").textContent=list.length;
  if($("headerFriendBadge")){
    $("headerFriendBadge").textContent=list.length;
    $("headerFriendBadge").classList.toggle("hidden",list.length===0);
  }
  $("friendRequestsList").innerHTML=list.length?list.map(r=>{
    const u=r.user,a=u.avatar||avatarFallback(u.fullName||u.displayName||u.username);
    return `<div class="person-card">
      <button class="person-main" data-open-profile="${esc(u.username)}"><img src="${a}"><span><strong>${esc(u.fullName||u.displayName||u.username)}</strong><small>@${esc(u.username)}</small></span></button>
      <div class="person-actions"><button class="gold-mini" data-accept-friend="${esc(u.username)}">قبول</button><button class="dark-mini" data-decline-friend="${esc(u.username)}">رفض</button></div>
    </div>`;
  }).join(""):`<div class="empty-line">لا توجد طلبات صداقة جديدة.</div>`;
  bindProfileLinks();

  document.querySelectorAll("[data-accept-friend]").forEach(b=>b.onclick=async()=>{
    await api("/api/friend-action",{method:"POST",body:JSON.stringify({token,username:b.dataset.acceptFriend,action:"accept"})});
    await loadFriendRequests(); await loadFriends(me.username); await loadChatContacts();
  });
  document.querySelectorAll("[data-decline-friend]").forEach(b=>b.onclick=async()=>{
    await api("/api/friend-action",{method:"POST",body:JSON.stringify({token,username:b.dataset.declineFriend,action:"decline"})});
    await loadFriendRequests();
  });
}

async function loadFriends(username){
  const d=await api(`/api/friends?token=${encodeURIComponent(token)}&username=${encodeURIComponent(username)}`);
  const list=d.friends||[];
  $("friendsCount").textContent=list.length;
  $("friendsList").innerHTML=list.length?list.map(u=>{
    const a=u.avatar||avatarFallback(u.fullName||u.displayName||u.username);
    return `<button class="friend-card" data-open-profile="${esc(u.username)}"><img src="${a}"><span><strong>${esc(u.fullName||u.displayName||u.username)}</strong><small>@${esc(u.username)}</small></span></button>`;
  }).join(""):`<div class="empty-line">لا يوجد أصدقاء لعرضهم.</div>`;
  bindProfileLinks();
}

function openUserProfile(username){
  viewedProfileUsername=username;
  showPage("profilePage");
}

function bindProfileLinks(){
  document.querySelectorAll("[data-open-profile]").forEach(el=>{
    el.onclick=()=>openUserProfile(el.dataset.openProfile);
  });
}

$("friendActionBtn").onclick=()=>runFriendAction(viewedProfileUsername,viewedFriendState);
$("messageProfileBtn").onclick=()=>{
  selectedUser=viewedProfileUsername;selectedGroup=null;
  $("selectedLabel").textContent=selectedUser;
  showPage("messagesPage");loadConversation(selectedUser);
};
$("myProfileBtn").onclick=()=>{viewedProfileUsername=me.username;renderProfilePage(me.username)};
$("friendsStatBtn").onclick=()=>$("friendsSection").scrollIntoView({behavior:"smooth"});

async function loadPosts(){
  if(!token)return;
  try{
    const d=await api(`/api/posts?token=${encodeURIComponent(token)}`);
    const posts=d.posts||[];

    const shareCounts={};
    posts.forEach(p=>{
      if(p.sharedPostId)shareCounts[p.sharedPostId]=(shareCounts[p.sharedPostId]||0)+1;
    });

    $("postsFeed").innerHTML=posts.length?posts.map(post=>{
      const a=post.authorInfo||{username:post.author,displayName:post.author};
      const avatar=a.avatar||avatarFallback(a.fullName||a.displayName||a.username);
      const likeCount=totalReactionsCount(post.reactionCounts||{});
      const commentCount=post.commentCount||0;
      const shareCount=shareCounts[post.id]||0;
      const comments=(post.comments||[]).slice(-5).map(c=>{
        const replies=(c.replies||[]).map(r=>`
          <div class="comment-reply"><strong>@${esc(r.author)}</strong> ${esc(r.text)}</div>
        `).join("");

        return `<div class="comment-row">
          <div><strong>@${esc(c.author)}</strong> ${esc(c.text)}</div>
          <button class="reply-open-btn" data-reply-open="${c.id}" data-post-id="${post.id}">رد</button>
          ${replies}
          <div class="reply-compose hidden" data-reply-box="${c.id}">
            <input data-reply-input="${c.id}" placeholder="اكتب ردًا...">
            <button data-reply-send="${c.id}" data-post-id="${post.id}">إرسال</button>
          </div>
        </div>`;
      }).join("");

      return `
      <article class="post-card reference-post-card" data-post="${post.id}">
        <div class="reference-post-header">
          <button class="post-head profile-link-button reference-post-head" data-open-profile="${esc(a.username)}">
            <img src="${avatar}" class="avatar" alt="">
            <span class="reference-post-meta">
              <strong>${esc(a.fullName||a.displayName||a.username)}</strong>
              <small>@${esc(a.username)} <i>•</i> ${timeAgoAr(post.createdAt)} <span class="globe-inline">${iconGlobe()}</span></small>
            </span>
          </button>
          <button class="post-menu-btn" type="button" aria-label="المزيد">${iconDots()}</button>
        </div>
        ${post.text?`<div class="post-text reference-post-text">${esc(post.text)}</div>`:""}
        ${post.image?`<img class="post-image reference-post-image" src="${post.image}" alt="">`:""}
        ${post.sharedPost?`
        <div class="shared-post-box">
          <div class="shared-post-head">@${esc(post.sharedPost.authorInfo?.username||post.sharedPost.author)}</div>
          ${post.sharedPost.text?`<div>${esc(post.sharedPost.text)}</div>`:""}
          ${post.sharedPost.image?`<img src="${post.sharedPost.image}" class="shared-post-image" alt="">`:""}
        </div>`:""}
        <div class="reference-post-footer">
          <div class="reference-post-side-actions">
            <button class="text-action-btn ${post.savedByMe?"active":""}" data-save-post="${post.id}">${iconBookmark()}<span>${post.savedByMe?"محفوظ":"حفظ"}</span></button>
            <button class="text-action-btn" data-share-post="${post.id}">${iconShare()}<span>مشاركة</span></button>
          </div>
          <div class="reference-post-stats">
            <button class="stat-btn ${post.myReaction?"active":""}" data-react-main="${post.id}">${iconHeart()}<span>${likeCount}</span></button>
            <button class="stat-btn" data-comment-focus="${post.id}">${iconComment()}<span>${commentCount}</span></button>
            <button class="stat-btn" data-share-post="${post.id}">${iconRepeat()}<span>${shareCount}</span></button>
          </div>
        </div>
        <div class="reaction-picker hidden reference-reaction-picker" data-reaction-picker="${post.id}">
          ${Object.entries(reactionEmoji).map(([k,e])=>`<button data-react="${post.id}" data-reaction="${k}">${e}</button>`).join("")}
        </div>
        <div class="comments-box reference-comments-box">
          <div class="comments-list">${comments}</div>
          <div class="comment-compose">
            <input data-comment-input="${post.id}" placeholder="اكتب تعليقًا...">
            <button data-comment-send="${post.id}">إرسال</button>
          </div>
        </div>
      </article>`;
    }).join(""):`<div class="empty-card">لا توجد منشورات بعد.</div>`;

    bindProfileLinks();

    document.querySelectorAll("[data-like]").forEach(btn=>{
      btn.onclick=async()=>{
        try{
          await api("/api/post-like",{method:"POST",body:JSON.stringify({token,postId:btn.dataset.like})});
          await loadPosts();
        }catch(e){alert(e.message)}
      };
    });

    document.querySelectorAll("[data-comment-send]").forEach(btn=>{
      btn.onclick=async()=>{
        const id=btn.dataset.commentSend;
        const input=document.querySelector(`[data-comment-input="${id}"]`);
        const text=input.value.trim();
        if(!text)return;
        try{
          await api("/api/post-comment",{method:"POST",body:JSON.stringify({token,postId:id,text})});
          await loadPosts();
        }catch(e){alert(e.message)}
      };
    });

    document.querySelectorAll("[data-comment-focus]").forEach(btn=>{
      btn.onclick=()=>document.querySelector(`[data-comment-input="${btn.dataset.commentFocus}"]`)?.focus();
    });

    document.querySelectorAll("[data-react-main]").forEach(btn=>{
      btn.onclick=()=>{
        document.querySelector(`[data-reaction-picker="${btn.dataset.reactMain}"]`)?.classList.toggle("hidden");
      };
    });

    document.querySelectorAll("[data-react]").forEach(btn=>{
      btn.onclick=async()=>{
        try{
          await api("/api/post-react",{method:"POST",body:JSON.stringify({
            token,
            postId:btn.dataset.react,
            reaction:btn.dataset.reaction
          })});
          await loadPosts();
        }catch(e){alert(e.message)}
      };
    });

    document.querySelectorAll("[data-save-post]").forEach(btn=>{
      btn.onclick=async()=>{
        try{
          const d=await api("/api/post-save",{method:"POST",body:JSON.stringify({token,postId:btn.dataset.savePost})});
          btn.classList.toggle("active",!!d.saved); const span=btn.querySelector("span"); if(span) span.textContent=d.saved?"محفوظ":"حفظ";
        }catch(e){alert(e.message)}
      };
    });

    document.querySelectorAll("[data-share-post]").forEach(btn=>{
      btn.onclick=async()=>{
        const text=prompt("اكتب تعليقًا على المشاركة (اختياري)","")||"";
        try{
          await api("/api/post-share",{method:"POST",body:JSON.stringify({token,postId:btn.dataset.sharePost,text})});
          await loadPosts();
        }catch(e){alert(e.message)}
      };
    });

    document.querySelectorAll("[data-report-post]").forEach(btn=>{
      btn.onclick=async()=>{
        const reason=prompt("سبب الإبلاغ عن المنشور:");
        if(!reason)return;
        try{
          await api("/api/report",{method:"POST",body:JSON.stringify({
            token,targetType:"post",targetId:btn.dataset.reportPost,reason
          })});
          alert("تم إرسال البلاغ");
        }catch(e){alert(e.message)}
      };
    });

    document.querySelectorAll("[data-reply-open]").forEach(btn=>{
      btn.onclick=()=>{
        document.querySelector(`[data-reply-box="${btn.dataset.replyOpen}"]`)?.classList.toggle("hidden");
      };
    });

    document.querySelectorAll("[data-reply-send]").forEach(btn=>{
      btn.onclick=async()=>{
        const input=document.querySelector(`[data-reply-input="${btn.dataset.replySend}"]`);
        const text=input?.value.trim();
        if(!text)return;
        try{
          await api("/api/comment-reply",{method:"POST",body:JSON.stringify({
            token,
            postId:btn.dataset.postId,
            commentId:btn.dataset.replySend,
            text
          })});
          await loadPosts();
        }catch(e){alert(e.message)}
      };
    });

  }catch(e){
    $("postsFeed").innerHTML=`<div class="empty-card">${esc(e.message)}</div>`;
  }
}

$("postImageInput").onchange=e=>{
  const f=e.target.files?.[0];
  if(!f)return;
  const r=new FileReader();
  r.onload=()=>{
    postImageData=String(r.result||"");
    $("postImagePreview").src=postImageData;
    $("postImagePreviewWrap").classList.remove("hidden");
  };
  r.readAsDataURL(f);
};

$("publishPostBtn").onclick=async()=>{
  const text=$("postText").value.trim();
  if(!text && !postImageData)return alert("اكتب منشورًا أو أضف صورة");
  try{
    await api("/api/posts",{method:"POST",body:JSON.stringify({token,text,image:postImageData})});
    $("postText").value="";
    $("postImageInput").value="";
    postImageData="";
    $("postImagePreviewWrap").classList.add("hidden");
    await loadPosts();
  }catch(e){alert(e.message)}
};

async function searchUsers(){
  const q=$("userSearchInput").value.trim();
  try{
    const d=await api(`/api/users?token=${encodeURIComponent(token)}&q=${encodeURIComponent(q)}`);
    const found=(d.users||[]).filter(u=>u.username!==me?.username);

    $("searchResults").innerHTML=found.length?found.map(u=>`
      <div class="user-search-card">
        <img src="${u.avatar||avatarFallback(u.fullName||u.displayName||u.username)}" class="avatar" alt="">
        <div>
          <strong>${esc(u.fullName||u.displayName||u.username)}</strong>
          <small>@${esc(u.username)}</small>
        </div>
        <div class="user-search-actions">
          <button class="gold-mini" data-search-friend="${esc(u.username)}">إضافة صديق</button>
          <button class="dark-mini" data-open-profile="${esc(u.username)}">الملف</button>
          <button class="dark-mini" data-message-user="${esc(u.username)}">رسالة</button>
        </div>
      </div>
    `).join(""):`<div class="empty-card">لا توجد نتائج</div>`;
    bindProfileLinks();

    document.querySelectorAll("[data-search-friend]").forEach(btn=>{
      btn.onclick=async()=>{
        try{
          await api("/api/friend-request",{method:"POST",body:JSON.stringify({
            token,username:btn.dataset.searchFriend
          })});
          btn.textContent="تم إرسال الطلب";
          btn.disabled=true;
        }catch(e){alert(e.message)}
      };
    });

    document.querySelectorAll("[data-message-user]").forEach(btn=>{
      btn.onclick=()=>{
        const username=btn.dataset.messageUser;
        const friend=chatFriends.find(u=>u.username===username);

        if(!friend){
          return alert("يجب أن يكون المستخدم ضمن أصدقائك لبدء محادثة.");
        }

        showPage("messagesPage");
        openDirectChat(username,friend);
      };
    });
  }catch(e){alert(e.message)}
}

$("userSearchBtn").onclick=searchUsers;
$("userSearchInput").onkeydown=e=>{if(e.key==="Enter")searchUsers()};

async function loadNotifications(){
  try{
    const d=await api(`/api/notifications?token=${encodeURIComponent(token)}`);
    const list=d.notifications||[];

    $("notificationsList").innerHTML=list.length?list.map(n=>{
      const label=n.type==="like"?`أعجب @${esc(n.from)} بمنشورك`:n.type==="follow"?`بدأ @${esc(n.from)} بمتابعتك`:`علّق @${esc(n.from)} على منشورك${n.text?`: ${esc(n.text)}`:""}`;
      return `<div class="notification-card ${n.read?"read":""}">${label}<small>${new Date(n.createdAt).toLocaleString("ar-SA")}</small></div>`;
    }).join(""):`<div class="empty-card">لا توجد إشعارات</div>`;
  }catch(e){alert(e.message)}
}

$("markNotificationsRead").onclick=async()=>{
  try{
    await api("/api/notifications/read",{method:"POST",body:JSON.stringify({token})});
    await loadNotifications();
  }catch(e){alert(e.message)}
};



async function loadStories(){
  if(!token)return;
  try{
    const d=await api(`/api/stories?token=${encodeURIComponent(token)}`);
    const stories=d.stories||[];

    const renderStory=s=>{
      const u=s.authorInfo||{username:s.author};
      const av=u.avatar||avatarFallback(u.displayName||u.username);
      return `<button class="story-item" data-story="${s.id}">
        <span class="story-ring"><img src="${av}" alt=""></span>
        <small>${esc(u.displayName||u.username)}</small>
      </button>`;
    };

    if($("storiesStrip")){
      $("storiesStrip").innerHTML=stories.length
        ? stories.map(renderStory).join("")
        : '<div class="story-empty">لا توجد حالات</div>';
    }

    if($("storiesStripSide")){
      const add=`<button id="sideAddStoryInline" class="side-add-story">
        <span class="story-plus-icon">${uiIcon("plus")}</span><small>إضافة قصة</small>
      </button>`;
      $("storiesStripSide").innerHTML=add+(stories.length
        ? stories.slice(0,5).map(renderStory).join("")
        : '<div class="reference-side-empty">لا توجد قصص</div>');
      if($("sideAddStoryInline")){
        $("sideAddStoryInline").onclick=()=>$("addStoryBtn")?.click();
      }
    }

    document.querySelectorAll("[data-story]").forEach(b=>{
      b.onclick=()=>{
        const s=stories.find(x=>x.id===b.dataset.story);
        if(!s)return;
        $("storyViewerAuthor").textContent=s.authorInfo?.displayName||s.author;
        $("storyViewerText").textContent=s.text||"";
        if(s.media){
          $("storyViewerImage").src=s.media;
          $("storyViewerImage").classList.remove("hidden");
        }else{
          $("storyViewerImage").classList.add("hidden");
        }
        $("storyViewer").classList.remove("hidden");
      };
    });
  }catch(e){
    console.error(e);
  }
}
$("addStoryBtn").onclick=()=>$("storyModal").classList.remove("hidden");$("closeStoryModalBtn").onclick=()=>$("storyModal").classList.add("hidden");$("closeStoryViewer").onclick=()=>$("storyViewer").classList.add("hidden");
$("storyMediaInput").onchange=e=>{const f=e.target.files?.[0];if(!f)return;const r=new FileReader();r.onload=()=>{storyMediaData=String(r.result||"");$("storyPreview").src=storyMediaData;$("storyPreview").classList.remove("hidden")};r.readAsDataURL(f)};
$("publishStoryBtn").onclick=async()=>{const text=$("storyTextInput").value.trim();if(!text&&!storyMediaData)return alert("اكتب حالة أو أضف صورة");try{await api('/api/stories',{method:'POST',body:JSON.stringify({token,text,media:storyMediaData})});$("storyTextInput").value="";storyMediaData="";$("storyModal").classList.add("hidden");$("storyPreview").classList.add("hidden");loadStories()}catch(e){alert(e.message)}};
$("coverInput").onchange=e=>{const f=e.target.files?.[0];if(!f)return;const r=new FileReader();r.onload=()=>{profileCoverData=String(r.result||"");$("profileCover").style.backgroundImage=`url("${profileCoverData}")`};r.readAsDataURL(f)};
$("saveProfileBtn").onclick=async()=>{try{const body={token,bio:$("bioInput").value.trim(),accountPrivate:$("privateAccountInput").checked};if(profileCoverData)body.cover=profileCoverData;const d=await api('/api/profile',{method:'POST',body:JSON.stringify(body)});me={...me,...d.user};profileCoverData="";renderProfilePage()}catch(e){alert(e.message)}};

function readFileDataUrl(file){
  return new Promise((resolve,reject)=>{
    const r=new FileReader();
    r.onload=()=>resolve(String(r.result||""));
    r.onerror=()=>reject(new Error("تعذر قراءة الملف"));
    r.readAsDataURL(file);
  });
}

async function compressChatImage(file){
  const original=await readFileDataUrl(file);

  if(file.size<=420000){
    return original;
  }

  const image=await new Promise((resolve,reject)=>{
    const img=new Image();
    img.onload=()=>resolve(img);
    img.onerror=()=>reject(new Error("تعذر فتح الصورة"));
    img.src=original;
  });

  const maxSide=1280;
  let width=image.naturalWidth||image.width;
  let height=image.naturalHeight||image.height;

  const scale=Math.min(1,maxSide/Math.max(width,height));
  width=Math.max(1,Math.round(width*scale));
  height=Math.max(1,Math.round(height*scale));

  const canvas=document.createElement("canvas");
  canvas.width=width;
  canvas.height=height;

  const ctx=canvas.getContext("2d");
  ctx.fillStyle="#fff";
  ctx.fillRect(0,0,width,height);
  ctx.drawImage(image,0,0,width,height);

  let quality=.82;
  let data=canvas.toDataURL("image/jpeg",quality);

  while(data.length>880000 && quality>.52){
    quality-=.08;
    data=canvas.toDataURL("image/jpeg",quality);
  }

  if(data.length>930000){
    const scale2=.78;
    const c2=document.createElement("canvas");
    c2.width=Math.max(1,Math.round(width*scale2));
    c2.height=Math.max(1,Math.round(height*scale2));
    const x2=c2.getContext("2d");
    x2.fillStyle="#fff";
    x2.fillRect(0,0,c2.width,c2.height);
    x2.drawImage(canvas,0,0,c2.width,c2.height);
    data=c2.toDataURL("image/jpeg",.68);
  }

  return data;
}

function updateChatMediaPreview(){
  const box=$("chatMediaPreview");
  if(!box)return;

  if(!pendingChatMedia){
    box.classList.add("hidden");
    $("chatPreviewImage")?.classList.add("hidden");
    return;
  }

  box.classList.remove("hidden");

  const img=$("chatPreviewImage");
  const title=$("chatPreviewTitle");
  const info=$("chatPreviewInfo");

  if(pendingChatMedia.type==="image"){
    img.src=pendingChatMedia.data;
    img.classList.remove("hidden");
    title.textContent="الصورة جاهزة للإرسال";
  }else{
    img.classList.add("hidden");
    title.textContent=pendingChatMedia.type==="audio"?"الصوت جاهز للإرسال":"الملف جاهز للإرسال";
  }

  info.textContent=pendingChatMedia.name||"";
}

function clearPendingChatMedia(){
  pendingChatMedia=null;

  if($("chatImageInput"))$("chatImageInput").value="";
  if($("chatFileInput"))$("chatFileInput").value="";

  $("messageInput").placeholder="اكتب رسالة...";
  updateChatMediaPreview();
}

$("clearChatMediaBtn").onclick=clearPendingChatMedia;

$("chatImageInput").onchange=async e=>{
  const f=e.target.files?.[0];
  if(!f)return;

  try{
    $("chatPreviewTitle").textContent="جاري تجهيز الصورة...";
    $("chatMediaPreview").classList.remove("hidden");

    const data=await compressChatImage(f);

    if(data.length>950000){
      clearPendingChatMedia();
      return alert("الصورة ما زالت كبيرة جدًا. اختر صورة أصغر.");
    }

    pendingChatMedia={data,type:"image",name:f.name};
    $("messageInput").placeholder="يمكنك كتابة تعليق مع الصورة...";
    updateChatMediaPreview();
  }catch(err){
    clearPendingChatMedia();
    alert(err.message||"تعذر تجهيز الصورة");
  }
};

$("chatFileInput").onchange=async e=>{
  const f=e.target.files?.[0];
  if(!f)return;

  if(f.size>650000){
    e.target.value="";
    return alert("حجم الملف كبير. الحد الحالي حوالي 650 كيلوبايت للملفات.");
  }

  try{
    const data=await readFileDataUrl(f);
    pendingChatMedia={
      data,
      type:f.type.startsWith("audio/")?"audio":"file",
      name:f.name
    };
    $("messageInput").placeholder=`مرفق جاهز: ${f.name}`;
    updateChatMediaPreview();
  }catch(err){
    clearPendingChatMedia();
    alert(err.message||"تعذر قراءة الملف");
  }
};

$("recordVoiceBtn").onclick=async()=>{
  if(mediaRecorder?.state==="recording"){
    mediaRecorder.stop();
    setVoiceRecordButton(false);
    return;
  }

  try{
    const s=await navigator.mediaDevices.getUserMedia({audio:true});
    mediaRecorder=new MediaRecorder(s);
    voiceChunks=[];

    mediaRecorder.ondataavailable=e=>{
      if(e.data.size)voiceChunks.push(e.data);
    };

    mediaRecorder.onstop=()=>{
      setVoiceRecordButton(false);
      const b=new Blob(voiceChunks,{type:"audio/webm"});
      const r=new FileReader();

      r.onload=()=>{
        pendingChatMedia={
          data:String(r.result||""),
          type:"audio",
          name:"رسالة صوتية"
        };
        $("messageInput").placeholder="رسالة صوتية جاهزة";
        updateChatMediaPreview();
      };

      r.readAsDataURL(b);
      s.getTracks().forEach(t=>t.stop());
    };

    mediaRecorder.start();
    setVoiceRecordButton(true);
  }catch(e){
    setVoiceRecordButton(false);
    alert("تعذر تشغيل الميكروفون");
  }
};
$("messageInput").addEventListener('input',()=>{if(!selectedUser)return;send({type:'typing',to:selectedUser,active:true});clearTimeout(typingTimer);typingTimer=setTimeout(()=>send({type:'typing',to:selectedUser,active:false}),900)});


async function loadExplore(){
  try{
    const d=await api(`/api/explore?token=${encodeURIComponent(token)}`);
    const posts=d.posts||[];

    $("exploreFeed").innerHTML=posts.length?posts.map(p=>`
      <article class="explore-card">
        ${p.image?`<img src="${p.image}" alt="">`:`<div class="explore-text">${esc(p.text||"منشور")}</div>`}
        <div class="explore-overlay">
          <strong>@${esc(p.authorInfo?.username||p.author)}</strong>
          <span>⭐ ${p.score||0}</span>
        </div>
      </article>
    `).join(""):`<div class="empty-card">لا توجد منشورات للاستكشاف</div>`;
  }catch(e){
    $("exploreFeed").innerHTML=`<div class="empty-card">${esc(e.message)}</div>`;
  }
}

async function loadSavedPosts(){
  try{
    const d=await api(`/api/saved-posts?token=${encodeURIComponent(token)}`);
    const posts=d.posts||[];

    $("savedPostsList").innerHTML=posts.length?posts.map(p=>`
      <div class="saved-post-card">
        <strong>@${esc(p.authorInfo?.username||p.author)}</strong>
        ${p.text?`<p>${esc(p.text)}</p>`:""}
        ${p.image?`<img src="${p.image}" alt="">`:""}
      </div>
    `).join(""):`<div class="empty-card">لا توجد منشورات محفوظة</div>`;
  }catch(e){alert(e.message)}
}

$("refreshExploreBtn").onclick=loadExplore;
$("loadSavedBtn").onclick=loadSavedPosts;



async function loadDesktopSuggestions(){
  if(!$("suggestionsList") || !token)return;
  try{
    const d=await api(`/api/users?token=${encodeURIComponent(token)}&q=`);
    const users=(d.users||[]).filter(u=>u.username!==me?.username).slice(0,4);

    $("suggestionsList").innerHTML=users.length?users.map(u=>{
      const avatar=u.avatar||avatarFallback(u.fullName||u.displayName||u.username);
      return `<div class="reference-suggestion-row">
        <button class="reference-suggestion-user" data-open-profile="${esc(u.username)}">
          <img src="${avatar}" alt="">
          <span><strong>${esc(u.fullName||u.displayName||u.username)}</strong><small>@${esc(u.username)}</small></span>
        </button>
        <button class="reference-friend-btn" data-side-friend="${esc(u.username)}">إضافة صديق</button>
      </div>`;
    }).join(""):`<div class="reference-side-empty">لا توجد اقتراحات الآن</div>`;

    bindProfileLinks();

    document.querySelectorAll("[data-side-friend]").forEach(btn=>{
      btn.onclick=async()=>{
        try{
          await api("/api/friend-request",{method:"POST",body:JSON.stringify({
            token,username:btn.dataset.sideFriend
          })});
          btn.textContent="تم الإرسال";
          btn.disabled=true;
        }catch(e){alert(e.message)}
      };
    });
  }catch(e){
    $("suggestionsList").innerHTML='<div class="reference-side-empty">تعذر تحميل الاقتراحات</div>';
  }
}

async function loadSavedPostsPage(){
  if(!$("savedPostsPageList"))return;
  try{
    const d=await api(`/api/saved-posts?token=${encodeURIComponent(token)}`);
    const posts=d.posts||[];
    $("savedPostsPageList").innerHTML=posts.length?posts.map(p=>`
      <article class="saved-page-card">
        <div class="saved-page-author">@${esc(p.authorInfo?.username||p.author)}</div>
        ${p.text?`<p>${esc(p.text)}</p>`:""}
        ${p.image?`<img src="${p.image}" alt="">`:""}
      </article>
    `).join(""):`<div class="empty-card">لا توجد منشورات محفوظة</div>`;
  }catch(e){
    $("savedPostsPageList").innerHTML=`<div class="empty-card">${esc(e.message)}</div>`;
  }
}

if($("refreshSavedPageBtn"))$("refreshSavedPageBtn").onclick=loadSavedPostsPage;


let authMode="login";
let pendingVerificationId="";
let verificationCountdownTimer=null;



function setVerificationCountdown(seconds=600){
  clearInterval(verificationCountdownTimer);

  let remaining=Math.max(0,Number(seconds)||0);

  const paint=()=>{
    const mins=Math.floor(remaining/60);
    const secs=String(remaining%60).padStart(2,"0");
    if($("verificationExpiryText")){
      $("verificationExpiryText").textContent=
        remaining>0
          ? `الكود صالح لمدة ${mins}:${secs}`
          : "انتهت صلاحية الكود. اطلب إعادة الإرسال.";
    }
    remaining=Math.max(0,remaining-1);
  };

  paint();
  verificationCountdownTimer=setInterval(paint,1000);
}

function showVerificationStep(data){
  pendingVerificationId=String(data?.verificationId||pendingVerificationId||"");
  authMode="verify";

  $("authMainFields").classList.add("hidden");
  $("registerFields").classList.add("hidden");
  $("confirmPasswordInput").classList.add("hidden");
  $("verificationFields").classList.remove("hidden");
  $("authBtn").textContent="تأكيد الكود وإنشاء الحساب";

  if($("verificationDeliveryText")){
    $("verificationDeliveryText").textContent=
      `أرسلنا كود التأكيد إلى ${data?.delivery||"بريدك الإلكتروني"}`;
  }

  $("verificationCodeInput").value="";
  $("verificationCodeInput").focus();
  $("loginTab").disabled=true;
  $("registerTab").disabled=true;
  if($("attaRegisterShortcut"))$("attaRegisterShortcut").classList.add("hidden");

  setVerificationCountdown(data?.expiresIn||600);
}

function exitVerificationStep(){
  clearInterval(verificationCountdownTimer);
  pendingVerificationId="";
  authMode="register";

  $("authMainFields").classList.remove("hidden");
  $("registerFields").classList.remove("hidden");
  $("confirmPasswordInput").classList.remove("hidden");
  $("verificationFields").classList.add("hidden");
  $("authBtn").textContent="إرسال كود التأكيد";
  $("loginTab").disabled=false;
  $("registerTab").disabled=false;
  $("registerTab").classList.add("active");
  $("loginTab").classList.remove("active");
  $("authMsg").textContent="";
}

if($("attaRegisterShortcut")){
  $("attaRegisterShortcut").onclick=()=>{
    authMode="register";
    $("registerFields").classList.remove("hidden");
    $("confirmPasswordInput").classList.remove("hidden");
    $("authBtn").textContent="إرسال كود التأكيد";
  if($("attaRegisterShortcut"))$("attaRegisterShortcut").classList.add("hidden");
    $("attaRegisterShortcut").classList.add("hidden");
  };
}

$("loginTab").onclick=()=>{
  clearInterval(verificationCountdownTimer);
  pendingVerificationId="";
  authMode="login";
  $("authMainFields").classList.remove("hidden");
  $("verificationFields").classList.add("hidden");
  $("loginTab").classList.add("active");
  $("registerTab").classList.remove("active");
  $("registerFields").classList.add("hidden");
  $("confirmPasswordInput").classList.add("hidden");
  $("authBtn").textContent="تسجيل الدخول";
  if($("attaRegisterShortcut"))$("attaRegisterShortcut").classList.remove("hidden");
};

$("registerTab").onclick=()=>{
  clearInterval(verificationCountdownTimer);
  pendingVerificationId="";
  authMode="register";
  $("authMainFields").classList.remove("hidden");
  $("verificationFields").classList.add("hidden");
  $("registerTab").classList.add("active");
  $("loginTab").classList.remove("active");
  $("registerFields").classList.remove("hidden");
  $("confirmPasswordInput").classList.remove("hidden");
  $("authBtn").textContent="إرسال كود التأكيد";
};

$("authBtn").onclick=async()=>{
  ensureCallAudio();

  try{
    $("authMsg").textContent="";

    if(authMode==="verify"){
      const code=$("verificationCodeInput").value.trim().replace(/\D/g,"");

      if(!/^\d{6}$/.test(code)){
        throw new Error("اكتب كود التأكيد المكون من 6 أرقام");
      }

      $("authBtn").disabled=true;
      $("authBtn").textContent="جاري التأكيد...";

      const d=await api("/api/register-verify",{
        method:"POST",
        body:JSON.stringify({
          verificationId:pendingVerificationId,
          code
        })
      });

      clearInterval(verificationCountdownTimer);
      pendingVerificationId="";
      $("authBtn").disabled=false;
      $("authBtn").textContent="تم إنشاء الحساب";

      await startAuthenticatedApp(d.token,d.user);
      return;
    }

    const body={
      username:$("usernameInput").value.trim(),
      password:$("passwordInput").value
    };

    if(authMode==="register"){
      body.fullName=$("fullNameInput").value.trim();
      body.age=$("ageInput").value;
      body.phone=$("phoneInput").value.trim();
      body.email=$("emailInput").value.trim();
      body.gender=$("genderInput").value;
      body.country=$("countryInput").value.trim();
      body.confirmPassword=$("confirmPasswordInput").value;

      $("authBtn").disabled=true;
      $("authBtn").textContent="جاري إرسال الكود...";

      const d=await api("/api/register-request",{
        method:"POST",
        body:JSON.stringify(body)
      });

      $("authBtn").disabled=false;
      showVerificationStep(d);
      return;
    }

    $("authBtn").disabled=true;
    $("authBtn").textContent="جاري تسجيل الدخول...";

    const d=await api("/api/login",{
      method:"POST",
      body:JSON.stringify(body)
    });

    $("authBtn").disabled=false;
    $("authBtn").textContent="تسجيل الدخول";
    await startAuthenticatedApp(d.token,d.user);
  }catch(e){
    $("authBtn").disabled=false;

    if(authMode==="verify"){
      $("authBtn").textContent="تأكيد الكود وإنشاء الحساب";
    }else if(authMode==="register"){
      $("authBtn").textContent="إرسال كود التأكيد";
    }else{
      $("authBtn").textContent="تسجيل الدخول";
    }

    $("authMsg").textContent=e.message;
  }
};

$("verificationCodeInput").addEventListener("input",e=>{
  e.target.value=e.target.value.replace(/\D/g,"").slice(0,6);
  if(e.target.value.length===6){
    $("authMsg").textContent="";
  }
});

$("verificationCodeInput").addEventListener("keydown",e=>{
  if(e.key==="Enter")$("authBtn").click();
});

$("cancelVerificationBtn").onclick=exitVerificationStep;

$("resendVerificationBtn").onclick=async()=>{
  if(!pendingVerificationId)return;

  try{
    $("authMsg").textContent="";
    $("resendVerificationBtn").disabled=true;
    $("resendVerificationBtn").textContent="جاري الإرسال...";

    const d=await api("/api/register-resend",{
      method:"POST",
      body:JSON.stringify({verificationId:pendingVerificationId})
    });

    if($("verificationDeliveryText")){
      $("verificationDeliveryText").textContent=
        `أرسلنا كودًا جديدًا إلى ${d.delivery||"بريدك الإلكتروني"}`;
    }

    $("verificationCodeInput").value="";
    $("verificationCodeInput").focus();
    setVerificationCountdown(d.expiresIn||600);
    $("authMsg").textContent="تم إرسال كود جديد.";
  }catch(e){
    $("authMsg").textContent=e.message;
  }finally{
    $("resendVerificationBtn").disabled=false;
    $("resendVerificationBtn").textContent="إعادة إرسال الكود";
  }
};

$("avatarInput").onchange=async e=>{
  const f=e.target.files?.[0];
  if(!f)return;

  const reader=new FileReader();

  reader.onload=async()=>{
    try{
      const d=await api("/api/profile",{
        method:"POST",
        body:JSON.stringify({token,avatar:reader.result})
      });
      me=d.user;
      renderProfile();
      $("homeAvatar").src=me.avatar||avatarFallback(me.fullName||me.displayName||me.username);
      renderProfilePage();
    }catch(err){
      alert(err.message);
    }
  };

  reader.readAsDataURL(f);
};

$("newGroupBtn").onclick=async()=>{
  const name=prompt("اسم المجموعة");
  if(!name)return;

  const members=prompt("اكتب أسماء المستخدمين مفصولة بفاصلة","")
    ?.split(",")
    .map(x=>x.trim())
    .filter(Boolean) || [];

  try{
    await api("/api/groups",{
      method:"POST",
      body:JSON.stringify({token,name,members})
    });
    await loadGroups();
  }catch(e){
    alert(e.message);
  }
};

document.querySelectorAll(".side-tab").forEach(btn=>{
  btn.onclick=()=>{
    document.querySelectorAll(".side-tab").forEach(x=>x.classList.remove("active"));
    btn.classList.add("active");

    ["users","groups","calls"].forEach(t=>{
      $(t+"Panel").classList.toggle("hidden",btn.dataset.tab!==t);
    });

    if(btn.dataset.tab==="groups")loadGroups();
    if(btn.dataset.tab==="calls")loadCalls();
  };
});

$("sendBtn").onclick=async()=>{
  const text=$("messageInput").value.trim();
  if(!text&&!pendingChatMedia)return;

  if(selectedGroup){
    if(ws?.readyState!==WebSocket.OPEN){
      return alert("المجموعات تحتاج اتصالًا مباشرًا الآن.");
    }
    send({type:"group-chat",groupId:selectedGroup.id,text});
  }else if(selectedUser){
    try{
      const d=await api("/api/message-send",{
        method:"POST",
        body:JSON.stringify({
          token,
          to:selectedUser,
          text,
          media:pendingChatMedia?.data||"",
          mediaType:pendingChatMedia?.type||"text"
        })
      });

      if(d.message){
        const exists=d.message.id && document.querySelector(`[data-message-id="${d.message.id}"]`);
        if(!exists)addRichMessage(d.message);
        lastConversationSignature="";
      }
    }catch(e){
      return alert(e.message||"تعذر إرسال الرسالة");
    }
  }else{
    return alert("اختر صديقًا أو مجموعة");
  }

  $("messageInput").value="";
  clearPendingChatMedia();
};

$("messageInput").onkeydown=e=>{
  if(e.key==="Enter")$("sendBtn").click();
};


if($("mobileBackContactsBtn")){
  $("mobileBackContactsBtn").onclick=()=>{
    resetMessagesInbox();
    loadChatContacts();
  };
}

if($("composerAudioCallBtn")){
  $("composerAudioCallBtn").onclick=()=>{
    ensureCallAudio();
    requestCall("audio");
  };
}

if($("composerVideoCallBtn")){
  $("composerVideoCallBtn").onclick=()=>{
    ensureCallAudio();
    requestCall("video");
  };
}

$("audioCallBtn").onclick=()=>{ensureCallAudio();requestCall("audio")};
$("videoCallBtn").onclick=()=>{ensureCallAudio();requestCall("video")};

$("acceptCallBtn").onclick=async()=>{
  if(callState!=="incoming" || !incomingFrom)return;

  stopCallSounds();
  $("incomingModal").classList.add("hidden");

  const peer=incomingFrom;
  const callId=activeCallId;
  selectedUser=peer;

  setCallState("connecting",peer,callId);
  showCallOverlay(peer,"جاري تجهيز الكاميرا والمايك...");

  try{
    await getMedia(currentCallType);
    await ensurePeer();

    await sendCallSignal({
      type:"call-accept",
      to:peer,
      callId,
      callType:currentCallType
    });

    $("callPeerState").textContent="تم الرد — جاري توصيل الوسائط...";
  }catch(err){
    await sendCallSignal({
      type:"call-reject",
      to:peer,
      callId,
      callType:currentCallType,
      reason:"media-unavailable"
    }).catch(()=>{});

    await teardownPeer(true);
    hideCallOverlay(false);
    resetCallState();
  }
};

$("rejectCallBtn").onclick=async()=>{
  if(callState!=="incoming" || !incomingFrom)return;

  const peer=incomingFrom;
  const callId=activeCallId;

  stopCallSounds();

  await sendCallSignal({
    type:"call-reject",
    to:peer,
    callId,
    callType:currentCallType
  }).catch(()=>{});

  $("incomingModal").classList.add("hidden");
  await teardownPeer(true);
  hideCallOverlay(false);
  resetCallState();
};

$("hangupBtn").onclick=async()=>{
  stopCallSounds();
  await hangup(true);
  hideCallOverlay(false);
};

$("muteBtn").onclick=()=>{
  const tracks=localStream?.getAudioTracks()||[];
  tracks.forEach(t=>t.enabled=!t.enabled);
  const muted=tracks[0]?.enabled===false;
  setRoundCallButton("muteBtn",muted?"mic-off":"mic",muted?"تشغيل المايك":"كتم المايك");
  $("muteBtn").classList.toggle("is-off",muted);
};

$("cameraBtn").onclick=()=>{
  const tracks=localStream?.getVideoTracks()||[];
  tracks.forEach(t=>t.enabled=!t.enabled);
  const off=tracks[0]?.enabled===false;
  setRoundCallButton("cameraBtn",off?"camera-off":"camera",off?"تشغيل الكاميرا":"إيقاف الكاميرا");
  $("cameraBtn").classList.toggle("is-off",off);
};


if($("resumeRemoteMediaBtn")){
  $("resumeRemoteMediaBtn").onclick=async()=>{
    try{
      const rv=$("remoteVideo");
      rv.muted=false;
      rv.volume=1;
      await rv.play();
      $("resumeRemoteMediaBtn").classList.add("hidden");
    }catch(err){
      console.warn("manual remote playback failed",err);
    }
  };
}

$("screenBtn").onclick=shareScreen;
window.addEventListener("beforeunload",stopCallSounds);

// افتح الحساب المحفوظ تلقائيًا بعد تجهيز كل عناصر الواجهة.
queueMicrotask(()=>{
  window.AttaIcons?.hydrate(document);
  setVoiceRecordButton(false);
  restoreSavedLogin().catch(err=>console.warn("restore login failed",err));
});
})();
