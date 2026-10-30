
(()=>{
const $=id=>document.getElementById(id);
const youtubePostMarker=/^\[\[alatta-youtube:([A-Za-z0-9_-]{11})\]\][ \t]*$/m;
function youtubePostInfo(text){
  const raw=String(text||'');
  const match=raw.match(youtubePostMarker);
  return {id:match?match[1]:'',text:raw.replace(youtubePostMarker,'').replace(/\n{3,}/g,'\n\n').trim()};
}
function youtubePostFrame(post){
  const id=youtubePostInfo(post.text).id;
  if(!id)return '';
  return `<div class="atta-youtube-post-media" data-youtube-post="${esc(post.id)}">
    <button class="atta-youtube-post-play" data-youtube-post-play="${esc(post.id)}" data-youtube-id="${id}" type="button" aria-label="تشغيل الفيديو داخل المنشور">
      <img loading="lazy" decoding="async" alt="معاينة الفيديو" data-watch-thumbnail="${id}" data-thumb-quality="max" src="https://i.ytimg.com/vi/${id}/maxresdefault.jpg">
      <span aria-hidden="true">▶</span>
    </button>
  </div>`;
}
function fitPostThumbnail(event){
  const image=event.target;
  if(image?.dataset?.watchThumbnail&&image.dataset.thumbQuality==='max'&&(event.type==='error'||image.naturalWidth<320)){
    image.dataset.thumbQuality='fallback';image.src='https://i.ytimg.com/vi/'+image.dataset.watchThumbnail+'/mqdefault.jpg';
  }
}
document.addEventListener('load',fitPostThumbnail,true);document.addEventListener('error',fitPostThumbnail,true);
document.addEventListener('click',event=>{
  const button=event.target.closest?.('[data-youtube-post-play]');
  if(!button)return;
  const id=button.dataset.youtubeId;
  if(!/^[A-Za-z0-9_-]{11}$/.test(id)||!window.AttaYouTubeEmbed)return;
  document.querySelectorAll('.atta-youtube-post-media iframe').forEach(frame=>frame.remove());
  const media=button.closest('.atta-youtube-post-media');
  media.classList.add('is-playing');media.replaceChildren(window.AttaYouTubeEmbed.create(id,'فيديو المنشور'));
});


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

    console.log("TURN enabled",d.provider||"cloudflare");

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
  const read=(opts.method||"GET").toUpperCase()==="GET";
  const deadline=Date.now()+20000;
  for(let attempt=0;;attempt++){
    const controller=new AbortController();
    const remaining=deadline-Date.now();
    if(remaining<=0)throw new Error("تأخر الاتصال بالخادم. أعد المحاولة.");
    const timer=setTimeout(()=>controller.abort(),remaining);
    let response;
    try{
      response=await fetch(path,{...opts,signal:opts.signal||controller.signal,
        headers:{"content-type":"application/json",...(opts.headers||{})}});
      const text=await response.text();
      let data;try{data=JSON.parse(text);}catch{throw new Error("تعذر قراءة استجابة الخادم. أعد المحاولة.");}
      if(!response.ok){const error=new Error(data.error||"حدث خطأ");error.status=response.status;error.code=data.code;throw error;}
      return data;
    }catch(error){
      if(read&&attempt===0&&!opts.signal&&error instanceof TypeError)continue;
      if(error.name==="AbortError")throw new Error("تأخر الاتصال بالخادم. أعد المحاولة.");
      if(error instanceof TypeError)throw new Error("انقطع الاتصال. تحقق من الإنترنت واضغط إعادة المحاولة.");
      throw error;
    }finally{clearTimeout(timer);}
  }
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
  }else{
    try{
      const d=await api(`/api/me?token=${encodeURIComponent(token)}`);
      user={...user,...d.user};
      if(!d.user?.role)user.role="user";
    }catch{
      // قد تبقي الجلسة المخزنة مؤقتًا الصفحات العادية متاحة دون اتصال؛ ولا تحفظ صلاحيات الإدارة أبدًا.
      user={...user,role:"user"};
    }
  }

  me=user;
  const canAdmin=["super_admin","moderator"].includes(me?.role);
  $("adminRailBtn")?.classList.toggle("hidden",!canAdmin);
  $("adminMoreBtn")?.classList.toggle("hidden",!canAdmin);
  viewedProfileUsername=me.username;
  appSessionStarted=true;
  saveSession(token);

  $("authView").classList.add("hidden");
  $("appView").classList.remove("hidden");

  renderProfile();
  $("homeAvatar").src=me.avatar||avatarFallback(me.fullName||me.displayName||me.username);
  // حمّل الملف الشخصي عند طلبه فقط؛ فهو غير مطلوب لعرض موجز الرئيسية.

  ensureTurnIceServers().catch(()=>{});

  // V13.60: اعرض الموجز الرئيسي أولًا، ثم حمّل اللوحات الثانوية تدريجيًا.
  connect();
  startCallSignalPolling();
  resetMessagesInbox();
  loadPosts();

  const deferStartup=(fn,delay)=>setTimeout(()=>{
    try{ Promise.resolve(fn()).catch(()=>{}); }catch{}
  },delay);

  deferStartup(loadStories,120);
  deferStartup(loadDesktopSuggestions,240);
  deferStartup(loadGroups,360);
  deferStartup(loadChatContacts,480);
  deferStartup(loadFriendRequests,600);
  deferStartup(()=>{ startSocialSync(); askNotifications(); },750);
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
    console.warn("Saved login could not be restored",err);
    if(err.status===401 || err.status===403)clearSavedSession();
    token="";resetPostMedia();
    me=null;
    appSessionStarted=false;
    $("appView").classList.add("hidden");
    $("authView").classList.remove("hidden");
    $("authMsg").textContent=err.message||"تعذر فتح الحساب. أعد المحاولة.";
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
function reactionSymbol(reaction){
  if(advancedReactions.includes(reaction))return `<span class="selected-reaction" role="img" aria-label="${reactionLabel(reaction)}">${reactionEmoji[reaction]}</span>`;
  return uiIcon(reaction==="like"?"heart-filled":"heart");
}
function reactionLabel(reaction){return ({like:"إعجاب",love:"أحببته",haha:"أضحكني",wow:"أدهشني",sad:"أحزنني",angry:"أغضبني"})[reaction]||"إعجاب";}
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

function showInAppToast(title,body){
  let host=document.getElementById("attaToastHost");
  if(!host){
    host=document.createElement("div");
    host.id="attaToastHost";
    host.className="atta-toast-host";
    document.body.appendChild(host);
  }
  const toast=document.createElement("div");
  toast.className="atta-toast";
  toast.innerHTML=`<strong>${esc(title||"إشعار")}</strong><span>${esc(body||"")}</span>`;
  host.appendChild(toast);
  requestAnimationFrame(()=>toast.classList.add("show"));
  setTimeout(()=>{toast.classList.remove("show");setTimeout(()=>toast.remove(),250)},4500);
  return toast;
}

function notify(title,body,target=null){
  // اعرض تنبيهًا داخل التطبيق دائمًا. إشعارات المتصفح ميزة إضافية فقط، لأن
  // Chrome قد يمنع طلب الإذن إذا لم ينتج عن إيماءة مباشرة من المستخدم.
  const toast=showInAppToast(title,body);
  if(target && toast){toast.style.cursor="pointer";toast.onclick=()=>openNotificationTarget(target);}
  if("Notification" in window && Notification.permission==="granted"){
    try{const notice=new Notification(title,{body,icon:"/logo-premium.png"});if(target)notice.onclick=()=>{window.focus();openNotificationTarget(target);notice.close();};}catch{}
  }
}

function wsUrl(){
  const p=location.protocol==="https:"?"wss":"ws";
  return `${p}://${location.host}/ws?token=${encodeURIComponent(token)}`;
}



const CALL_SIGNAL_TYPES=new Set([
  "call-request",
  "call-ringing",
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
  updateCallPresentation(state);
}

function clearOutgoingCallTimer(){
  if(outgoingCallTimer){
    clearTimeout(outgoingCallTimer);
    outgoingCallTimer=null;
  }
}

function resetCallState(){
  clearOutgoingCallTimer();
  stopCallSounds();
  remoteRinging=false;
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

async function waitForIceGatheringComplete(peer,timeoutMs=1500){
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
    if(callState!=="idle" || globalThis.AttaWatchRoom?.active){
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

    setIncomingCallText(incomingFrom);
    setCallPeerIdentity(incomingFrom);
    $("incomingModal").classList.remove("hidden");
    playIncomingRing();
    // لا تؤكد الاستلام إلا بعد ظهور مربع المكالمة الواردة.
    // يجب ألا يسمع المتصل نغمة انتظار لمجرد إرسال الطلب.
    sendCallSignal({
      type:"call-ringing", to:msg.from, callId:msgCallId,
      callType:currentCallType
    }).catch(err=>console.warn("call ringing acknowledgement failed",err));
    notify("مكالمة واردة",`${incomingFrom} يتصل بك`);
    return true;
  }

  if(!callIdMatches({...msg,callId:msgCallId})){
    return true;
  }

  if(msg.type==="call-ringing"){
    // لا ينتقل الاتصال من «جارٍ الاتصال» إلى «يرن» إلا بتأكيد المتصل المقصود.
    if((callState==="outgoing" || callState==="preparing") &&
       msg.from===activeCallPeer && activeCallId===msgCallId){
      remoteRinging=true;
      $("callPeerState").textContent="يرن الآن...";
      $("callStatusText").textContent="يرن الآن...";
      if(activeRingType!=="outgoing")playOutgoingRing();
    }
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
    // استخدم دائمًا البديل المتوافق القائم على تخزين الرسائل. يعمل مع
    // الخادم المنشور حاليًا ولا يتطلب /api/call-signals.
    await pollStoredCallSignals();
  }finally{
    callSignalPollBusy=false;
  }
}
function startCallSignalPolling(){
  clearInterval(callSignalPollTimer);

  // افحص فورًا أولًا، ثم تابع الفحص بوتيرة تكفي لمتابعة الرنين.
  pollCallSignals();
  callSignalPollTimer=setInterval(pollCallSignals,2000);
}

function connect(){
  clearTimeout(reconnectTimer);
  ws=new WebSocket(wsUrl());
  $("connectionState").textContent="جاري الاتصال...";

  ws.onopen=()=>{$("connectionState").textContent="متصل"};
  ws.onclose=()=>{
    if(!token){
      $("connectionState").textContent="غير متصل";
      return;
    }

    $("connectionState").textContent="إعادة الاتصال...";
    reconnectTimer=setTimeout(connect,1500);
  };

  ws.onmessage=async e=>{
    const msg=JSON.parse(e.data);

    if(["account-reset","account-disabled","account-deleted"].includes(msg.type)){await logoutCurrentAccount();location.reload();return;}
    if(CALL_SIGNAL_TYPES.has(msg.type)){
      await handleCallSignal(msg);
      return;
    }

    if(msg.type==="joined"){
      me={...me,...msg.user};
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

    if(msg.type==="chat-deleted"){
      if(selectedUser===msg.peer && !selectedGroup)await loadConversation(selectedUser,{silent:true}).catch(()=>{});
      await loadChatContacts().catch(()=>{});
      return;
    }

    if(msg.type==="new-post"){
      notify("منشور جديد",`نشر ${msg.fromName||"مستخدم"} منشورًا جديدًا`,msg);
      await loadNotifications().catch(()=>{});
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
      setIncomingCallText(incomingFrom);
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
      // يُستخدم هذا الحدث الآن للمكالمات الحية فقط.
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
  const ch=esc(Array.from(name||"A")[0].toUpperCase());
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="#191307"/><text x="50" y="64" font-size="48" text-anchor="middle" fill="#f6d776">${ch}</text></svg>`)}`;
}

function renderProfile(){
  if(!me)return;
  $("meDisplay").textContent=me.displayName||me.username;
  $("meUsername").textContent="@"+me.username;
  $("avatarImg").src=me.avatar||avatarFallback(me.displayName||me.username);
  if($("headerAvatar"))$("headerAvatar").src="logo-premium.png?v=14.12";
  if($("headerProfileName"))$("headerProfileName").textContent="AL-ATTA";
  if($("headerProfileUsername"))$("headerProfileUsername").textContent="@"+me.username;
  if($("homeAvatar"))$("homeAvatar").src=me.avatar||avatarFallback(me.fullName||me.displayName||me.username);
  if($("profileAvatarLarge") && viewedProfileUsername===me.username)$("profileAvatarLarge").src=me.avatar||avatarFallback(me.fullName||me.displayName||me.username);
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

function lastActivityText(user){
  if(user?.isOnline)return "نشط الآن";
  const last=Number(user?.lastSeen||0);
  if(!last)return "غير متصل";
  const mins=Math.max(0,Math.floor((Date.now()-last)/60000));
  if(mins<1)return "نشط منذ لحظات";
  if(mins<60)return `نشط منذ ${mins} دقيقة`;
  if(mins<1440)return `نشط منذ ${Math.floor(mins/60)} ساعة`;
  return `نشط منذ ${Math.floor(mins/1440)} يوم`;
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
              <small>${lastActivityText(u)}</small>
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
function callEventLabel(msg){
  const base=msg.callType==='video'?'مكالمة فيديو':'مكالمة صوتية';
  const status=msg.callStatus;
  const outgoing=msg.from===me?.username;
  const state=status==='completed'||status==='answered'?'انتهت المكالمة':
    status==='rejected'?'تم رفض المكالمة':
    status==='busy'?'المستخدم مشغول':
    status==='missed'||status==='cancelled'||
      (status==='ringing'&&Date.now()-Number(msg.ts||0)>55000)?
      outgoing?'لم يتم الرد':'مكالمة فائتة':'اتصال وارد';
  return base+' • '+state;
}

function addRichMessage(msg){
  if(msg?.mediaType==="call-signal" || msg?.deletedForEveryone)return;
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
    ${msg.mediaType==='call-event'?`<div>📞 ${esc(callEventLabel(msg))}</div>`:
      (msg.text?`<div>${esc(msg.text)}</div>`:"")}
    ${media}
    ${mine?`<div class="message-read-state">${state}</div>`:""}
  `;

  if(msg.mediaType==='call-event'){
    const peer=msg.from===me?.username?msg.to:msg.from;
    div.classList.add('chat-call-history');div.tabIndex=0;div.setAttribute('role','button');
    div.setAttribute('aria-label',callEventLabel(msg)+'؛ إعادة الاتصال');
    const redial=()=>{if(peer&&peer===selectedUser)requestCall(msg.callType==='video'?'video':'audio');};
    div.onclick=redial;div.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();redial();}};
  }
  if(msg.text){
    const copy=document.createElement('button');copy.className='message-copy';copy.type='button';copy.textContent='نسخ';
    copy.onclick=e=>{e.stopPropagation();copyPrivateMessage(msg.text,copy);};div.append(copy);
    const code=privateRoomInviteCode(msg.text);
    if(code){const button=document.createElement('button');button.type='button';button.className='message-copy';button.textContent='نسخ كود الغرفة';
      button.onclick=e=>{e.stopPropagation();copyPrivateMessage(code,button);};div.append(button);}
  }

  $("messages").appendChild(div);
  $("messages").scrollTop=$("messages").scrollHeight;
}

function privateRoomInviteCode(text){return String(text||'').match(/(?:الكود|كود الغرفة|room code)\s*[:：]\s*([A-Za-z]{3})(?![A-Za-z0-9])/i)?.[1]?.toUpperCase()||'';}
async function copyPrivateMessage(text,button){
  try{await navigator.clipboard.writeText(text);const previous=button.textContent;button.textContent='تم النسخ';setTimeout(()=>{if(button.isConnected)button.textContent=previous;},1200);}
  catch{alert('تعذر النسخ؛ حدّد نص الرسالة وانسخه من قائمة الهاتف');}
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
  if(!token || !me || callState!=="idle" || pc)return;

  await Promise.allSettled([
    loadChatContacts(),
    loadFriendRequests(),
    selectedUser && !selectedGroup ? loadConversation(selectedUser,{silent:true}) : Promise.resolve(),
    loadNotifications()
  ]);
}

function startSocialSync(){
  clearInterval(syncTimer);
  syncTimer=setInterval(syncSocialState,8000);
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
          width:{ideal:1280,max:1280},
          height:{ideal:720,max:720},
          frameRate:{ideal:24,max:24}
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

  // جهّز بيانات اعتماد الترحيل والوسائط المحلية بالتوازي لتقليل زمن الإعداد.
  const [turnOk,stream]=await Promise.all([
    ensureTurnIceServers(),
    getMedia(currentCallType)
  ]);

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

    // حافظ على استجابة المكالمات على الهواتف والاتصالات الأبطأ.
    try{
      const params=sender.getParameters();
      params.encodings=params.encodings?.length?params.encodings:[{}];
      if(track.kind==="video"){
        params.encodings[0].maxBitrate=1400000;
        params.encodings[0].maxFramerate=24;
        params.degradationPreference="balanced";
      }else{
        params.encodings[0].maxBitrate=48000;
      }
      await sender.setParameters(params);
    }catch(err){
      console.warn("media sender tuning skipped",err);
    }
  }

  // ننتظر اكتمال ICE في offer/answer؛ لذلك يظل ICE المتدرج ميزة إضافية فقط.
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
    const remoteAudio=$("remoteAudio");

    // أبقِ صوت الطرف الآخر على عنصر واحد فقط. أدى استخدام الدفق نفسه في
    // <video> و<audio> إلى تكرار الصوت أو كتمه في بعض إصدارات Chrome والهواتف.
    remoteVideo.srcObject=remoteMediaStream;
    remoteVideo.playsInline=true;
    remoteVideo.autoplay=true;
    remoteVideo.muted=true;

    if(remoteAudio){
      const remoteAudioTracks=remoteMediaStream.getAudioTracks();
      remoteAudio.srcObject=remoteAudioTracks.length?new MediaStream(remoteAudioTracks):null;
      remoteAudio.autoplay=true;
      remoteAudio.volume=1;
      remoteAudio.muted=false;
    }

    const revealVideo=()=>{
      if(e.track.kind==="video"){
        const empty=document.querySelector(".remote-empty-state");
        if(empty)empty.style.display="none";
        $("callOverlay")?.classList.add("video-established");
        $("callPeerState").textContent="الفيديو متصل";
        $("callStatusText").textContent="مكالمة فيديو جارية";
      }
    };

    e.track.onunmute=async()=>{
      revealVideo();
      try{await remoteVideo.play()}catch{}
      try{await remoteAudio?.play()}catch{}
    };

    revealVideo();

    const playback=[];
    if(e.track.kind==="video")playback.push(remoteVideo.play());
    if(remoteAudio?.srcObject)playback.push(remoteAudio.play());

    const playbackResults=await Promise.allSettled(playback);
    const blocked=playbackResults.some(r=>r.status==="rejected");
    if(blocked){
      console.warn("remote autoplay blocked",playbackResults);
      $("resumeRemoteMediaBtn")?.classList.remove("hidden");
    }else{
      $("resumeRemoteMediaBtn")?.classList.add("hidden");
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
  if(window.AttaWatchRoom?.active)return alert("غادر غرفة المشاهدة الحالية أولًا");
  if(!selectedUser)return alert("اختر مستخدمًا أولًا");

  if(callState!=="idle"){
    return alert("في مكالمة أو اتصال جاري بالفعل.");
  }

  currentCallType=type;
  const peer=selectedUser;
  const callId=crypto.randomUUID();

  setCallState("preparing",peer,callId);
  remoteRinging=false;

  try{
    // يمكن تشغيل إذن الميكروفون والكاميرا وإعداد TURN بالتوازي.
    const [turnOk]=await Promise.all([
      ensureTurnIceServers(),
      getMedia(type)
    ]);

    const empty=document.querySelector(".remote-empty-state");
    if(empty)empty.style.display="grid";

    showCallOverlay(
      peer,
      turnOk?"TURN جاهز — جاري الاتصال...":"جاري الاتصال..."
    );

    // تنتظر نغمة الاتصال تأكيد call-ringing من واجهة المستلم.
    setCallState("outgoing",peer,callId);

    const result=await sendCallSignal({
      type:"call-request",
      to:peer,
      callId,
      callType:type
    });

    // تجنب سباق تأكيد الرنين السريع الذي يصل أثناء انتظار إرسال الطلب.
    const stateText=remoteRinging
      ?"يرن الآن..."
      :"تم إرسال الاتصال — في انتظار الطرف الآخر...";
    $("callPeerState").textContent=stateText;
    $("callStatusText").textContent=stateText;

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
  if($("remoteAudio"))$("remoteAudio").srcObject=null;
  $("resumeRemoteMediaBtn")?.classList.add("hidden");
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
let postVideoData="";
let postVideoUrl="";
let postVideoUploading=false;

const advancedReactions=["love","haha","wow","sad","angry"];
const reactionEmoji={like:"👍",love:"❤️",haha:"😂",wow:"😮",sad:"😢",angry:"😡",dislike:"👎"};



let callAudioCtx=null;
let ringtoneTimer=null;
// أعد الضبط لكل محاولة اتصال صادرة؛ فالتسليم لا يثبت أن الطرف الآخر يرن.
let remoteRinging=false;
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
  ringtoneTimer=setInterval(playIncomingPattern,2450);

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
  ringtoneTimer=setInterval(playOutgoingPattern,2800);

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

let callConnectedAt=0,callDisplayTimer=null;
function updateCallPresentation(state){
  const overlay=$("callOverlay");
  if(state==="connected" && !callConnectedAt){
    callConnectedAt=Date.now();
    callDisplayTimer=setInterval(()=>{
      if(currentCallType!=="audio")return;
      const seconds=Math.floor((Date.now()-callConnectedAt)/1000);
      $("callPeerState").textContent=`${String(Math.floor(seconds/60)).padStart(2,"0")}:${String(seconds%60).padStart(2,"0")}`;
    },1000);
  }
  if(state==="idle"){
    clearInterval(callDisplayTimer);callDisplayTimer=null;callConnectedAt=0;
    overlay?.classList.remove("video-established");
    for(const id of ["muteBtn","cameraBtn","speakerBtn"]){$(id)?.classList.remove("is-off");$(id)?.setAttribute("aria-pressed","false")}
    setRoundCallButton("muteBtn","mic","الميكروفون");setRoundCallButton("cameraBtn","video","الكاميرا");
  }
  if(currentCallType==="video" && callConnectedAt)overlay?.classList.add("video-established");
}

let callOverlayOpen=false;


async function setIncomingCallText(peer){
  const update=user=>{if(incomingFrom===peer)$("incomingText").textContent=`${user?.fullName||user?.displayName||"جهة اتصال"} يتصل بك`;};
  update(users.find(u=>u.username===peer));
  try{const d=await api(`/api/profile-public?token=${encodeURIComponent(token)}&username=${encodeURIComponent(peer)}`);update(d.user)}catch{}
}

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
  const audioOnly=currentCallType==="audio";
  $("callOverlay")?.classList.toggle("audio-only-call",audioOnly);
  $("cameraBtn")?.classList.toggle("hidden",audioOnly);
  $("localVideo")?.closest(".local-pip")?.classList.toggle("hidden",audioOnly);
  updateCallPresentation(callState);
  $("callTypeLabel").textContent=audioOnly?"مكالمة صوتية":"مكالمة فيديو";
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




function adminEscape(v){return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));}
function adminDate(v){try{return new Date(Number(v)||0).toLocaleString("ar-SA")}catch{return ""}}
function adminSecret(){
  return new Promise(resolve=>{
    const dialog=document.createElement("dialog");dialog.className="admin-secret-dialog";
    dialog.innerHTML=`<form><h3>كلمة حماية الإدارة</h3><p>كلمة مستقلة خاصة بالمدير الرئيسي.</p><label>كلمة الحماية<input type="password" maxlength="128" autocomplete="off" required></label><div><button type="submit">تأكيد</button><button type="button" data-cancel>إلغاء</button></div></form>`;
    const input=dialog.querySelector("input"),form=dialog.querySelector("form");
    const finish=value=>{input.value="";dialog.close();dialog.remove();resolve(value);};
    dialog.addEventListener("cancel",e=>{e.preventDefault();finish(null)});
    dialog.querySelector("[data-cancel]").onclick=()=>finish(null);
    form.onsubmit=e=>{e.preventDefault();if(input.value)finish(input.value)};
    document.body.append(dialog);dialog.showModal();input.focus();
  });
}
async function loadAdminDashboard(){
  if(!["super_admin","moderator"].includes(me?.role))return;
  try{
    const d=await api(`/api/admin/summary?token=${encodeURIComponent(token)}`);
    me.role=d.role;
    document.querySelectorAll("[data-owner-only]").forEach(el=>el.classList.toggle("hidden",d.role!=="super_admin"));
    $("adminSecurityStatus").textContent=d.securityConfigured?"🔒 حذف الحسابات يتطلب كلمة الحماية كل مرة":"🔒 اضبط كلمة مرور حذف الحسابات أولًا";
    const fields={adminUsersCount:"users",adminPostsCount:"posts",adminReportsCount:"reports",adminBannedCount:"banned",adminDisabledCount:"disabled",adminActiveCount:"active",adminOnlineCount:"online",adminUsersTodayCount:"usersToday",adminPostsTodayCount:"postsToday",adminUsersWeekCount:"users7Days",adminPostsWeekCount:"posts7Days",adminArchivedCount:"archivedPosts"};
    for(const [id,key] of Object.entries(fields))if($(id))$(id).textContent=d.counts[key]||0;
    const maximum=Math.max(1,...(d.daily||[]).flatMap(day=>[day.users,day.posts]));
    $("adminActivityChart").innerHTML=(d.daily||[]).map(day=>`<div class="activity-row"><time>${adminEscape(day.date.slice(5))}</time><div class="activity-bars"><div class="activity-track"><span style="width:${day.users/maximum*100}%"></span></div><div class="activity-track posts"><span style="width:${day.posts/maximum*100}%"></span></div></div><span>${day.users} / ${day.posts}</span></div>`).join("");
    const resume=$("adminDeleteResume"),canDelete=d.role==="super_admin";
    resume.classList.toggle("hidden",!d.deletion || !canDelete);
    resume.innerHTML=d.deletion&&canDelete?`حذف قيد الاستكمال: @${adminEscape(d.deletion.username)} <button class="danger" data-admin-action="delete_user" data-user="${adminEscape(d.deletion.username)}">استكمال الحذف</button>`:"";
    $("adminRoleBadge").textContent=canDelete?"👑 المدير الرئيسي":"مشرف • دون صلاحية حذف";
    await loadAdminUsers();
  }catch(e){alert(e.message||"تعذر تحميل لوحة الإدارة")}
}
$("adminSecurityCodeBtn").onclick=async()=>{
  try{const d=await api("/api/admin/security/code",{method:"POST",body:JSON.stringify({token})});$("adminSecurityStatus").textContent=`أُرسل الكود إلى ${d.email}`;}catch(e){alert(e.message)}
};
$("adminSecurityForm").onsubmit=async e=>{
  e.preventDefault();const form=e.target,button=form.querySelector("button");button.disabled=true;
  const body={token,code:form.elements.code.value,currentPassword:form.elements.currentPassword.value,newPassword:form.elements.newPassword.value,confirmPassword:form.elements.confirmPassword.value};
  form.elements.newPassword.value="";form.elements.confirmPassword.value="";form.elements.currentPassword.value="";
  try{await api("/api/admin/security/password",{method:"POST",body:JSON.stringify(body)});form.reset();$("adminSecurityStatus").textContent="تم حفظ كلمة الحماية";await loadAdminDashboard();}catch(err){alert(err.message)}finally{body.newPassword="";body.confirmPassword="";body.currentPassword="";button.disabled=false;}
};
let ownerResetPlan=null;
const resetPreviewButton=$("adminResetPreviewBtn"),resetExecuteButton=$("adminResetExecuteBtn"),resetConfirmation=$("adminResetConfirmation");
$("adminResetScope").onchange=()=>{ownerResetPlan=null;$("adminResetConfirmArea").classList.add("hidden");};
resetPreviewButton.onclick=async()=>{
  if(me?.role!=="super_admin")return;
  resetPreviewButton.disabled=true;
  try{
    ownerResetPlan=await api("/api/admin/reset/plan",{method:"POST",body:JSON.stringify({token,scope:$("adminResetScope").value})});
    const scope=ownerResetPlan.scope;
    $("adminResetPreviewReport").textContent=`حسابات للحذف: ${scope.accountsToDelete} • منشورات للحذف: ${scope.postsToDelete} • حساب المدير محفوظ`;
    $("adminResetPhrase").textContent=`اكتب: ${ownerResetPlan.confirmation}`;
    $("adminResetConfirmArea").classList.remove("hidden");resetConfirmation.value="";resetExecuteButton.disabled=true;
  }catch(e){alert(e.message)}finally{resetPreviewButton.disabled=false;}
};
resetConfirmation.oninput=()=>{resetExecuteButton.disabled=!ownerResetPlan || resetConfirmation.value!==ownerResetPlan.confirmation;};
resetExecuteButton.onclick=async()=>{
  if(!ownerResetPlan || resetConfirmation.value!==ownerResetPlan.confirmation)return;
  let adminPassword=ownerResetPlan.scope.kind==="accounts"?await adminSecret():"";if(adminPassword===null)return;
  const report=$("adminResetPreviewReport");resetExecuteButton.disabled=true;resetPreviewButton.disabled=true;resetConfirmation.disabled=true;
  try{
    let result;
    do{result=await api("/api/admin/reset/execute",{method:"POST",body:JSON.stringify({token,planId:ownerResetPlan.planId,confirmation:ownerResetPlan.confirmation,adminPassword})});report.textContent=`تمت معالجة ${result.completed||0} • حساب المدير محفوظ`;}while(!result.done);
    ownerResetPlan=null;$("adminResetConfirmArea").classList.add("hidden");await loadAdminDashboard();
  }catch(e){report.textContent=e.message+" — يمكنك استكمال الحذف من لوحة الإدارة";resetExecuteButton.disabled=false;}
  finally{adminPassword="";resetPreviewButton.disabled=false;resetConfirmation.disabled=false;}
};
let adminUsersNextOffset=null;
async function loadAdminUsers(append=false){
  const q=$("adminUserSearch").value.trim(),canDelete=me?.role==="super_admin";
  const d=await api(`/api/admin/users?token=${encodeURIComponent(token)}&q=${encodeURIComponent(q)}&offset=${append?adminUsersNextOffset||0:0}`);
  const markup=d.users.map(u=>{
    const owner=u.role==="super_admin",moderator=u.role==="moderator",status=u.banned?"محظور":u.disabled?"معطّل":moderator?"مشرف":"مفعّل",username=adminEscape(u.username);
    const action=(name,label)=>`<button type="button" data-admin-action="${name}" data-user="${username}" data-name="${adminEscape(u.displayName||u.username)}">${label}</button>`;
    let actions="";
    if(!owner && (canDelete || !moderator))actions+=u.banned||u.disabled?action("activate","إعادة تفعيل"):action("suspend","تعطيل")+action("ban","حظر");
    if(canDelete && !owner)actions+=action("update_user","تعديل الاسم")+action(moderator?"remove_moderator":"make_moderator",moderator?"إزالة الإشراف":"＋ إضافة مشرف")+action("delete_user","🔒 حذف نهائي");
    return `<div class="admin-item"><div class="admin-item-main"><img alt="" src="${adminEscape(u.avatar||avatarFallback(u.displayName||u.username))}"><div class="admin-item-text"><strong>${adminEscape(u.displayName||u.username)}</strong><span>@${username} · ${owner?'مدير رئيسي • محمي':status}</span><span>${adminDate(u.createdAt)}</span></div></div><div class="admin-actions">${actions}</div></div>`;
  }).join("");
  if(append)$("adminUsersList").insertAdjacentHTML("beforeend",markup);else $("adminUsersList").innerHTML=markup||'<div class="admin-empty">لا يوجد مستخدمون</div>';
  adminUsersNextOffset=d.nextOffset;$("adminMoreUsersBtn").classList.toggle("hidden",d.nextOffset===null);
}
$("adminRefreshBtn").onclick=loadAdminDashboard;
$("adminUserSearchBtn").onclick=()=>loadAdminUsers().catch(e=>alert(e.message));
$("adminMoreUsersBtn").onclick=()=>loadAdminUsers(true).catch(e=>alert(e.message));
$("adminPostSearchBtn").onclick=()=>loadAdminPosts().catch(e=>alert(e.message));
async function loadAdminPosts(){
  const d=await api(`/api/admin/posts?token=${encodeURIComponent(token)}&q=${encodeURIComponent($("adminPostSearch").value.trim())}`),owner=me?.role==="super_admin";
  $("adminPostsList").innerHTML=d.posts.map(p=>`<div class="admin-item"><div class="admin-item-text"><strong>@${adminEscape(p.author)}</strong><p class="admin-post-text">${adminEscape(p.text||'منشور وسائط')}</p><span>${adminDate(p.createdAt)}</span></div><div class="admin-actions">${owner?`<button data-admin-action="update_post" data-post="${adminEscape(p.id)}" data-text="${adminEscape(p.text)}">تعديل المنشور</button><button data-admin-action="delete_post" data-post="${adminEscape(p.id)}">🔒 حذف المنشور</button>`:''}</div></div>`).join("")||'<div class="admin-empty">لا توجد منشورات</div>';
}
async function loadAdminReports(){
  const d=await api(`/api/admin/reports?token=${encodeURIComponent(token)}`);
  $("adminReportsList").innerHTML=d.reports.map(r=>`<div class="admin-item"><div class="admin-item-text"><strong>بلاغ من @${adminEscape(r.reporter)}</strong><span>${adminEscape(r.targetType)} · ${adminEscape(r.reason||'بدون سبب')}</span><span>${adminDate(r.createdAt)} ${r.resolved?'· تمت المراجعة':''}</span></div><div class="admin-actions">${r.resolved?'':`<button data-admin-action="resolve_report" data-report="${adminEscape(r.id)}">تمت المراجعة</button>`}</div></div>`).join("")||'<div class="admin-empty">لا توجد بلاغات</div>';
}
document.addEventListener("click",async e=>{
  const tab=e.target.closest("[data-admin-tab]");
  if(tab){
    document.querySelectorAll("[data-admin-tab]").forEach(x=>x.classList.toggle("active",x===tab));
    const name=tab.dataset.adminTab;["users","posts","reports"].forEach(x=>$("admin"+x[0].toUpperCase()+x.slice(1)+"Panel").classList.toggle("hidden",x!==name));
    try{if(name==="users")await loadAdminUsers();if(name==="posts")await loadAdminPosts();if(name==="reports")await loadAdminReports();}catch(err){alert(err.message)}return;
  }
  const btn=e.target.closest("[data-admin-action]");if(!btn)return;
  const action=btn.dataset.adminAction,body={token,action,username:btn.dataset.user||"",postId:btn.dataset.post||"",reportId:btn.dataset.report||""};
  if(["ban","suspend","delete_post","make_moderator","remove_moderator"].includes(action) && !confirm("تأكيد تنفيذ الإجراء على العنصر المحدد؟"))return;
  if(action==="delete_user"){if(!confirm(`سيُحذف حساب @${body.username} مع بياناته نهائيًا. هل تريد المتابعة؟`))return;body.confirmation=body.username;}
  if(action==="update_user"){body.displayName=prompt("الاسم الجديد للحساب:",btn.dataset.name);if(body.displayName===null)return;}
  if(action==="update_post"){body.text=prompt("نص المنشور الجديد:",btn.dataset.text);if(body.text===null)return;}
  if(action==="delete_user"){body.adminPassword=await adminSecret();if(body.adminPassword===null)return;}
  btn.disabled=true;
  try{
    if(action==="delete_user"){let result;do{result=await api("/api/admin/users/delete",{method:"POST",body:JSON.stringify(body)});btn.textContent=`جاري الحذف... ${result.deletedMedia||0}`;}while(!result.done);}
    else await api("/api/admin/action",{method:"POST",body:JSON.stringify(body)});
    await loadAdminDashboard();const active=document.querySelector("[data-admin-tab].active")?.dataset.adminTab;
    if(active==="posts")await loadAdminPosts();if(active==="reports")await loadAdminReports();
  }catch(err){alert(err.message)}finally{body.adminPassword="";btn.disabled=false;}
});

function showPage(pageId){
  if(pageId!=="homePage")notificationPostTarget="";
  // واجهة المالك فقط. ويرفض Cloudflare بشكل مستقل واجهات الإدارة غير المصرح بها.
  if(pageId==="adminPage" && !["super_admin","moderator"].includes(me?.role))return false;
  const page=$(pageId);
  if(!page || !page.classList.contains("page"))return false;
  document.querySelectorAll(".page").forEach(p=>p.classList.remove("active-page"));
  $(pageId).classList.add("active-page");
  $("appView").dataset.page=pageId;
  document.querySelectorAll(".nav-btn").forEach(b=>b.classList.toggle("active",b.dataset.page===pageId));
  document.querySelectorAll(".rail-btn").forEach(b=>b.classList.toggle("active",b.dataset.page===pageId));

  document.querySelectorAll("[data-top-page]").forEach(b=>b.setAttribute("aria-current",b.dataset.topPage===pageId?"page":"false"));
  document.dispatchEvent(new CustomEvent("atta:page",{detail:{pageId}}));
  if(pageId==="homePage")loadPosts();
  if(pageId==="friendsPage"){ loadFriendRequests().catch(()=>{}); if(me?.username)loadFriends(me.username).catch(()=>{}); }
  if(pageId==="adminPage")loadAdminDashboard();
  if(pageId==="notificationsPage")loadNotifications();
  if(pageId==="profilePage"){
    if(!viewedProfileUsername)viewedProfileUsername=me?.username||"";
    renderProfilePage(viewedProfileUsername);
  }
  if(pageId==="searchPage")resetExploreSearch();
  if(pageId==="savedPage")loadSavedPostsPage();
  if(pageId==="messagesPage"){
    loadStories();
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

if($("mobileHeaderSearchBtn")){
  $("mobileHeaderSearchBtn").onclick=()=>{
    showPage("searchPage");
    setTimeout(()=>$("userSearchInput")?.focus(),50);
  };
}


let logoutInProgress=false;

async function logoutCurrentAccount(){
  await window.AttaWatchRoom?.leave();
  if(logoutInProgress)return;
  logoutInProgress=true;

  const currentToken=token;

  try{
    if(callState!=="idle" || pc || localStream){
      await hangup(false).catch(()=>{});
    }

    if(currentToken){
      await api("/api/logout",{
        method:"POST",
        body:JSON.stringify({token:currentToken})
      }).catch(()=>{});
    }
  }finally{
    token="";resetPostMedia();
    me=null;
    viewedProfileUsername="";
    viewedFriendState="none";
    selectedUser="";
    selectedGroup=null;
    users=[];
    groups=[];
    chatFriends=[];
    appSessionStarted=false;

    clearSavedSession();

    clearTimeout(reconnectTimer);
    reconnectTimer=null;
    clearInterval(syncTimer);
    syncTimer=null;
    clearInterval(callSignalPollTimer);
    callSignalPollTimer=null;
    clearInterval(verificationCountdownTimer);
    verificationCountdownTimer=null;

    try{
      if(ws){
        ws.onclose=null;
        ws.close();
      }
    }catch{}
    ws=null;

    resetMessagesInbox();

    document.body.classList.remove("chat-fullscreen-open");
    $("appView").classList.add("hidden");
    $("authView").classList.remove("hidden");
    $("moreMenuPanel")?.classList.add("hidden");

    authMode="login";
    pendingVerificationId="";

    $("authMainFields").classList.remove("hidden");
    $("verificationFields").classList.add("hidden");
    $("registerFields").classList.add("hidden");
    $("confirmPasswordInput").classList.add("hidden");

    $("loginTab").disabled=false;
    $("registerTab").disabled=false;
    $("loginTab").classList.add("active");
    $("registerTab").classList.remove("active");

    $("usernameInput").value="";
    $("passwordInput").value="";
    $("confirmPasswordInput").value="";
    $("verificationCodeInput").value="";

    $("authBtn").disabled=false;
    $("authBtn").textContent="تسجيل الدخول";
    $("authMsg").textContent="تم تسجيل الخروج بنجاح.";

    if($("attaRegisterShortcut")){
      $("attaRegisterShortcut").classList.remove("hidden");
    }

    logoutInProgress=false;
  }
}

function bindLogoutButton(id){
  const btn=$(id);
  if(!btn || btn.dataset.logoutBound==="1")return;

  btn.dataset.logoutBound="1";

  btn.addEventListener("click",async e=>{
    e.preventDefault();
    e.stopPropagation();

    if(!confirm("هل تريد تسجيل الخروج من تواصل العطا؟"))return;
    await logoutCurrentAccount();
  });
}


document.querySelectorAll(".rail-btn:not(#logoutBtn)").forEach(btn=>{
  btn.onclick=()=>{
    document.querySelectorAll(".rail-btn").forEach(x=>x.classList.remove("active"));
    btn.classList.add("active");
    if(!btn.dataset.page)return;
    if(btn.dataset.page==="profilePage")viewedProfileUsername=me?.username||"";
    showPage(btn.dataset.page);
  };
});

if($("railStoryBtn")){
  $("railStoryBtn").onclick=()=>$("addStoryBtn")?.click();
}



if($("brandProfileBtn")){
  $("brandProfileBtn").onclick=()=>{
    showPage("homePage");
  };
}

document.querySelectorAll("[data-top-page]").forEach(btn=>{
  btn.onclick=()=>showPage(btn.dataset.topPage);
});

if($("headerFriendsBtn")){
  $("headerFriendsBtn").onclick=()=>showPage("friendsPage");
}
if($("friendsFindPeopleBtn")){
  $("friendsFindPeopleBtn").onclick=()=>{
    showPage("searchPage");
    setTimeout(()=>$("userSearchInput")?.focus(),80);
  };
}

if($("sideAddStoryBtn"))$("sideAddStoryBtn").onclick=()=>$("addStoryBtn")?.click();


if($("railSavedBtn"))$("railSavedBtn").onclick=()=>showPage("savedPage");
if($("railMoreBtn"))$("railMoreBtn").onclick=()=>showPage("morePage");

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
  $("postVideoBtn").onclick=()=>$("postVideoInput")?.click();
}

if($("postVideoInput")){
  $("postVideoInput").onchange=async e=>{
    const f=e.target.files?.[0];
    if(!f)return;
    const preview=$("postVideoPreview");
    const wrap=$("postVideoPreviewWrap");
    const publishBtn=$("publishPostBtn");
    postVideoData="";
    postVideoUrl="";
    wrap?.classList.add("hidden");
    preview?.removeAttribute("src");
    if(!String(f.type||"").startsWith("video/")){
      showInAppToast("ملف غير صالح","اختر ملف فيديو مدعومًا.");
      e.target.value="";
      return;
    }
    if(f.size>50*1024*1024){
      showInAppToast("الفيديو كبير","الحد الأقصى للفيديو 50 MB.");
      e.target.value="";
      return;
    }
    if(postVideoUploading)return;
    postVideoUploading=true;
    if(publishBtn)publishBtn.disabled=true;
    showInAppToast("جاري رفع الفيديو","انتظر حتى يكتمل الرفع قبل النشر.");
    try{
      const up=await fetch(`/api/media-upload?token=${encodeURIComponent(token)}&kind=video&name=${encodeURIComponent(f.name||"video.mp4")}`,{
        method:"POST",
        headers:{"content-type":f.type||"application/octet-stream"},
        body:f
      });
      const d=await up.json().catch(()=>({}));
      if(!up.ok || !d.url){
        if(d.error==="MEDIA_BUCKET_NOT_CONFIGURED")throw new Error("مخزن R2 غير مربوط بالتطبيق. تأكد من MEDIA_BUCKET ثم أعد النشر.");
        throw new Error(d.error||`فشل رفع الفيديو (HTTP ${up.status})`);
      }
      postVideoUrl=String(d.url);
      if(preview)preview.src=postVideoUrl;
      wrap?.classList.remove("hidden");
      showInAppToast("تم رفع الفيديو","الفيديو جاهز للنشر.");
    }catch(err){
      console.error("Video upload failed",err);
      // احتفظ ببديل الفيديو المصغّر الحالي فقط عند تعذر R2.
      if(f.size<=1100000){
        try{
          const data=await new Promise((resolve,reject)=>{
            const reader=new FileReader();
            reader.onload=()=>resolve(String(reader.result||""));
            reader.onerror=()=>reject(new Error("تعذر قراءة الفيديو"));
            reader.readAsDataURL(f);
          });
          postVideoData=data;
          if(preview)preview.src=data;
          wrap?.classList.remove("hidden");
          showInAppToast("تم تجهيز فيديو صغير","سيتم نشره بالطريقة القديمة.");
        }catch(readErr){showInAppToast("تعذر تجهيز الفيديو",readErr.message);}
      }else{
        showInAppToast("تعذر رفع الفيديو",err?.message||"حاول مرة أخرى.");
        e.target.value="";
      }
    }finally{
      postVideoUploading=false;
      if(publishBtn)publishBtn.disabled=false;
    }
  };
}

document.querySelectorAll(".nav-btn").forEach(btn=>{
  btn.onclick=()=>{
    if(!btn.dataset.page)return;
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
    $("profileUsername").textContent=""; $("profileUsername").hidden=true; $("profileUsername").style.display="none";
    $("profileBio").textContent=p.bio||"لا توجد نبذة بعد";
    const coverImg=$("profileCoverImage");
    if(coverImg){
      if(p.cover){ coverImg.src=p.cover; coverImg.classList.remove("hidden"); }
      else{ coverImg.removeAttribute("src"); coverImg.classList.add("hidden"); }
    }
    $("friendsCount").textContent=d.friendCount||0;

    $("friendActionBtn").classList.toggle("hidden", self || d.blockRestricted || (d.friendState==="none" && p.allowFriendRequests===false));
    $("messageProfileBtn").classList.toggle("hidden", self || d.blockRestricted || d.friendState!=="friends");
    $("myProfileBtn").classList.toggle("hidden",self);
    $("profileEditArea").classList.add("hidden");
    $("toggleProfileEditBtn").classList.toggle("hidden",!self);
    $("profileAvatarEditBtn")?.classList.toggle("hidden",!self);
    $("profileCoverEditBtn")?.classList.toggle("hidden",!self);
    $("privateInfoCard").classList.toggle("hidden",!self);
    $("ownAccountActions")?.classList.toggle("hidden",!self);
    $("friendRequestsSection").classList.toggle("hidden",!self);

    if(self){
      const mine=await api(`/api/me?token=${encodeURIComponent(token)}`);
      me={...me,...mine.user};
      $("bioInput").value=me.bio||"";
      $("privateAccountInput").checked=!!me.accountPrivate;
      $("allowFriendRequestsInput").checked=me.allowFriendRequests!==false;
      $("profileCountryInput").value=me.country||"";
      $("profileCityInput").value=me.city||"";
      $("profileEducationInput").value=me.education||"";
      $("profileWorkInput").value=me.work||"";
      const rows=[];
      if(me.phone)rows.push(`<div><span>رقم الهاتف</span><strong>${esc(me.phone)}</strong></div>`);
      if(me.email)rows.push(`<div><span>البريد الإلكتروني</span><strong>${esc(me.email)}</strong></div>`);
      if(me.age)rows.push(`<div><span>العمر</span><strong>${esc(me.age)}</strong></div>`);
      $("profilePrivateMeta").innerHTML=rows.join("")||"<div>لا توجد بيانات خاصة.</div>";
      await loadFriendRequests();
    }else{
      updateFriendButton(viewedFriendState);
    }

    const accountFacts=[["الدولة",p.country],["المدينة",p.city],["التعليم",p.education],["العمل",p.work]];
    $("profileMeta").innerHTML=accountFacts.filter(([,value])=>value).map(([label,value])=>`<div><span>${label}</span><strong>${esc(value)}</strong></div>`).join("")+
      (p.createdAt?`<div><span>إنشاء الحساب</span><strong>${new Date(p.createdAt).toLocaleDateString("ar-SA")}</strong></div>`:"");
    await loadFriends(username);
    await loadProfilePosts(username);
  }catch(e){
    alert(e.message||"تعذر تحميل الملف الشخصي");
  }
}

document.addEventListener("click",e=>{
  const section=e.target.closest("[data-profile-section]"),filter=e.target.closest("[data-profile-filter]");
  if(section){
    document.querySelectorAll("#profilePostsList > *").forEach(card=>card.hidden=false);
    $(section.dataset.profileSection)?.scrollIntoView({behavior:"smooth",block:"start"});
  }
  if(filter){
    const video=filter.dataset.profileFilter==="videos";
    document.querySelectorAll("#profilePostsList > *").forEach(card=>{card.hidden=video?!card.querySelector("video,.post-video-preview,.atta-youtube-post-media"):!card.querySelector(".post-media-img,.post-media-image,img[data-full-src],.profile-post-image");});
    $("profilePostsSection").scrollIntoView({behavior:"smooth",block:"start"});
  }
});

// عارض غامر واحد لوسائط المنشور؛ ويظل مشغّل YouTube الحالي مستقلًا.
const postMediaUI={catalogue:new Map(),playlist:[],index:0,kind:"image",root:null,panel:null,session:null,viewerOpen:false,controls:true,hideTimer:0,panelSerial:0,zoom:1,panX:0,panY:0};
function postTotalReactionCount(post){
  if(Number.isFinite(post.reactionCount))return Math.max(0,post.reactionCount);
  const names=new Set(post.likes||[]);
  for(const list of Object.values(post.reactions||{}))if(Array.isArray(list))for(const name of list)names.add(name);
  return names.size;
}
function currentMediaPost(){return postMediaUI.playlist[postMediaUI.index]||null;}
function mediaLayerState(panel=null){return {session:postMediaUI.session,viewer:postMediaUI.viewerOpen,panel};}
function pushMediaHistory(panel=null){history.pushState({...history.state,attaPostMedia:mediaLayerState(panel)},"");}
function postMediaBack(){history.back();}
function refreshMediaBody(){document.body.classList.toggle("post-media-open",postMediaUI.viewerOpen||!!postMediaUI.panel);}
function setMediaControls(show){
  postMediaUI.controls=show;
  postMediaUI.root?.classList.toggle("controls-hidden",!show);
  clearTimeout(postMediaUI.hideTimer);
  const video=postMediaUI.root?.querySelector("video");
  if(show&&postMediaUI.viewerOpen&&postMediaUI.kind==="video"&&video&&!video.paused&&!postMediaUI.panel){
    postMediaUI.hideTimer=setTimeout(()=>setMediaControls(false),2500);
  }
}
function updateMediaStats(id,data){
  const post=postMediaUI.catalogue.get(id);
  for(const item of [post,...postMediaUI.playlist.filter(p=>p.id===id)])if(item){item.reactionCount=data.reactionCount??item.reactionCount;item.commentCount=data.commentCount??item.commentCount;item.myReaction=data.myReaction??item.myReaction;}
  if(currentMediaPost()?.id!==id||!postMediaUI.viewerOpen)return;
  const root=postMediaUI.root;
  root.querySelector('[data-media-people]').textContent=String(postTotalReactionCount(post||data));
  root.querySelector('[data-media-comments]').innerHTML=iconComment()+`<span>${Number(data.commentCount??post?.commentCount??0)}</span>`;
  root.querySelector('[data-media-react]').classList.toggle("active",!!(data.myReaction??post?.myReaction));
}
function closePostPanel(){
  postMediaUI.panelSerial++;
  postMediaUI.panel?.remove();postMediaUI.panel=null;
  refreshMediaBody();setMediaControls(true);
  if(postMediaUI.viewerOpen)postMediaUI.root?.focus();
}
function closePostViewer(){
  postMediaUI.viewerOpen=false;postMediaUI.wasFullscreen=false;clearTimeout(postMediaUI.hideTimer);
  const video=postMediaUI.root?.querySelector("video");
  if(video){video.pause();video.removeAttribute("src");video.load();}
  if(postMediaUI.root)postMediaUI.root.hidden=true;
  if(document.fullscreenElement===postMediaUI.root)postMediaUI.exitPromise=document.exitFullscreen?.().catch(()=>{});
  refreshMediaBody();postMediaUI.previousFocus?.focus?.();
}
function resetPostMedia(){closePostPanel();closePostViewer();postMediaUI.catalogue.clear();postMediaUI.playlist=[];postMediaUI.session=null;}
window.addEventListener("popstate",event=>{
  if(!postMediaUI.viewerOpen&&!postMediaUI.panel)return;
  const target=event.state?.attaPostMedia;
  if(!target||target.session!==postMediaUI.session){closePostPanel();closePostViewer();postMediaUI.session=null;return;}
  if(!target.panel)closePostPanel();
  if(!target.viewer)closePostViewer();
});
document.addEventListener("fullscreenchange",()=>{
  if(document.fullscreenElement===postMediaUI.root){postMediaUI.wasFullscreen=true;return;}
  if(postMediaUI.wasFullscreen){postMediaUI.wasFullscreen=false;if(postMediaUI.viewerOpen)postMediaBack();}
});
function needsPostReader(text){return text.length>500||(text.match(/\n/g)||[]).length>8;}
function postTextPreview(text){return Array.from(text).slice(0,320).join('').split('\n').slice(0,6).join('\n').trimEnd();}
function renderPostText(post,classes='post-text reference-post-text'){
  const text=youtubePostInfo(post.text).text;if(!text)return '';
  if(!needsPostReader(text))return `<div class="${classes}">${esc(text)}</div>`;
  return `<button type="button" class="${classes} post-text-preview" data-read-post="${esc(post.id)}"><span>${esc(postTextPreview(text))}…</span><strong>قراءة المنشور كاملًا</strong></button>`;
}
function bindPostMedia(posts,scope){
  for(const post of posts){postMediaUI.catalogue.set(post.id,post);if(post.sharedPost)postMediaUI.catalogue.set(post.sharedPost.id,post.sharedPost);}
  scope.querySelectorAll("[data-read-post]").forEach(button=>button.onclick=()=>openPostPanel(button.dataset.readPost,"text"));
  scope.querySelectorAll('[data-open-post-media]').forEach(el=>{
    const open=()=>openPostMedia(el.dataset.openPostMedia,el.dataset.mediaKind||"image",posts);
    el.onclick=open;
    if(el.tagName==="IMG"){el.setAttribute("role","button");el.tabIndex=0;el.onkeydown=e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();open();}};}
  });
  scope.querySelectorAll('[data-post-people]').forEach(button=>button.onclick=()=>openPostPanel(button.dataset.postPeople,"reactions"));
  scope.querySelectorAll('[data-post-comments]').forEach(button=>button.onclick=()=>openPostPanel(button.dataset.postComments,"comments"));
}
function createPostMediaRoot(){
  if(postMediaUI.root)return postMediaUI.root;
  const root=document.createElement("section");root.id="postMediaViewer";root.className="post-media-viewer";root.hidden=true;root.tabIndex=-1;
  root.setAttribute("role","dialog");root.setAttribute("aria-modal","true");root.setAttribute("aria-label","وسائط المنشور");
  root.innerHTML=`<div class="post-media-stage"><img alt="صورة المنشور" draggable="false"><video playsinline preload="metadata"></video></div>
    <button class="post-media-play" aria-label="تشغيل الفيديو">${uiIcon("play")}</button>
    <div class="post-media-tools"><div class="post-media-error" role="status"></div><input class="post-media-progress" type="range" min="0" max="1000" value="0" aria-label="موضع تشغيل الفيديو">
    <div class="post-media-actions"><button data-media-react aria-label="إعجاب">${iconHeart()}</button><button data-media-people aria-label="عرض أصحاب التفاعلات">0</button><button data-media-comments aria-label="عرض التعليقات">${iconComment()}<span>0</span></button></div></div>
    <button class="post-media-accessible-close" aria-label="الرجوع إلى المنشورات">الرجوع</button>`;
  document.body.append(root);postMediaUI.root=root;
  const video=root.querySelector("video"),play=root.querySelector(".post-media-play"),range=root.querySelector("input"),stage=root.querySelector(".post-media-stage"),image=root.querySelector("img");
  play.onclick=async()=>{if(video.paused){if(video.error)video.load();if(video.ended)video.currentTime=0;try{await video.play()}catch{root.querySelector('.post-media-error').textContent="تعذر تشغيل الفيديو. تحقق من الاتصال ثم حاول مجددًا.";}}else video.pause();setMediaControls(true);};
  video.addEventListener("play",()=>{root.querySelector('.post-media-error').textContent="";play.textContent="Ⅱ";play.setAttribute("aria-label","إيقاف مؤقت");root.classList.add("playing");setMediaControls(true);});
  video.addEventListener("pause",()=>{play.innerHTML=uiIcon("play");play.setAttribute("aria-label","تشغيل الفيديو");root.classList.remove("playing");setMediaControls(true);});
  video.addEventListener("ended",()=>setMediaControls(true));
  video.addEventListener("error",()=>{if(postMediaUI.viewerOpen&&postMediaUI.kind==="video"){root.querySelector('.post-media-error').textContent="تعذر تحميل الفيديو. تحقق من الاتصال ثم حاول مجددًا.";setMediaControls(true);}});
  video.addEventListener("timeupdate",()=>{range.value=Number.isFinite(video.duration)&&video.duration>0?Math.round(video.currentTime/video.duration*1000):0;});
  range.oninput=()=>{if(Number.isFinite(video.duration)&&video.duration>0)video.currentTime=Number(range.value)/1000*video.duration;};
  root.querySelector('[data-media-react]').onclick=async e=>{
    const post=currentMediaPost();if(!post)return;const button=e.currentTarget;button.disabled=true;
    try{const data=await api('/api/post-react',{method:'POST',body:JSON.stringify({token,postId:post.id,reaction:"like",remove:post.myReaction==="like"})});updateMediaStats(post.id,data);await loadPosts();}
    catch(error){root.querySelector('.post-media-error').textContent=error.message;setMediaControls(true);}finally{button.disabled=false;}
  };
  root.querySelector('[data-media-people]').onclick=()=>openPostPanel(currentMediaPost().id,"reactions");
  root.querySelector('[data-media-comments]').onclick=()=>openPostPanel(currentMediaPost().id,"comments");
  root.querySelector('.post-media-accessible-close').onclick=postMediaBack;
  const points=new Map();let down=null,pinch=0,baseZoom=1,moved=false,wasPinch=false;
  function transformImage(){
    const bounds=stage.getBoundingClientRect();
    postMediaUI.panX=Math.max(-bounds.width*(postMediaUI.zoom-1)/2,Math.min(bounds.width*(postMediaUI.zoom-1)/2,postMediaUI.panX));
    postMediaUI.panY=Math.max(-bounds.height*(postMediaUI.zoom-1)/2,Math.min(bounds.height*(postMediaUI.zoom-1)/2,postMediaUI.panY));
    image.style.transform=`translate(${postMediaUI.panX}px,${postMediaUI.panY}px) scale(${postMediaUI.zoom})`;
  }
  stage.addEventListener("pointerdown",e=>{
    points.set(e.pointerId,{x:e.clientX,y:e.clientY});stage.setPointerCapture?.(e.pointerId);
    if(points.size===1){down={x:e.clientX,y:e.clientY};moved=false;wasPinch=false;}
    if(points.size===2){const [a,b]=[...points.values()];pinch=Math.hypot(a.x-b.x,a.y-b.y);baseZoom=postMediaUI.zoom;wasPinch=true;}
  });
  stage.addEventListener("pointermove",e=>{
    const old=points.get(e.pointerId);if(!old)return;points.set(e.pointerId,{x:e.clientX,y:e.clientY});
    if(down&&Math.hypot(e.clientX-down.x,e.clientY-down.y)>10)moved=true;
    if(postMediaUI.kind!=="image")return;
    if(points.size===2&&pinch){const [a,b]=[...points.values()];postMediaUI.zoom=Math.min(5,Math.max(1,baseZoom*Math.hypot(a.x-b.x,a.y-b.y)/pinch));transformImage();}
    else if(points.size===1&&postMediaUI.zoom>1){postMediaUI.panX+=e.clientX-old.x;postMediaUI.panY+=e.clientY-old.y;transformImage();}
  });
  stage.addEventListener("pointerup",e=>{
    points.delete(e.pointerId);
    if(wasPinch||points.size||!down)return;
    const dy=e.clientY-down.y,dx=e.clientX-down.x;
    if(postMediaUI.kind==="video"&&Math.abs(dy)>80&&Math.abs(dy)>Math.abs(dx)*1.5){changePostVideo(dy<0?1:-1);}
    else if(!moved)setMediaControls(!postMediaUI.controls);
    down=null;
  });
  stage.addEventListener("pointercancel",()=>{points.clear();down=null;wasPinch=false;});
  image.ondblclick=()=>{postMediaUI.zoom=postMediaUI.zoom>1?1:2;postMediaUI.panX=0;postMediaUI.panY=0;transformImage();};
  root.addEventListener("keydown",e=>{
    if(e.key==="Escape"){e.preventDefault();postMediaBack();}
    if(!postMediaUI.panel&&postMediaUI.kind==="video"&&["ArrowUp","ArrowDown"].includes(e.key)){e.preventDefault();changePostVideo(e.key==="ArrowUp"?1:-1);}
    if(e.key==="Tab"){
      const host=postMediaUI.panel||root,items=[...host.querySelectorAll('button:not(:disabled),input:not(:disabled)')].filter(x=>x.offsetParent!==null);
      const first=items[0],last=items.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}
    }
  });
  return root;
}
function renderPostMedia(autoplay=false){
  const post=currentMediaPost(),root=postMediaUI.root;if(!post||!root)return;
  const video=root.querySelector("video"),image=root.querySelector("img");
  video.pause();root.querySelector('.post-media-error').textContent="";
  postMediaUI.zoom=1;postMediaUI.panX=0;postMediaUI.panY=0;image.style.transform="";
  root.dataset.postId=post.id;root.dataset.kind=postMediaUI.kind;
  const isVideo=postMediaUI.kind==="video";video.hidden=!isVideo;image.hidden=isVideo;
  root.querySelector('.post-media-play').hidden=!isVideo;root.querySelector('.post-media-progress').hidden=!isVideo;
  if(isVideo){image.removeAttribute('src');video.src=post.video;video.load();if(autoplay)video.play().catch(()=>setMediaControls(true));}
  else{video.removeAttribute('src');video.load();image.src=post.image;}
  updateMediaStats(post.id,{reactionCount:postTotalReactionCount(post),commentCount:post.commentCount||post.comments?.length||0,myReaction:post.myReaction||""});
  setMediaControls(true);
}
function changePostVideo(delta){
  const next=postMediaUI.index+delta;if(next<0||next>=postMediaUI.playlist.length)return;
  const wasPlaying=!postMediaUI.root.querySelector('video').paused;
  postMediaUI.index=next;renderPostMedia(wasPlaying);
}
function openPostMedia(id,kind,posts){
  const post=postMediaUI.catalogue.get(id);if(!post||!post[kind])return;
  const root=createPostMediaRoot();
  document.querySelectorAll('video').forEach(v=>v.pause());
  postMediaUI.previousFocus=document.activeElement;postMediaUI.session=Date.now()+":"+Math.random();
  postMediaUI.playlist=posts.filter(p=>p[kind]);postMediaUI.index=postMediaUI.playlist.findIndex(p=>p.id===id);postMediaUI.kind=kind;
  if(postMediaUI.index<0)return;
  postMediaUI.viewerOpen=true;root.hidden=false;refreshMediaBody();pushMediaHistory();renderPostMedia();root.focus();
  const session=postMediaUI.session;
  const enterFullscreen=()=>{if(postMediaUI.viewerOpen&&postMediaUI.session===session&&root.requestFullscreen&&!document.fullscreenElement)root.requestFullscreen().catch(()=>{});};
  if(postMediaUI.exitPromise)postMediaUI.exitPromise.then(enterFullscreen);else enterFullscreen();
}
async function openPostPanel(id,kind){
  closePostPanel();
  if(!postMediaUI.viewerOpen)postMediaUI.session=Date.now()+":"+Math.random();
  pushMediaHistory({id,kind});
  const panel=document.createElement('div');panel.className='post-interactions-overlay';panel.setAttribute('role','dialog');panel.setAttribute('aria-modal','true');panel.setAttribute('aria-label',kind==='text'?'المنشور كاملًا':kind==='comments'?'التعليقات':'أصحاب التفاعلات');panel.tabIndex=-1;
  panel.innerHTML=`<section class="post-interactions-sheet"><div class="post-interactions-title"><strong>${kind==='text'?'المنشور كاملًا':kind==='comments'?'التعليقات':'الأشخاص الذين تفاعلوا'}</strong><button aria-label="إغلاق القائمة">×</button></div><div class="post-interactions-rows"></div><div class="post-interactions-error" role="status"></div><button class="post-interactions-more" hidden>عرض المزيد</button>${kind==='comments'?'<form class="post-interactions-compose"><input maxlength="1000" placeholder="اكتب تعليقًا" aria-label="اكتب تعليقًا" required><button>إرسال</button></form>':''}</section>`;
  (postMediaUI.viewerOpen?postMediaUI.root:document.body).append(panel);postMediaUI.panel=panel;refreshMediaBody();setMediaControls(true);panel.focus();
  panel.querySelector('.post-interactions-title button').onclick=postMediaBack;
  panel.addEventListener('click',e=>{if(e.target===panel)postMediaBack();});
  panel.addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();e.stopPropagation();postMediaBack();}});
  if(kind==='text'){
    const rows=panel.querySelector('.post-interactions-rows');rows.classList.add('post-reader-text');
    const text=youtubePostInfo(postMediaUI.catalogue.get(id)?.text||'').text;
    for(const paragraph of text.split('\n\n')){const p=document.createElement('p');p.textContent=paragraph;rows.append(p);}
    return;
  }
  const serial=++postMediaUI.panelSerial;let offset=0,busy=false;
  const rows=panel.querySelector('.post-interactions-rows'),more=panel.querySelector('.post-interactions-more'),error=panel.querySelector('.post-interactions-error');
  async function fetchRows(reset=false){
    if(busy)return;busy=true;more.disabled=true;error.textContent='';
    if(reset){offset=0;rows.textContent='جارٍ التحميل…';}
    try{
      const data=await api(`/api/post-interactions?token=${encodeURIComponent(token)}&postId=${encodeURIComponent(id)}&kind=${kind}&offset=${offset}`);
      if(serial!==postMediaUI.panelSerial)return;
      if(reset)rows.replaceChildren();
      for(const item of data[kind==='comments'?'comments':'people']||[]){
        const person=kind==='comments'?item.authorInfo||{username:item.author}:item,name=person.fullName||person.displayName||person.username||'مستخدم';
        const row=document.createElement('div');row.className='post-interaction-person';
        row.innerHTML=`<button class="post-interaction-profile"><img alt="" src="${esc(person.avatar||avatarFallback(name))}"><strong>${esc(name)}</strong></button>${kind==='comments'?`<p>${esc(item.text)}</p>`:`<span>${esc(reactionEmoji[item.reaction]||'👍')}</span>`}`;
        row.querySelector('button').disabled=!!person.anonymous;
        row.querySelector('button').onclick=()=>{
          closePostPanel();closePostViewer();postMediaUI.session=null;
          history.replaceState({...history.state,attaPostMedia:null},'');openUserProfile(person.username);
        };
        if(kind==='comments')for(const reply of item.replies||[]){const text=document.createElement('p');text.className='post-interaction-reply';text.textContent=(reply.authorInfo?.fullName||reply.author)+': '+reply.text;row.append(text);}
        rows.append(row);
      }
      if(!rows.childElementCount)rows.textContent=kind==='comments'?'اكتب أول تعليق':'لا توجد تفاعلات بعد';
      more.hidden=data.nextOffset===null;offset=data.nextOffset;updateMediaStats(id,data);
    }catch(e){if(serial===postMediaUI.panelSerial){error.textContent=e.message;more.hidden=false;more.textContent='إعادة المحاولة';}}
    finally{busy=false;more.disabled=false;}
  }
  more.onclick=()=>fetchRows(rows.childElementCount===0);
  const form=panel.querySelector('form');if(form)form.onsubmit=async e=>{
    e.preventDefault();const input=form.querySelector('input'),button=form.querySelector('button'),text=input.value.trim();if(!text||button.disabled)return;
    button.disabled=true;error.textContent='';
    try{await api('/api/post-comment',{method:'POST',body:JSON.stringify({token,postId:id,text})});input.value='';await fetchRows(true);await loadPosts();}
    catch(e){error.textContent=e.message;}finally{button.disabled=false;}
  };
  await fetchRows(true);
}

async function loadProfilePosts(username){
  const listEl=$("profilePostsList");
  const countEl=$("profilePostsCount");
  if(!listEl)return;
  listEl.innerHTML='<div class="profile-post-empty">جاري تحميل المنشورات...</div>';
  try{
    const d=await api(`/api/posts?token=${encodeURIComponent(token)}&limit=20`);
    const posts=(d.posts||[]).filter(p=>String(p.author||"").toLowerCase()===String(username||"").toLowerCase());
    if(countEl)countEl.textContent=posts.length;
    if(!posts.length){
      listEl.innerHTML='<div class="profile-post-empty">لا توجد منشورات لهذا الحساب حتى الآن.</div>';
      return;
    }
    listEl.innerHTML=posts.map(post=>{
      const a=post.authorInfo||{username:post.author,displayName:post.author};
      const avatar=a.avatar||avatarFallback(a.fullName||a.displayName||a.username);
      const reactionCount=postTotalReactionCount(post);
      const commentCount=post.commentCount||0;
      return `<article class="profile-post-card" data-profile-post="${esc(post.id)}">
        <button class="profile-post-head profile-link-button" data-open-profile="${esc(a.username)}">
          <img src="${avatar}" alt="">
          <span><strong>${esc(a.fullName||a.displayName||a.username)}</strong><small>@${esc(a.username)} • ${timeAgoAr(post.createdAt)}</small></span>
        </button>
        ${renderPostText(post,"profile-post-text")}
        ${youtubePostFrame(post)}
        ${post.image?`<img class="profile-post-image" data-open-post-media="${esc(post.id)}" data-media-kind="image" loading="lazy" decoding="async" src="${esc(post.image)}" alt="صورة المنشور">`:""}
        ${post.video?`<button class="post-video-preview" data-open-post-media="${esc(post.id)}" data-media-kind="video" aria-label="فتح الفيديو">${uiIcon("play")}</button>`:""}
        ${post.sharedPost?`<div class="shared-post-box"><div class="shared-post-head">@${esc(post.sharedPost.authorInfo?.username||post.sharedPost.author)}</div>${renderPostText(post.sharedPost)}
          ${youtubePostFrame(post.sharedPost)}${post.sharedPost.image?`<img loading="lazy" decoding="async" src="${post.sharedPost.image}" class="shared-post-image" alt="">`:""}</div>`:""}
        <div class="profile-post-footer"><div class="profile-post-stats"><button data-post-people="${esc(post.id)}" aria-label="عرض المعجبين والمتفاعلين">❤️ ${reactionCount} <span>المتفاعلون</span></button><button data-post-comments="${esc(post.id)}" aria-label="عرض أصحاب التعليقات">💬 ${commentCount} <span>التعليقات</span></button></div><button class="profile-post-open" data-profile-post-open="${esc(post.id)}">عرض المنشور</button></div>
      </article>`;
    }).join("");
    bindProfileLinks();
    bindPostMedia(posts,listEl);
    document.querySelectorAll("[data-profile-post-open]").forEach(btn=>{
      btn.onclick=async()=>{
        showPage("homePage");
        await loadPosts();
        await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
        const id=String(btn.dataset.profilePostOpen||"");
        const post=[...document.querySelectorAll("[data-post]")].find(el=>String(el.dataset.post||"")===id);
        if(post){
          post.classList.add("notification-target-highlight");
          post.scrollIntoView({behavior:"smooth",block:"center"});
          setTimeout(()=>post.classList.remove("notification-target-highlight"),2600);
        }
      };
    });
  }catch(e){
    if(countEl)countEl.textContent="0";
    listEl.innerHTML=`<div class="profile-post-empty">${esc(e.message||"تعذر تحميل المنشورات")}</div>`;
  }
}

function updateFriendButton(state){
  const b=$("friendActionBtn");
  const wasHidden=b.classList.contains("hidden");
  if(state==="friends"){b.textContent="✓ صديق";b.className="dark-outline";}
  else if(state==="outgoing"){b.textContent="تم إرسال الطلب";b.className="dark-outline";}
  else if(state==="incoming"){b.textContent="قبول طلب الصداقة";b.className="gold-action";}
  else{b.textContent="إضافة صديق";b.className="gold-action";}
  if(wasHidden)b.classList.add("hidden");
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
    await renderProfilePage(viewedProfileUsername);
    await loadFriends(viewedProfileUsername);
    await loadChatContacts();
  }catch(e){alert(e.message)}
}

async function loadFriendRequests(){
  const d=await api(`/api/friend-requests?token=${encodeURIComponent(token)}`);
  const list=d.requests||[];
  const markup=list.length?list.map(r=>{
    const u=r.user,a=u.avatar||avatarFallback(u.fullName||u.displayName||u.username);
    return `<div class="person-card">
      <button class="person-main" data-open-profile="${esc(u.username)}"><img src="${a}"><span><strong>${esc(u.fullName||u.displayName||u.username)}</strong><small>@${esc(u.username)}</small></span></button>
      <div class="person-actions"><button class="gold-mini" data-accept-friend="${esc(u.username)}">قبول</button><button class="dark-mini" data-decline-friend="${esc(u.username)}">رفض</button></div>
    </div>`;
  }).join(""):`<div class="empty-line">لا توجد طلبات صداقة جديدة.</div>`;

  if($("friendRequestsCount"))$("friendRequestsCount").textContent=list.length;
  if($("friendRequestsList"))$("friendRequestsList").innerHTML=markup;
  if($("friendsPageRequestsCount"))$("friendsPageRequestsCount").textContent=list.length;
  if($("friendsPageRequestsList"))$("friendsPageRequestsList").innerHTML=markup;

  if($("headerFriendBadge")){
    $("headerFriendBadge").textContent=list.length;
    $("headerFriendBadge").classList.toggle("hidden",list.length===0);
  }
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
  const markup=list.length?list.map(u=>{
    const a=u.avatar||avatarFallback(u.fullName||u.displayName||u.username);
    return `<button class="friend-card" data-open-profile="${esc(u.username)}"><img src="${a}"><span><strong>${esc(u.fullName||u.displayName||u.username)}</strong><small>@${esc(u.username)}</small></span></button>`;
  }).join(""):`<div class="empty-line">لا يوجد أصدقاء لعرضهم.</div>`;
  if($("friendsCount"))$("friendsCount").textContent=list.length;
  if($("friendsList"))$("friendsList").innerHTML=markup;
  if(username===me?.username){
    if($("friendsPageCount"))$("friendsPageCount").textContent=list.length;
    if($("friendsPageList"))$("friendsPageList").innerHTML=markup;
  }
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

let notificationPostTarget="";
async function loadPosts(){
  if(!token)return;
  if(!$("postsFeed").children.length)$("postsFeed").innerHTML='<div class="native-load-state" role="status">جاري تحميل المنشورات...</div>';
  try{
    const d=await api(`/api/posts?token=${encodeURIComponent(token)}&limit=20`);
    const posts=(d.posts||[]).slice(0,20);
    if(notificationPostTarget){
      const result=await api(`/api/post?token=${encodeURIComponent(token)}&postId=${encodeURIComponent(notificationPostTarget)}`);
      const index=posts.findIndex(p=>String(p.id)===String(result.post.id));
      if(index>=0)posts.splice(index,1);posts.unshift(result.post);
    }

    const shareCounts={};
    posts.forEach(p=>{
      if(p.sharedPostId)shareCounts[p.sharedPostId]=(shareCounts[p.sharedPostId]||0)+1;
    });

    $("postsFeed").innerHTML=posts.length?posts.map(post=>{
      const a=post.authorInfo||{username:post.author,displayName:post.author};
      const avatar=a.avatar||avatarFallback(a.fullName||a.displayName||a.username);
      const likeCount=postTotalReactionCount(post);
      const commentCount=post.commentCount||0;
      const shareCount=shareCounts[post.id]||0;
      const comments=(post.comments||[]).slice(-5).map(c=>{
        const replies=(c.replies||[]).map(r=>`
          <div class="comment-reply"><strong>${esc(r.authorInfo?.fullName||r.authorInfo?.displayName||r.author)}</strong> ${esc(r.text)}</div>
        `).join("");

        return `<div class="comment-row">
          <div><strong>${esc(c.authorInfo?.fullName||c.authorInfo?.displayName||c.author)}</strong> ${esc(c.text)}</div>
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
        ${renderPostText(post)}
        ${youtubePostFrame(post)}
        ${post.image?`<img class="post-image reference-post-image" data-open-post-media="${esc(post.id)}" data-media-kind="image" loading="lazy" decoding="async" src="${esc(post.image)}" alt="صورة المنشور">`:""}
        ${post.video?`<button class="post-video-preview" data-open-post-media="${esc(post.id)}" data-media-kind="video" aria-label="فتح الفيديو">${uiIcon("play")}</button>`:""}
        ${post.sharedPost?`
        <div class="shared-post-box">
          <div class="shared-post-head">@${esc(post.sharedPost.authorInfo?.username||post.sharedPost.author)}</div>
          ${renderPostText(post.sharedPost)}
          ${youtubePostFrame(post.sharedPost)}
          ${post.sharedPost.image?`<img loading="lazy" decoding="async" src="${post.sharedPost.image}" class="shared-post-image" alt="">`:""}
        </div>`:""}
        <div class="reference-post-footer">
          <div class="reference-post-side-actions">
            <button class="text-action-btn ${post.savedByMe?"active":""}" data-save-post="${post.id}">${iconBookmark()}<span>${post.savedByMe?"محفوظ":"حفظ"}</span></button>

          </div>
          <div class="reference-post-stats">
            <button class="stat-btn ${post.myReaction?"active":""}" data-react-main="${post.id}" data-current-reaction="${esc(post.myReaction||"")}" title="${reactionLabel(post.myReaction)}" aria-label="${reactionLabel(post.myReaction)}">${reactionSymbol(post.myReaction)}</button><button class="stat-btn post-people-trigger" data-post-people="${esc(post.id)}" aria-label="عرض أسماء المعجبين والمتفاعلين">${likeCount}</button>
            <button class="stat-btn post-comments-trigger" data-post-comments="${esc(post.id)}" aria-label="عرض أسماء المعلقين وتعليقاتهم">${iconComment()}<span>${commentCount}</span></button>
            <button class="stat-btn" data-share-post="${post.id}">${iconRepeat()}<span>${shareCount}</span></button>
          </div>
        </div>
        <div class="reaction-picker hidden reference-reaction-picker" data-reaction-picker="${post.id}">
          ${advancedReactions.map(k=>`<button data-react="${post.id}" data-reaction="${k}" aria-label="${reactionLabel(k)}">${reactionEmoji[k]}</button>`).join("")}
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
    bindPostMedia(posts,$("postsFeed"));

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

    // يفتح عدّاد التعليقات العارض المقسّم إلى صفحات الموجود أصلًا،
    // بما فيه الأسماء والصور ومربع التعليق عبر bindPostMedia.

    document.querySelectorAll("[data-react-main]").forEach(btn=>{
      // النقر العادي يبدّل الإعجاب فورًا. فيظل التفاعل الأساسي متاحًا
      // على الكمبيوتر والهاتف، لا مجرد زر يفتح منتقيًا مخفيًا.
      btn.onclick=async()=>{
        try{
          const current=btn.dataset.currentReaction||"";
          await api("/api/post-react",{method:"POST",body:JSON.stringify({
            token,
            postId:btn.dataset.reactMain,
            reaction:"like",
            remove:current==="like"
          })});
          await loadPosts();
        }catch(e){alert(e.message)}
      };

      // النقر بالزر الأيمن على الكمبيوتر يفتح منتقي التفاعلات كاملًا.
      btn.oncontextmenu=(e)=>{
        e.preventDefault();
        document.querySelector(`[data-reaction-picker="${btn.dataset.reactMain}"]`)?.classList.toggle("hidden");
      };
    });


    document.querySelectorAll("[data-react-main]").forEach(btn=>{
      let holdTimer=null,held=false,startPoint=null;
      btn.addEventListener("click",e=>{if(held){e.preventDefault();e.stopImmediatePropagation();held=false;}},true);
      btn.addEventListener("pointerdown",e=>{
        held=false;startPoint={x:e.clientX,y:e.clientY};
        holdTimer=setTimeout(()=>{held=true;
          const picker=document.querySelector(`[data-reaction-picker="${btn.dataset.reactMain}"]`);
          if(picker)picker.classList.remove("hidden");
          holdTimer=null;
        },550);
      });
      btn.addEventListener("pointermove",e=>{if(startPoint&&Math.hypot(e.clientX-startPoint.x,e.clientY-startPoint.y)>10&&holdTimer){clearTimeout(holdTimer);holdTimer=null;}});
      ["pointerup","pointercancel","pointerleave"].forEach(evt=>btn.addEventListener(evt,()=>{
        if(holdTimer){clearTimeout(holdTimer);holdTimer=null;}
      }));
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
    $("postsFeed").innerHTML=`<div class="empty-card" role="alert">${esc(e.message)} <button class="native-button" id="retryFeedBtn">إعادة المحاولة</button></div>`;
    $("retryFeedBtn").onclick=()=>loadPosts();
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
  if(postVideoUploading)return alert("انتظر حتى يكتمل رفع الفيديو أولاً");
  const text=$("postText").value.trim();
  if(!text && !postImageData && !postVideoData && !postVideoUrl)return alert("اكتب منشورًا أو أضف صورة أو فيديو");
  try{
    await api("/api/posts",{method:"POST",body:JSON.stringify({token,text,image:postImageData,video:postVideoUrl||postVideoData})});
    $("postText").value="";
    $("postImageInput").value="";
    if($("postVideoInput"))$("postVideoInput").value="";
    postImageData="";postVideoData="";postVideoUrl="";
    $("postImagePreviewWrap").classList.add("hidden");
    $("postVideoPreviewWrap")?.classList.add("hidden");
    if($("postVideoPreview"))$("postVideoPreview").removeAttribute("src");
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
          ${u.allowFriendRequests!==false && u.friendState==="none"?`<button class="gold-mini" data-search-friend="${esc(u.username)}">إضافة صديق</button>`:""}
          <button class="dark-mini" data-open-profile="${esc(u.username)}">الملف</button>
          ${u.friendState==="friends"?`<button class="dark-mini" data-message-user="${esc(u.username)}">رسالة</button>`:""}
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

async function openNotificationTarget(notification){
  if(!notification)return;

  try{
    if(notification.id){
      await api("/api/notifications/read-one",{
        method:"POST",
        body:JSON.stringify({token,notificationId:notification.id})
      });
    }
  }catch(err){
    console.warn("notification read update failed",err);
  }

  if(notification.postId){
    notificationPostTarget=String(notification.postId);
    showPage("homePage");
    await loadPosts();
    await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    const post=document.querySelector(`[data-post="${CSS.escape(String(notification.postId))}"]`);
    if(post){
      post.classList.add("notification-target-highlight");
      post.scrollIntoView({behavior:"smooth",block:"center"});
      setTimeout(()=>post.classList.remove("notification-target-highlight"),2600);
    }else{
      showInAppToast("المنشور","تعذر العثور على المنشور؛ قد يكون حُذف.");
    }
    return;
  }

  if(notification.type==="friend-request"){
    showPage("friendsPage");
    return;
  }

  if(notification.from && ["friend-accepted","follow"].includes(notification.type)){
    openUserProfile(notification.from);
    return;
  }
}

async function loadNotifications(){
  try{
    const d=await api(`/api/notifications?token=${encodeURIComponent(token)}`);
    const list=d.notifications||[];

    $("notificationsList").innerHTML=list.length?list.map(n=>{
      const sender=esc(n.fromName||n.from||"تواصل العطا");
      const labels={
        like:`أعجب ${sender} بمنشورك`,
        reaction:`تفاعل ${sender} مع منشورك`,
        follow:`بدأ ${sender} بمتابعتك`,
        "friend-request":`أرسل ${sender} لك طلب صداقة`,
        "friend-accepted":`قبل ${sender} طلب الصداقة`,
        share:`شارك ${sender} منشورك`,
        "new-post":`نشر ${sender} منشورًا جديدًا`
      };
      const label=labels[n.type]||`علّق ${sender} على منشورك${n.text?`: ${esc(n.text)}`:""}`;
      const hint=n.postId?"اضغط لعرض المنشور":(n.type==="friend-request"?"اضغط لعرض طلبات الصداقة":(["friend-accepted","follow"].includes(n.type)?"اضغط لعرض الحساب":""));
      return `<button type="button" class="notification-card smart-notification-card ${n.read?"read":""}" data-notification-id="${esc(n.id||"")}" data-notification-post="${esc(n.postId||"")}" data-notification-type="${esc(n.type||"")}" data-notification-from="${esc(n.from||"")}">
        <span class="notification-main-text">${label}</span>
        ${hint?`<span class="notification-action-hint">${hint} ←</span>`:""}
        <small>${new Date(n.createdAt).toLocaleString("ar-SA")}</small>
      </button>`;
    }).join(""):`<div class="empty-card">لا توجد إشعارات</div>`;

    document.querySelectorAll("[data-notification-id]").forEach(btn=>{
      btn.onclick=()=>openNotificationTarget({
        id:btn.dataset.notificationId||"",
        postId:btn.dataset.notificationPost||"",
        type:btn.dataset.notificationType||"",
        from:btn.dataset.notificationFrom||""
      }).then(()=>loadNotifications().catch(()=>{}));
    });

    const unread=list.filter(n=>!n.read).length;
    const badge=$("headerNotifyBadge");
    if(badge){
      badge.textContent=unread>99?"99+":String(unread);
      badge.classList.toggle("hidden",unread===0);
    }
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

    const renderFeedStory=s=>{
      const u=s.authorInfo||{username:s.author};
      const avatar=u.avatar||avatarFallback(u.displayName||u.username);
      return `<button class="inbox-story" data-story="${esc(s.id)}" type="button"><img src="${esc(avatar)}" alt=""><small>${esc(u.displayName||u.username)}</small></button>`;
    };

    if($("storiesStrip")){
      const add=`<button id="feedAddStoryBtn" class="inbox-story" type="button"><span class="story-add-circle">+</span><small>إضافة قصة</small></button>`;
      $("storiesStrip").innerHTML=add+(stories.length
        ? stories.map(renderFeedStory).join("")
        : '<div class="story-empty">لا توجد حالات من الأصدقاء بعد.</div>');
      if($("feedAddStoryBtn"))$("feedAddStoryBtn").onclick=()=>$("addStoryBtn")?.click();
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
async function compressProfileImage(file,{maxWidth,maxHeight,maxChars,quality=.86}){
  const dataUrl=await new Promise((resolve,reject)=>{
    const reader=new FileReader();
    reader.onerror=()=>reject(new Error("تعذر قراءة الصورة"));
    reader.onload=()=>resolve(String(reader.result||""));
    reader.readAsDataURL(file);
  });
  const img=await new Promise((resolve,reject)=>{
    const x=new Image();
    x.onload=()=>resolve(x);
    x.onerror=()=>reject(new Error("صيغة الصورة غير مدعومة"));
    x.src=dataUrl;
  });
  let w=img.naturalWidth||img.width,h=img.naturalHeight||img.height;
  const scale=Math.min(1,maxWidth/w,maxHeight/h);
  w=Math.max(1,Math.round(w*scale));
  h=Math.max(1,Math.round(h*scale));
  let q=quality;
  for(let pass=0;pass<7;pass++){
    const canvas=document.createElement("canvas");
    canvas.width=w; canvas.height=h;
    const ctx=canvas.getContext("2d");
    ctx.fillStyle="#111";ctx.fillRect(0,0,w,h);ctx.drawImage(img,0,0,w,h);
    const out=canvas.toDataURL("image/jpeg",q);
    if(out.length<=maxChars)return out;
    q=Math.max(.52,q-.08);
    if(pass>=3){w=Math.max(320,Math.round(w*.86));h=Math.max(220,Math.round(h*.86));}
  }
  const canvas=document.createElement("canvas");canvas.width=w;canvas.height=h;canvas.getContext("2d").drawImage(img,0,0,w,h);
  return canvas.toDataURL("image/jpeg",.5);
}

async function uploadOwnProfileImage(kind,file){
  if(!file)return;
  const isAvatar=kind==="avatar";
  const trigger=isAvatar?$("profileAvatarEditBtn"):$("profileCoverEditBtn");
  try{
    trigger?.classList.add("profile-image-uploading");
    const data=await compressProfileImage(file,isAvatar
      ?{maxWidth:520,maxHeight:520,maxChars:150000,quality:.88}
      :{maxWidth:1400,maxHeight:700,maxChars:390000,quality:.82});
    if(isAvatar){
      $("profileAvatarLarge").src=data;
      if($("headerAvatar"))$("headerAvatar").src="logo-premium.png?v=14.12";
      if($("homeAvatar"))$("homeAvatar").src=data;
    }else{
      const coverImg=$("profileCoverImage");
      if(coverImg){ coverImg.src=data; coverImg.classList.remove("hidden"); }
    }
    // V13.43: استخدم مسارات API المستقرة فقط لأقصى توافق عند النشر.
    // 1) حدّث وسائط الملف الشخصي للمستخدم عبر /api/profile.
    const profilePayload=kind==="avatar"?{token,avatar:data}:{token,cover:data};
    const d=await api("/api/profile",{method:"POST",body:JSON.stringify(profilePayload)});
    me={...me,...d.user};
    renderProfile();
    await renderProfilePage(me.username);

    // 2) انشر منشورًا عاديًا في الموجز عبر المسار الحالي /api/posts.
    const postText=isAvatar?"قام بتحديث صورته الشخصية.":"قام بتحديث صورة الغلاف.";
    const posted=await api("/api/posts",{method:"POST",body:JSON.stringify({token,text:postText,image:data})});

    showPage("homePage");
    await loadPosts();
    await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    const targetId=String(posted?.post?.id||"");
    if(targetId){
      const post=[...document.querySelectorAll("[data-post]")].find(el=>String(el.dataset.post||"")===targetId);
      if(post){
        post.classList.add("notification-target-highlight");
        post.scrollIntoView({behavior:"smooth",block:"center"});
        setTimeout(()=>post.classList.remove("notification-target-highlight"),3600);
      }
    }
    showInAppToast("تم النشر",isAvatar?"تم تحديث الصورة الشخصية ونشرها في الصفحة الرئيسية.":"تم تحديث صورة الغلاف ونشرها في الصفحة الرئيسية.");
  }catch(err){alert(err.message||"تعذر رفع الصورة");}
  finally{trigger?.classList.remove("profile-image-uploading");}
}

if($("profileAvatarEditBtn"))$("profileAvatarEditBtn").onclick=()=>$("profileAvatarInput")?.click();
if($("profileAvatarEditListBtn"))$("profileAvatarEditListBtn").onclick=()=>$("profileAvatarInput")?.click();
if($("profileCoverEditBtn"))$("profileCoverEditBtn").onclick=()=>$("coverInput")?.click();
if($("profileCoverEditListBtn"))$("profileCoverEditListBtn").onclick=()=>$("coverInput")?.click();
if($("profileAvatarLarge"))$("profileAvatarLarge").onclick=()=>{if(viewedProfileUsername===me?.username)$("profileAvatarInput")?.click()};

if($("profileAvatarInput"))$("profileAvatarInput").onchange=async e=>{const f=e.target.files?.[0];e.target.value="";await uploadOwnProfileImage("avatar",f)};
if($("coverInput"))$("coverInput").onchange=async e=>{const f=e.target.files?.[0];e.target.value="";await uploadOwnProfileImage("cover",f)};

$("toggleProfileEditBtn").onclick=()=>$("profileEditArea").classList.toggle("hidden");
$("saveProfileBtn").onclick=async()=>{try{const body={token,bio:$("bioInput").value.trim(),country:$("profileCountryInput").value.trim(),city:$("profileCityInput").value.trim(),education:$("profileEducationInput").value.trim(),work:$("profileWorkInput").value.trim(),accountPrivate:$("privateAccountInput").checked,allowFriendRequests:$("allowFriendRequestsInput").checked};const d=await api('/api/profile',{method:'POST',body:JSON.stringify(body)});me={...me,...d.user};renderProfile();renderProfilePage();$("profileEditArea").classList.add("hidden")}catch(e){alert(e.message)}};

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


function resetExploreSearch(){
  const input=$("exploreSearchInput");
  if(input)input.value="";
  if($("exploreFeed"))$("exploreFeed").innerHTML='<div class="empty-card">اكتب كلمة في البحث لعرض المنشورات.</div>';
}

async function loadExplore(query=null){
  const q=String(query ?? $("exploreSearchInput")?.value ?? "").trim();
  if(!q){
    if($("exploreFeed"))$("exploreFeed").innerHTML='<div class="empty-card">اكتب كلمة في البحث لعرض المنشورات.</div>';
    return;
  }
  try{
    const d=await api(`/api/explore?token=${encodeURIComponent(token)}&q=${encodeURIComponent(q)}`);
    const posts=d.posts||[];

    $("exploreFeed").innerHTML=posts.length?posts.map(p=>`
      <article class="explore-card">
        ${p.image?`<img src="${p.image}" alt="">`:`<div class="explore-text">${esc(p.text||"منشور")}</div>`}
        <div class="explore-overlay">
          <strong>@${esc(p.authorInfo?.username||p.author)}</strong>
          <span>⭐ ${p.score||0}</span>
        </div>
      </article>
    `).join(""):`<div class="empty-card">لا توجد منشورات مطابقة للبحث</div>`;
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

$("refreshExploreBtn").onclick=()=>loadExplore();
if($("exploreSearchBtn"))$("exploreSearchBtn").onclick=()=>loadExplore();
if($("exploreSearchInput")){
  let exploreSearchTimer=null;
  $("exploreSearchInput").addEventListener("input",()=>{
    clearTimeout(exploreSearchTimer);
    const q=$("exploreSearchInput").value.trim();
    if(!q){resetExploreSearch();return;}
    exploreSearchTimer=setTimeout(()=>loadExplore(q),320);
  });
  $("exploreSearchInput").addEventListener("keydown",e=>{if(e.key==="Enter")loadExplore()});
}
$("loadSavedBtn").onclick=loadSavedPosts;



async function loadDesktopSuggestions(){
  const targets=[$("suggestionsList"),$("mobileSuggestionsList")].filter(Boolean);
  if(!targets.length || !token)return;
  try{
    const d=await api(`/api/users?token=${encodeURIComponent(token)}&q=`);
    const users=(d.users||[]).filter(u=>u.username!==me?.username && u.allowFriendRequests!==false && (!u.friendState || u.friendState==="none")).slice(0,6);

    const html=users.length?users.map(u=>{
      const avatar=u.avatar||avatarFallback(u.fullName||u.displayName||u.username);
      return `<div class="reference-suggestion-row">
        <button class="reference-suggestion-user" data-open-profile="${esc(u.username)}">
          <img src="${avatar}" alt="">
          <span><strong>${esc(u.fullName||u.displayName||u.username)}</strong><small>@${esc(u.username)}</small></span>
        </button>
        <button class="reference-friend-btn" data-side-friend="${esc(u.username)}">إضافة صديق</button>
      </div>`;
    }).join(""):`<div class="reference-side-empty">لا توجد اقتراحات الآن</div>`;

    targets.forEach(el=>el.innerHTML=html);
    bindProfileLinks();

    document.querySelectorAll("[data-side-friend]").forEach(btn=>{
      btn.onclick=async()=>{
        try{
          const username=btn.dataset.sideFriend;
          await api("/api/friend-request",{method:"POST",body:JSON.stringify({token,username})});
          document.querySelectorAll(`[data-side-friend="${CSS.escape(username)}"]`).forEach(b=>{
            b.textContent="تم الإرسال";
            b.disabled=true;
          });
          setTimeout(()=>loadDesktopSuggestions().catch(()=>{}),250);
        }catch(e){alert(e.message)}
      };
    });
  }catch(e){
    targets.forEach(el=>el.innerHTML='<div class="reference-side-empty">تعذر تحميل الاقتراحات</div>');
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
// AL-ATTA v1.0.14: استخدم أعياد الميلاد من التقويم الفعلي، لا عمرًا مكتوبًا يدويًا.
function attaCalendarLimit(offsetYears){
  const now=new Date();
  const d=new Date(now.getFullYear()+offsetYears,now.getMonth(),now.getDate());
  const p=n=>String(n).padStart(2,"0");
  return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())}`;
}
// استخدم قوائم اختيار عادية بدل أجزاء إدخال تاريخ أصلية تختلف حسب لغة الموقع في PWA.
function setupBirthDateFields(input){
  if(!input || input.attaReadBirthDate)return;
  const initial=input.value,minimum=attaCalendarLimit(-120),maximum=attaCalendarLimit(-13);
  const group=document.createElement("div");
  group.className="atta-birth-date";group.setAttribute("role","group");group.setAttribute("aria-label","تاريخ الميلاد");
  const makeSelect=(part,label)=>{
    const field=document.createElement("select");field.dataset.birthPart=part;field.required=true;
    field.setAttribute("aria-label",label);field.add(new Option(label,""));group.append(field);return field;
  };
  const day=makeSelect("day","اليوم"),month=makeSelect("month","الشهر"),year=makeSelect("year","السنة");
  const pad=n=>String(n).padStart(2,"0");
  const months=["يناير","فبراير","مارس","أبريل","مايو","يونيو","يوليو","أغسطس","سبتمبر","أكتوبر","نوفمبر","ديسمبر"];
  months.forEach((name,index)=>month.add(new Option(`${index+1} - ${name}`,pad(index+1))));
  for(let y=Number(maximum.slice(0,4));y>=Number(minimum.slice(0,4));y--)year.add(new Option(String(y),String(y)));
  function sync(){
    const chosen=day.value,count=month.value?new Date(Number(year.value)||2000,Number(month.value),0).getDate():31;
    day.length=1;for(let d=1;d<=count;d++)day.add(new Option(String(d),pad(d)));
    day.value=Number(chosen)<=count?chosen:"";
    input.value=year.value&&month.value&&day.value?`${year.value}-${month.value}-${day.value}`:"";
    year.setCustomValidity(input.value && (input.value<minimum || input.value>maximum)?"العمر المسموح من 13 إلى 120 سنة":"");
  }
  input.type="hidden";input.required=false;input.style.display="none";
  input.insertAdjacentElement("afterend",group);
  const label=input.previousElementSibling;
  if(label?.tagName==="LABEL" && label.htmlFor===input.id){day.id=input.id+"Day";label.htmlFor=day.id;}
  if(/^\d{4}-\d{2}-\d{2}$/.test(initial)){
    const [y,m,d]=initial.split("-");year.value=y;month.value=m;sync();day.value=d;
  }
  sync();for(const field of [day,month,year])field.addEventListener("change",sync);
  input.attaReadBirthDate=()=>{
    sync();
    for(const field of [day,month,year])if(!field.reportValidity())throw Error(field.validationMessage||"اختر تاريخ ميلاد صحيحًا");
    return input.value;
  };
}
function readBirthDate(input){return input.attaReadBirthDate();}
setupBirthDateFields(document.getElementById("ageInput"));

let pendingVerificationId="";
// تلميح تسجيل الدخول أو التسجيل AL-ATTA v1.0.10
function refreshLoginIdentifierHint(){
  const field=document.getElementById("usernameInput");
  if(field)field.placeholder=authMode==="register" ? "اختر اسم مستخدم لحسابك" : "الهاتف أو البريد أو اسم المستخدم";
}
["loginTab","registerTab","attaRegisterShortcut"].forEach(id=>document.getElementById(id)?.addEventListener("click",refreshLoginIdentifierHint));
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
      if(!$("registerPrivacyAgreement")?.checked || !$("registerTermsAgreement")?.checked)throw new Error("اقرأ شروط الاستخدام وسياسة الخصوصية ووافق عليهما قبل إنشاء الحساب");
      const gender=$("genderInput").value;
      const hidden=$("hiddenAccountInput").value;
      if(!["male","female"].includes(gender))throw new Error("اختيار النوع (ذكر أو أنثى) إجباري");
      if(gender==="female" && !["true","false"].includes(hidden))throw new Error("اختاري هل تريدين إخفاء الحساب أم لا");
      body.accountHidden=gender==="female" && hidden==="true";
      body.fullName=$("fullNameInput").value.trim();
      body.birthDate=readBirthDate($("ageInput"));
      body.phone=$("phoneInput").value.trim();
      body.email=$("emailInput").value.trim();
      body.gender=$("genderInput").value;
      body.country=$("countryInput").value.trim();
      body.confirmPassword=$("confirmPasswordInput").value;
      body.legalAccepted=true;
      body.legalVersion="2026-10-20";

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

    if(authMode==="login" && String(e.message||"").includes("EMAIL_NOT_REGISTERED") && body?.username?.includes("@")){
      $("authMsg").innerHTML="";
      const msg=document.createElement("p");
      msg.textContent="هذا البريد غير مسجّل في تواصل العطا. هل تريد إنشاء حساب جديد؟";
      const signup=document.createElement("button");
      signup.type="button";signup.className="atta-create-button";
      signup.textContent="إنشاء حساب بهذا البريد";
      signup.onclick=()=>{
        const email=body.username;
        $("registerTab").click();
        $("emailInput").value=email;
        $("authMsg").textContent="";
      };
      $("authMsg").append(msg,signup);
    } else $("authMsg").textContent=e.message;
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
  e.target.value="";
  await uploadOwnProfileImage("avatar",f);
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
  setRoundCallButton("muteBtn",muted?"mic-off":"mic","الميكروفون");
  $("muteBtn").setAttribute("aria-pressed",String(muted));
  $("muteBtn").classList.toggle("is-off",muted);
};

$("cameraBtn").onclick=async()=>{
  // المكالمة الصوتية لا تفتح الكاميرا تلقائيًا. تشغيلها يدويًا يحولها لفيديو.
  let tracks=localStream?.getVideoTracks()||[];
  if(!tracks.length){
    try{
      const cam=await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:"user"},width:{ideal:1280,max:1280},height:{ideal:720,max:720},frameRate:{ideal:24,max:30}},audio:false});
      const track=cam.getVideoTracks()[0];
      if(!track)return;
      localStream.addTrack(track);
      if(videoSender) await videoSender.replaceTrack(track);
      else if(pc){ videoSender=pc.addTrack(track,localStream); }
      currentCallType="video";
      $("callOverlay")?.classList.remove("audio-only-call");
      $("localVideo")?.closest(".local-pip")?.classList.remove("hidden");
      $("localVideo").srcObject=localStream;
      await $("localVideo").play().catch(()=>{});
      tracks=[track];
    }catch(err){ console.warn("Camera start failed",err); return; }
  }else{
    tracks.forEach(t=>t.enabled=!t.enabled);
  }
  const off=tracks[0]?.enabled===false;
  setRoundCallButton("cameraBtn",off?"camera-off":"video","الكاميرا");
  $("cameraBtn").setAttribute("aria-pressed",String(off));
  $("cameraBtn").classList.toggle("is-off",off);
};


if($("resumeRemoteMediaBtn")){
  $("resumeRemoteMediaBtn").onclick=async()=>{
    try{
      const rv=$("remoteVideo");
      const ra=$("remoteAudio");
      rv.muted=true;
      await rv.play().catch(()=>{});
      if(ra?.srcObject){
        ra.muted=false;
        ra.volume=1;
        await ra.play();
      }
      $("resumeRemoteMediaBtn").classList.add("hidden");
    }catch(err){
      console.warn("manual remote playback failed",err);
    }
  };
}

let switchingWebCamera=false;
$("switchCameraBtn").onclick=async()=>{
  if(switchingWebCamera || currentCallType!=="video")return;
  const previous=localStream?.getVideoTracks()[0];
  if(!previous || !videoSender)return;
  switchingWebCamera=true;
  $("switchCameraBtn").disabled=true;
  let replacement,previousReleased=false;
  const session=localStream;
  const priorFacing=previous.getSettings?.().facingMode || ($("localVideo").closest(".local-pip").classList.contains("rear-camera")?"environment":"user");
  try{
    const rear=previous.getSettings?.().facingMode==="environment" || $("localVideo").closest(".local-pip").classList.contains("rear-camera");
    const constraints={audio:false,video:{facingMode:{exact:rear?"user":"environment"}}};
    let stream;
    try{stream=await navigator.mediaDevices.getUserMedia(constraints)}
    catch(error){
      // تسمح بعض الهواتف بفتح كاميرا واحدة فقط. أبقِ الصوت يعمل أثناء تسليم
      // الكاميرا، وأعد اتجاه العدسة السابق إذا تعذر فتحها.
      if(error.name!=="NotReadableError")throw error;
      previous.stop();previousReleased=true;
      stream=await navigator.mediaDevices.getUserMedia(constraints);
    }
    replacement=stream.getVideoTracks()[0];
    if(!replacement)throw new Error("camera unavailable");
    replacement.enabled=previous.enabled;
    if(localStream!==session || !pc)throw new Error("call ended");
    await videoSender.replaceTrack(replacement);
    localStream.removeTrack(previous);localStream.addTrack(replacement);previous.stop();
    $("localVideo").srcObject=localStream;
    $("localVideo").closest(".local-pip").classList.toggle("rear-camera",!rear);
    await $("localVideo").play().catch(()=>{});
  }catch(err){
    replacement?.stop();
    if(previousReleased && localStream===session && pc){
      try{
        const restored=await navigator.mediaDevices.getUserMedia({audio:false,video:{facingMode:{exact:priorFacing}}});
        const track=restored.getVideoTracks()[0];
        if(localStream!==session || !pc){restored.getTracks().forEach(t=>t.stop());return}
        track.enabled=previous.enabled;
        await videoSender.replaceTrack(track);
        session.removeTrack(previous);session.addTrack(track);$("localVideo").srcObject=session;
      }catch{}
    }
    if(localStream===session)alert("تعذر تبديل الكاميرا. تأكد من توفر كاميرا أخرى والسماح باستخدامها.");
  }
  finally{switchingWebCamera=false;$("switchCameraBtn").disabled=false}
};
$("speakerBtn").onclick=async()=>{
  const audio=$("remoteAudio"),button=$("speakerBtn");
  if(!audio?.setSinkId || !navigator.mediaDevices?.selectAudioOutput){
    alert("تغيير السماعة غير مدعوم في هذا المتصفح. استخدم اختيار مخرج الصوت من إعدادات الجهاز.");return;
  }
  try{
    const output=await navigator.mediaDevices.selectAudioOutput();
    await audio.setSinkId(output.deviceId);
    button.setAttribute("aria-pressed",String(output.deviceId!=="default"));
  }catch{}
};
$("screenBtn").onclick=shareScreen;
window.addEventListener("beforeunload",stopCallSounds);

// افتح الحساب المحفوظ تلقائيًا بعد تجهيز كل عناصر الواجهة.
// ربط تسجيل الخروج بعد جميع أحداث التنقل حتى لا يتم استبدال onclick.
bindLogoutButton("logoutBtn");
bindLogoutButton("moreLogoutBtn");
bindLogoutButton("profileLogoutBtn");

queueMicrotask(()=>{
  window.AttaIcons?.hydrate(document);
  setVoiceRecordButton(false);
  restoreSavedLogin().catch(err=>console.warn("restore login failed",err));
});


// منتقي خصوصية واحد لنموذج التسجيل الحالي بالبريد الإلكتروني.
const registerGender=$("genderInput"), registerHidden=$("hiddenAccountChoice");
registerGender?.addEventListener("change",()=>{
  registerHidden?.classList.toggle("hidden",registerGender.value!=="female");
  if(registerGender.value!=="female")$("hiddenAccountInput").value="";
});

// V13.8 — خدمات هوية Google (تسجيل الدخول/إنشاء الحساب ببريد موثّق مجانًا).
// تتحقق Google من ملكية البريد؛ ولا يُرسل رمز تحقق أو يُكشف.
// AL-ATTA v1.0.10: اختر الملف الشخصي يدويًا لحسابات Google الجديدة.
function requestGoogleProfile(submitProfile){
  return new Promise(resolve=>{
    const overlay=document.createElement("div");
    overlay.className="google-profile-overlay";
    overlay.setAttribute("role","dialog");overlay.setAttribute("aria-modal","true");overlay.setAttribute("aria-label","إنشاء حساب باستخدام Google");
    overlay.style.cssText="position:fixed;inset:0;z-index:999999;background:rgba(0,0,0,.85);display:grid;place-items:center;padding:12px;direction:rtl;overflow-y:auto";
    overlay.innerHTML=`<form style="width:min(100%,420px);min-width:0;max-height:calc(100dvh - 24px);overflow-y:auto;background:#111619;border:1px solid #e6b94e;border-radius:22px;padding:24px;display:grid;gap:12px;box-shadow:0 12px 50px #000;color:#fff" autocomplete="on">
      <h2 style="margin:0;color:#e6b94e;font-size:21px">إنشاء حساب تواصل العطا</h2>
      <p style="margin:0;color:#b8bec1;font-size:13px">اختَر اسمك بنفسك. اسم المستخدم لا يظهر تحت اسمك في الملف الشخصي.</p>
      <input name="fullName" required minlength="2" maxlength="60" placeholder="اسمك الذي يظهر للآخرين" style="padding:13px;border-radius:11px;background:#080b0d;color:#fff;border:1px solid #514125">
      <input name="username" required minlength="3" maxlength="24" pattern="[a-zA-Z0-9_]{3,24}" placeholder="اسم مستخدم فريد (إنجليزي/أرقام)" style="padding:13px;border-radius:11px;background:#080b0d;color:#fff;border:1px solid #514125" autocapitalize="off">
      <label style="color:#e6b94e;font-size:13px">تاريخ الميلاد (اليوم / الشهر / السنة)</label>
      <input name="birthDate" type="date" required aria-label="تاريخ الميلاد" style="padding:13px;border-radius:11px;background:#080b0d;color:#fff;border:1px solid #514125">
      <input name="city" maxlength="80" placeholder="المدينة (اختياري)" style="padding:13px;border-radius:11px;background:#080b0d;color:#fff;border:1px solid #514125">
      <select name="gender" required style="padding:13px;border-radius:11px;background:#080b0d;color:#fff;border:1px solid #514125">
        <option value="">النوع (إجباري)</option><option value="male">ذكر</option><option value="female">أنثى</option>
      </select>
      <label data-hidden-label style="display:none;color:#e6b94e;font-size:13px">هل تريدين إخفاء الحساب؟
        <select name="accountHidden" style="width:100%;padding:12px;background:#080b0d;color:#fff;border:1px solid #514125">
          <option value="">اختاري نعم أو لا</option><option value="true">نعم، إخفاء</option><option value="false">لا، إظهار</option>
        </select>
      </label>
      <label style="color:#e6b94e;font-size:12px"><input type="checkbox" name="legalAccepted" required> قرأت <a href="/terms.html" target="_blank" rel="noopener noreferrer" style="color:#f8d57f">الشروط</a> و<a href="/privacy.html" target="_blank" rel="noopener noreferrer" style="color:#f8d57f">سياسة الخصوصية</a> وأوافق عليهما</label>
      <p data-google-profile-error role="alert" style="margin:0;color:#ffb5b5;font-size:13px"></p>
      <button type="submit" style="border:0;background:#e6b94e;color:#070707;padding:13px;border-radius:13px;font-weight:800">تأكيد وإنشاء الحساب</button>
      <button type="button" data-cancel style="background:transparent;color:#d9c89a;border:1px solid #6b5429;padding:11px;border-radius:13px">إلغاء</button>
    </form>`;
    const form=overlay.querySelector("form");
    const finish=value=>{overlay.remove();resolve(value)};
    overlay.querySelector("[data-cancel]").onclick=()=>finish(null);
    form.elements.gender.onchange=()=>{
      const female=form.elements.gender.value==="female";
      form.querySelector("[data-hidden-label]").style.display=female?"block":"none";
      if(!female)form.elements.accountHidden.value="";
    };
    form.onsubmit=async e=>{
      e.preventDefault();
      try{readBirthDate(form.elements.birthDate);}catch(error){form.querySelector('[data-google-profile-error]').textContent=error.message;return;}
      if(!form.reportValidity())return;
      if(form.elements.gender.value==="female" && !["true","false"].includes(form.elements.accountHidden.value)){
        alert("اختاري هل تريدين إخفاء الحساب أم لا");return;
      }
      const profile={fullName:form.elements.fullName.value.trim(),username:form.elements.username.value.trim().toLowerCase(),birthDate:form.elements.birthDate.value,city:form.elements.city.value.trim(),gender:form.elements.gender.value,accountHidden:form.elements.gender.value==="female" && form.elements.accountHidden.value==="true",legalAccepted:form.elements.legalAccepted.checked,legalVersion:"2026-10-20"};
      const submit=form.querySelector('[type="submit"]'),error=form.querySelector('[data-google-profile-error]');
      submit.disabled=true;form.querySelector("[data-cancel]").disabled=true;error.textContent="جاري إنشاء الحساب...";
      try{finish(await submitProfile(profile));}
      catch(e){error.textContent=e.message||"تعذر إنشاء الحساب. راجع البيانات وأعد المحاولة.";}
      finally{submit.disabled=false;form.querySelector("[data-cancel]").disabled=false;}
    };
    document.body.appendChild(overlay);
    const born=form.elements.birthDate;
    setupBirthDateFields(born);
    form.elements.fullName.focus();
  });
}

let googleWidgetStarted=false;
async function setupGoogleSignIn(){
  const hint=document.getElementById("googleSetupHint");
  const holder=document.getElementById("googleSignInBtn");
  if(!hint || !holder || googleWidgetStarted)return;
  try{
    const config=await api("/api/google-config");
    if(!config.enabled){
      hint.textContent="";
      hint.style.display="none";
      return;
    }
    hint.style.display="";
    await new Promise((resolve,reject)=>{
      if(window.google?.accounts?.id)return resolve();
      const script=document.createElement("script");
      script.src="https://accounts.google.com/gsi/client";
      script.async=true;script.defer=true;
      script.onload=resolve;script.onerror=()=>reject(new Error("تعذر تحميل تسجيل Google"));
      document.head.appendChild(script);
    });
    window.google.accounts.id.initialize({
      client_id:config.clientId,
      callback:async ({credential})=>{
        try{
          hint.textContent="جاري تأكيد البريد بواسطة Google...";
          let result;
          try{
            result=await api("/api/google-login",{
              method:"POST",body:JSON.stringify({credential})
            });
          }catch(firstError){
            if(firstError.code!=="USERNAME_REQUIRED" && !String(firstError.message||"").includes("USERNAME_REQUIRED"))throw firstError;
            result=await requestGoogleProfile(profile=>api("/api/google-login",{
              method:"POST",body:JSON.stringify({credential,...profile})
            }));
            if(!result){hint.textContent="لم يتم إنشاء الحساب";return;}
          }
          await startAuthenticatedApp(result.token,result.user);
        }catch(e){hint.textContent=e.message||"تعذر تسجيل الدخول بواسطة Google";}
      }
    });
    window.google.accounts.id.renderButton(holder,{
      type:"standard",theme:"filled_black",size:"large",shape:"pill",
      text:"continue_with",width:300,locale:"ar"
    });
    googleWidgetStarted=true;
  }catch(e){hint.textContent=e.message||"تعذر تفعيل تسجيل Google";}
}
window.addEventListener("load",setupGoogleSignIn);

window.AttaWeb=Object.freeze({
  navigate:showPage,
  user:()=>({username:me?.username||"",role:me?.role||""}),
  callBusy:()=>callState!=="idle",
  watchRequest:(payload,keepalive=false)=>api("/api/watch-room",{method:"POST",keepalive,body:JSON.stringify({...payload,token})}),
  async watchIce(){await ensureTurnIceServers();return rtcConfig;},
  preparePost(text,image=""){
    if(image && postImageData)throw new Error("يوجد منشور بصورة قيد التجهيز. انشره أولًا أو احذف صورته قبل مشاركة صفحة جديدة.");
    const current=$("postText").value.trim();
    $("postText").value=current?current+"\n\n"+text:text;
    if(image){postImageData=image;$("postImagePreview").src=image;$("postImagePreviewWrap").classList.remove("hidden");}
    showPage("homePage");$("postText").scrollIntoView({behavior:"smooth",block:"center"});$("postText").focus();
  },
  async changePassword(oldPassword,newPassword,confirmPassword){
    await api("/api/change-password",{method:"POST",body:JSON.stringify({token,oldPassword,newPassword,confirmPassword})});
    await logoutCurrentAccount();
  }
});
})();


/* ===== دعم التثبيت في Android وPWA، الإصدار V13.28 ===== */
(() => {
  let deferredInstallPrompt = null;
  const installButtons = [...document.querySelectorAll('[data-pwa-install]')];
  const installHelp = document.getElementById("pwaInstallHelp");
  const standaloneQuery = window.matchMedia("(display-mode: standalone)");
  const fullscreenQuery = window.matchMedia("(display-mode: fullscreen)");
  const isStandalone = () => standaloneQuery.matches || fullscreenQuery.matches || window.navigator.standalone === true;
  let installedThisSession = false;
  const actions=document.getElementById("mobileInstallActions"),mount=document.getElementById("authInstallMount");
  if(actions&&mount)mount.append(actions);
  const refreshInstallButton = () => {
    const installed = isStandalone() || installedThisSession;
    installButtons.forEach(button => button.classList.toggle("hidden", installed));
    if (installHelp) installHelp.hidden = installed;
  };
  window.addEventListener("beforeinstallprompt", event => {
    event.preventDefault();
    deferredInstallPrompt = event;
    refreshInstallButton();
  });
  window.addEventListener("appinstalled", () => {
    deferredInstallPrompt = null;
    installedThisSession = true;
    refreshInstallButton();
  });
  standaloneQuery.addEventListener?.("change", refreshInstallButton);
  fullscreenQuery.addEventListener?.("change", refreshInstallButton);
  installButtons.forEach(button => button.addEventListener("click", async () => {
    if (!deferredInstallPrompt) {
      alert("من Chrome على Android: افتح القائمة ⋮ ثم «تثبيت التطبيق». لو ظهر «إضافة إلى الشاشة الرئيسية»، اختَر التثبيت وليس إنشاء اختصار. افتح الموقع بعدها من أيقونته. على iPhone: من Safari اضغط مشاركة ثم إضافة إلى الشاشة الرئيسية.");
      return;
    }
    const prompt = deferredInstallPrompt;
    deferredInstallPrompt = null;
    installButtons.forEach(button => button.disabled = true);
    try { await prompt.prompt(); await prompt.userChoice; } catch (_) {}
    finally { installButtons.forEach(button => button.disabled = false); refreshInstallButton(); }
  }));

  if ("serviceWorker" in navigator) {
    window.addEventListener("load", () => {
      navigator.serviceWorker.register("/sw.js?v=14.17", { scope: "/" }).catch(() => {});
    });
  }

  refreshInstallButton();


})();
