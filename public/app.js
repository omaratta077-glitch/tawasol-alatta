
(()=>{
const $=id=>document.getElementById(id);

let token="",me=null,ws=null,reconnectTimer=null;
let viewedProfileUsername="";
let viewedFriendState="none";
let selectedUser="",selectedGroup=null,users=[],groups=[],chatFriends=[];
let pc=null,localStream=null,currentCallType="video",incomingFrom="";
let videoSender=null,audioSender=null;

let pendingIceCandidates=[];


const rtcConfig={iceServers:(window.TAWASOL_CONFIG?.iceServers)||[]};

async function api(path,opts={}){
  const r=await fetch(path,{
    ...opts,
    headers:{"content-type":"application/json",...(opts.headers||{})}
  });
  const d=await r.json().catch(()=>({}));
  if(!r.ok)throw new Error(d.error||"حدث خطأ");
  return d;
}

function esc(s){
  return String(s??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");
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

function iconHeart(){return `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20.5s-7-4.35-9.2-8.17C1.02 9.24 2.04 5.6 5.8 4.76c2.2-.5 4.08.52 5.2 2.1 1.12-1.58 3-2.6 5.2-2.1 3.76.84 4.78 4.48 3 7.57C19 16.15 12 20.5 12 20.5Z"></path></svg>`}
function iconComment(){return `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H9l-5 4V7a2 2 0 0 1 2-2Z"></path></svg>`}
function iconRepeat(){return `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M17 2l4 4-4 4"></path><path d="M3 11V9a3 3 0 0 1 3-3h15"></path><path d="M7 22l-4-4 4-4"></path><path d="M21 13v2a3 3 0 0 1-3 3H3"></path></svg>`}
function iconBookmark(){return `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h12v18l-6-4-6 4V3Z"></path></svg>`}
function iconShare(){return `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 5h5v5"></path><path d="M10 14 19 5"></path><path d="M19 13v4a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h4"></path></svg>`}
function iconGlobe(){return `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M3 12h18"></path><path d="M12 3a15 15 0 0 1 0 18"></path><path d="M12 3a15 15 0 0 0 0 18"></path></svg>`}
function iconDots(){return `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="5" r="1.8"></circle><circle cx="12" cy="12" r="1.8"></circle><circle cx="12" cy="19" r="1.8"></circle></svg>`}

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

function connect(){
  clearTimeout(reconnectTimer);
  ws=new WebSocket(wsUrl());
  $("connectionState").textContent="جاري الاتصال...";

  ws.onopen=()=>{$("connectionState").textContent="متصل"};
  ws.onclose=()=>{
    $("connectionState").textContent="انقطع الاتصال — إعادة المحاولة...";
    reconnectTimer=setTimeout(connect,1500);
  };

  ws.onmessage=async e=>{
    const msg=JSON.parse(e.data);

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

  const allMap=new Map();

  for(const f of chatFriends){
    if(f.username!==me.username){
      allMap.set(f.username,{...f,isFriend:true});
    }
  }

  for(const u of users){
    if(u.username!==me.username){
      allMap.set(u.username,{...(allMap.get(u.username)||{}),...u,isOnline:true});
    }
  }

  const all=[...allMap.values()].sort((a,b)=>{
    const ao=onlineMap.has(a.username)?1:0;
    const bo=onlineMap.has(b.username)?1:0;
    return bo-ao || String(a.displayName||a.username).localeCompare(String(b.displayName||b.username),"ar");
  });

  $("usersList").innerHTML=all.length
    ? all.map(u=>{
        const isOnline=onlineMap.has(u.username);
        const avatar=u.avatar||avatarFallback(u.fullName||u.displayName||u.username);

        return `
        <button class="user-item chat-contact-item" data-user="${esc(u.username)}">
          <img class="chat-contact-avatar" src="${avatar}" alt="">
          <span class="chat-contact-copy">
            <strong>${esc(u.fullName||u.displayName||u.username)}</strong>
            <small>${isOnline?"متصل الآن":"غير متصل — يمكنك إرسال رسالة"}</small>
          </span>
          <span class="presence-dot ${isOnline?"online":"offline"}"></span>
        </button>`;
      }).join("")
    : `<div class="chat-empty-contacts">لا يوجد أصدقاء بعد. أضف صديقًا ليظهر هنا حتى لو كان غير متصل.</div>`;

  document.querySelectorAll("[data-user]").forEach(btn=>{
    btn.onclick=()=>{
      selectedUser=btn.dataset.user;
      selectedGroup=null;

      const u=allMap.get(selectedUser);
      $("selectedLabel").textContent=u?.fullName||u?.displayName||selectedUser;

      loadConversation(selectedUser);
    };
  });
}

function addMessage(from,text,mine){addRichMessage({from,to:mine?selectedUser:me?.username,text,media:"",mediaType:"text",read:false,ts:Date.now()})}
function addRichMessage(msg){
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
        : `<a class="chat-file-link" href="${msg.media}" target="_blank">📎 فتح المرفق</a>`;
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

async function loadConversation(username){if(!username)return;try{const d=await api(`/api/conversation?token=${encodeURIComponent(token)}&with=${encodeURIComponent(username)}`);$("messages").innerHTML="";(d.messages||[]).forEach(addRichMessage);send({type:"read",with:username})}catch(e){console.error(e)}}

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

async function getMedia(type){
  localStream?.getTracks().forEach(t=>t.stop());

  const constraints = type==="audio"
    ? {
        audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},
        video:false
      }
    : {
        audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},
        video:{
          facingMode:"user",
          width:{ideal:1280},
          height:{ideal:720}
        }
      };

  try{
    localStream=await navigator.mediaDevices.getUserMedia(constraints);

    const localVideo=$("localVideo");
    localVideo.srcObject=localStream;
    localVideo.muted=true;
    localVideo.playsInline=true;

    try{await localVideo.play()}catch{}

    const hasVideo=localStream.getVideoTracks().length>0;
    if(type==="video" && !hasVideo){
      throw new Error("لم يتم العثور على كاميرا فعالة");
    }

    return localStream;
  }catch(err){
    console.error("getUserMedia error",err);

    let msg="تعذر تشغيل الكاميرا أو المايك.";
    if(err?.name==="NotAllowedError"){
      msg="تم رفض صلاحية الكاميرا أو المايك. اسمح للموقع باستخدامهما ثم جرّب مرة أخرى.";
    }else if(err?.name==="NotFoundError"){
      msg="لم يتم العثور على كاميرا أو مايك على هذا الجهاز.";
    }else if(err?.name==="NotReadableError"){
      msg="الكاميرا أو المايك مستخدمان في برنامج آخر.";
    }

    $("callPeerState").textContent=msg;
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

  pendingIceCandidates=[];
  videoSender=null;
  audioSender=null;

  pc=new RTCPeerConnection(rtcConfig);

  // نعلن من البداية أننا نرسل ونستقبل.
  const audioTransceiver=pc.addTransceiver("audio",{direction:"sendrecv"});
  let videoTransceiver=null;

  if(currentCallType==="video"){
    videoTransceiver=pc.addTransceiver("video",{direction:"sendrecv"});
  }

  pc.onicecandidate=e=>{
    if(e.candidate && selectedUser){
      send({type:"ice",to:selectedUser,candidate:e.candidate});
    }
  };

  pc.ontrack=async e=>{
    const remoteVideo=$("remoteVideo");

    let stream=e.streams?.[0];
    if(!stream){
      stream=remoteVideo.srcObject instanceof MediaStream
        ? remoteVideo.srcObject
        : new MediaStream();

      if(!stream.getTracks().some(t=>t.id===e.track.id)){
        stream.addTrack(e.track);
      }
    }

    remoteVideo.srcObject=stream;
    remoteVideo.playsInline=true;

    try{
      await remoteVideo.play();
    }catch(err){
      console.warn("remote video play blocked",err);
    }

    if(e.track.kind==="video"){
      const empty=document.querySelector(".remote-empty-state");
      if(empty)empty.style.display="none";
      $("callPeerState").textContent="الفيديو متصل";
      $("callStatusText").textContent="مكالمة فيديو جارية";
    }else if(e.track.kind==="audio"){
      if($("callPeerState").textContent!=="الفيديو متصل"){
        $("callPeerState").textContent="الصوت متصل — جاري استقبال الفيديو...";
      }
    }
  };

  pc.onconnectionstatechange=()=>{
    const state=pc?.connectionState||"";
    const labels={
      new:"جاري التجهيز...",
      connecting:"جاري توصيل المكالمة...",
      connected:currentCallType==="video"?"متصل — جاري استقبال الفيديو...":"متصل",
      disconnected:"الاتصال ضعيف...",
      failed:"فشل اتصال الوسائط",
      closed:"انتهت المكالمة"
    };

    if($("callPeerState"))$("callPeerState").textContent=labels[state]||state;

    if(state==="failed"){
      alert("تعذر توصيل الصوت/الفيديو. قد تحتاج شبكة أخرى أو TURN للاتصال بين بعض الشبكات.");
    }
  };

  const stream=await getMedia(currentCallType);

  const audioTrack=stream.getAudioTracks()[0]||null;
  const videoTrack=stream.getVideoTracks()[0]||null;

  if(audioTrack){
    audioTrack.enabled=true;
    await audioTransceiver.sender.replaceTrack(audioTrack);
    audioSender=audioTransceiver.sender;
  }

  if(currentCallType==="video" && videoTransceiver && videoTrack){
    videoTrack.enabled=true;
    await videoTransceiver.sender.replaceTrack(videoTrack);
    videoSender=videoTransceiver.sender;
  }

  // تحقق فعلي أن المسارات مضافة.
  console.log("Local media tracks",{
    audio:!!audioSender?.track,
    video:!!videoSender?.track,
    videoEnabled:videoSender?.track?.enabled
  });

  return pc;
}

async function requestCall(type){
  if(!selectedUser)return alert("اختر مستخدمًا أولًا");
  currentCallType=type;

  const empty=document.querySelector(".remote-empty-state");
  if(empty)empty.style.display="grid";

  showCallOverlay(selectedUser,"جاري الاتصال...");
  playOutgoingRing();
  send({type:"call-request",to:selectedUser,callType:type});
}

async function startOffer(){
  await ensurePeer();

  const offer=await pc.createOffer({
    offerToReceiveAudio:true,
    offerToReceiveVideo:currentCallType==="video"
  });

  await pc.setLocalDescription(offer);

  send({
    type:"offer",
    to:selectedUser,
    sdp:pc.localDescription,
    callType:currentCallType
  });

  // لو الفيديو لم يظهر بعد ثوانٍ، نعيد التفاوض مرة واحدة.
  if(currentCallType==="video"){
    setTimeout(async()=>{
      if(!pc || pc.connectionState==="closed")return;

      const remoteHasVideo=$("remoteVideo")?.srcObject instanceof MediaStream
        && $("remoteVideo").srcObject.getVideoTracks().length>0;

      if(!remoteHasVideo){
        try{
          const retryOffer=await pc.createOffer({
            iceRestart:true,
            offerToReceiveAudio:true,
            offerToReceiveVideo:true
          });
          await pc.setLocalDescription(retryOffer);
          send({
            type:"offer",
            to:selectedUser,
            sdp:pc.localDescription,
            callType:"video",
            renegotiate:true
          });
        }catch(err){
          console.warn("Video renegotiation failed",err);
        }
      }
    },3500);
  }
}

async function hangup(notifyPeer=true){
  if(notifyPeer && selectedUser){
    send({type:"hangup",to:selectedUser,callType:currentCallType});
  }

  pc?.close();
  pc=null;
  videoSender=null;
  audioSender=null;
  pendingIceCandidates=[];

  const empty=document.querySelector(".remote-empty-state");
  if(empty)empty.style.display="grid";

  localStream?.getTracks().forEach(t=>t.stop());
  localStream=null;

  $("localVideo").srcObject=null;
  $("remoteVideo").srcObject=null;
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

function softTone(freq,duration=0.32,volume=0.045,delay=0){
  if(!callAudioCtx)return;

  const now=callAudioCtx.currentTime+delay;
  const osc=callAudioCtx.createOscillator();
  const gain=callAudioCtx.createGain();

  osc.type="sine";
  osc.frequency.setValueAtTime(freq,now);

  gain.gain.setValueAtTime(0.0001,now);
  gain.gain.exponentialRampToValueAtTime(volume,now+0.05);
  gain.gain.setValueAtTime(volume,now+Math.max(0.08,duration-0.10));
  gain.gain.exponentialRampToValueAtTime(0.0001,now+duration);

  osc.connect(gain);
  gain.connect(callAudioCtx.destination);

  osc.start(now);
  osc.stop(now+duration+0.03);
}

function playIncomingPattern(){
  if(activeRingType!=="incoming")return;
  ensureCallAudio();

  // نغمة هادئة: ثلاث نقرات موسيقية قصيرة
  softTone(659.25,0.34,0.040,0.00);
  softTone(783.99,0.34,0.038,0.36);
  softTone(987.77,0.42,0.034,0.72);
}

function playOutgoingPattern(){
  if(activeRingType!=="outgoing")return;
  ensureCallAudio();

  // انتظار هادئ ومنخفض
  softTone(440.00,0.28,0.030,0.00);
  softTone(554.37,0.28,0.026,0.34);
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
        selectedUser=btn.dataset.messageUser;
        selectedGroup=null;
        $("selectedLabel").textContent=selectedUser;
        showPage("messagesPage");
        loadConversation(selectedUser);
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
        <span>+</span><small>إضافة قصة</small>
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
$("chatFileInput").onchange=e=>{const f=e.target.files?.[0];if(!f)return;const r=new FileReader();r.onload=()=>{pendingChatMedia={data:String(r.result||""),type:f.type.startsWith('image/')?'image':f.type.startsWith('audio/')?'audio':'file'};$("messageInput").placeholder=`مرفق جاهز: ${f.name}`};r.readAsDataURL(f)};
$("recordVoiceBtn").onclick=async()=>{if(mediaRecorder?.state==='recording'){mediaRecorder.stop();$("recordVoiceBtn").textContent='🎤';return}try{const s=await navigator.mediaDevices.getUserMedia({audio:true});mediaRecorder=new MediaRecorder(s);voiceChunks=[];mediaRecorder.ondataavailable=e=>{if(e.data.size)voiceChunks.push(e.data)};mediaRecorder.onstop=()=>{const b=new Blob(voiceChunks,{type:'audio/webm'});const r=new FileReader();r.onload=()=>{pendingChatMedia={data:String(r.result||''),type:'audio'};$("messageInput").placeholder='رسالة صوتية جاهزة'};r.readAsDataURL(b);s.getTracks().forEach(t=>t.stop())};mediaRecorder.start();$("recordVoiceBtn").textContent='⏹️'}catch(e){alert('تعذر تشغيل الميكروفون')}};
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


if($("attaRegisterShortcut")){
  $("attaRegisterShortcut").onclick=()=>{
    authMode="register";
    $("registerFields").classList.remove("hidden");
    $("confirmPasswordInput").classList.remove("hidden");
    $("authBtn").textContent="إنشاء الحساب";
  if($("attaRegisterShortcut"))$("attaRegisterShortcut").classList.add("hidden");
    $("attaRegisterShortcut").classList.add("hidden");
  };
}

$("loginTab").onclick=()=>{
  authMode="login";
  $("loginTab").classList.add("active");
  $("registerTab").classList.remove("active");
  $("registerFields").classList.add("hidden");
  $("confirmPasswordInput").classList.add("hidden");
  $("authBtn").textContent="تسجيل الدخول";
  if($("attaRegisterShortcut"))$("attaRegisterShortcut").classList.remove("hidden");
};

$("registerTab").onclick=()=>{
  authMode="register";
  $("registerTab").classList.add("active");
  $("loginTab").classList.remove("active");
  $("registerFields").classList.remove("hidden");
  $("confirmPasswordInput").classList.remove("hidden");
  $("authBtn").textContent="إنشاء الحساب";
};

$("authBtn").onclick=async()=>{
  ensureCallAudio();
  try{
    $("authMsg").textContent="";

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
    }

    const d=await api(
      authMode==="register"?"/api/register":"/api/login",
      {method:"POST",body:JSON.stringify(body)}
    );

    token=d.token;
    me=d.user;
    viewedProfileUsername=me.username;

    $("authView").classList.add("hidden");
    $("appView").classList.remove("hidden");

    renderProfile();
    $("homeAvatar").src=me.avatar||avatarFallback(me.fullName||me.displayName||me.username);
    renderProfilePage();
    connect();
    loadGroups();
    loadChatContacts();
    loadPosts();
    loadStories();
    loadDesktopSuggestions();
    loadFriendRequests().catch(()=>{});
    askNotifications();
  }catch(e){
    $("authMsg").textContent=e.message;
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

$("sendBtn").onclick=()=>{const text=$("messageInput").value.trim();if(!text&&!pendingChatMedia)return;if(selectedGroup)send({type:"group-chat",groupId:selectedGroup.id,text});else if(selectedUser)send({type:"chat",to:selectedUser,text,media:pendingChatMedia?.data||"",mediaType:pendingChatMedia?.type||"text"});else return alert("اختر مستخدمًا أو مجموعة");$("messageInput").value="";$("messageInput").placeholder="اكتب رسالة...";pendingChatMedia=null;};

$("messageInput").onkeydown=e=>{
  if(e.key==="Enter")$("sendBtn").click();
};

$("audioCallBtn").onclick=()=>{ensureCallAudio();requestCall("audio")};
$("videoCallBtn").onclick=()=>{ensureCallAudio();requestCall("video")};

$("acceptCallBtn").onclick=async()=>{
  stopCallSounds();
  $("incomingModal").classList.add("hidden");
  selectedUser=incomingFrom;
  showCallOverlay(incomingFrom,"مكالمة جارية");
  await ensurePeer();
  send({type:"call-accept",to:incomingFrom,callType:currentCallType});
};

$("rejectCallBtn").onclick=()=>{
  stopCallSounds();
  send({type:"call-reject",to:incomingFrom,callType:currentCallType});
  $("incomingModal").classList.add("hidden");
  hideCallOverlay(false);
};

$("hangupBtn").onclick=async()=>{
  stopCallSounds();
  await hangup(true);
  hideCallOverlay(false);
};

$("muteBtn").onclick=()=>{
  const tracks=localStream?.getAudioTracks()||[];
  tracks.forEach(t=>t.enabled=!t.enabled);
  $("muteBtn").textContent=tracks[0]?.enabled===false?"🔇 تشغيل المايك":"🎙️ كتم المايك";
};

$("cameraBtn").onclick=()=>{
  const tracks=localStream?.getVideoTracks()||[];
  tracks.forEach(t=>t.enabled=!t.enabled);
  $("cameraBtn").textContent=tracks[0]?.enabled===false?"📷 تشغيل الكاميرا":"📷 إيقاف الكاميرا";
};

$("screenBtn").onclick=shareScreen;
window.addEventListener("beforeunload",stopCallSounds);
})();
