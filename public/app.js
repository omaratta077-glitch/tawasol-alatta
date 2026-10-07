
(()=>{
const $=id=>document.getElementById(id);

let token="",me=null,ws=null,reconnectTimer=null;
let selectedUser="",selectedGroup=null,users=[],groups=[];
let pc=null,localStream=null,currentCallType="video",incomingFrom="";

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
      return;
    }

    if(msg.type==="users"){
      users=msg.users||[];
      renderUsers();
      return;
    }

    if(msg.type==="chat"){
      if(msg.from!==me.username)notify("رسالة جديدة",`${msg.from}: ${msg.text}`);
      addMessage(msg.from,msg.text,msg.from===me.username);
      return;
    }

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
      $("incomingModal").classList.remove("hidden");
      notify("مكالمة واردة",`${incomingFrom} يتصل بك`);
      return;
    }

    if(msg.type==="call-accept"){
      selectedUser=msg.from;
      await startOffer();
      return;
    }

    if(msg.type==="call-reject"){
      await hangup(false);
      alert("تم رفض المكالمة");
      return;
    }

    if(msg.type==="offer"){
      selectedUser=msg.from;
      await ensurePeer();
      await pc.setRemoteDescription(msg.sdp);
      const answer=await pc.createAnswer();
      await pc.setLocalDescription(answer);
      send({type:"answer",to:msg.from,sdp:pc.localDescription,callType:currentCallType});
      return;
    }

    if(msg.type==="answer"){
      if(pc)await pc.setRemoteDescription(msg.sdp);
      return;
    }

    if(msg.type==="ice"){
      if(pc && msg.candidate){
        try{await pc.addIceCandidate(msg.candidate)}catch{}
      }
      return;
    }

    if(msg.type==="hangup"){
      await hangup(false);
      return;
    }

    if(msg.type==="user-offline"){
      alert("المستخدم غير متصل الآن");
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
}

function renderUsers(){
  const others=users.filter(u=>u.username!==me?.username);

  $("usersList").innerHTML=others.length
    ? others.map(u=>`
      <button class="user-item" data-user="${esc(u.username)}">
        <span class="online-dot"></span>${esc(u.displayName||u.username)}
      </button>`).join("")
    : `<div style="opacity:.7;font-size:12px">لا يوجد مستخدمون آخرون متصلون</div>`;

  document.querySelectorAll("[data-user]").forEach(btn=>{
    btn.onclick=()=>{
      selectedUser=btn.dataset.user;
      selectedGroup=null;
      const u=users.find(x=>x.username===selectedUser);
      $("selectedLabel").textContent=u?.displayName||selectedUser;
      $("messages").innerHTML="";
    };
  });
}

function addMessage(from,text,mine){
  const div=document.createElement("div");
  div.className="bubble"+(mine?" mine":"");
  div.innerHTML=`<div class="meta">${esc(from)}</div><div>${esc(text)}</div>`;
  $("messages").appendChild(div);
  $("messages").scrollTop=$("messages").scrollHeight;
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

  localStream=await navigator.mediaDevices.getUserMedia(
    type==="audio"
      ? {audio:true,video:false}
      : {audio:true,video:true}
  );

  $("localVideo").srcObject=localStream;
  return localStream;
}

async function ensurePeer(){
  if(pc)return pc;

  pc=new RTCPeerConnection(rtcConfig);

  pc.onicecandidate=e=>{
    if(e.candidate && selectedUser){
      send({type:"ice",to:selectedUser,candidate:e.candidate});
    }
  };

  pc.ontrack=e=>{
    $("remoteVideo").srcObject=e.streams[0];
  };

  const stream=await getMedia(currentCallType);
  stream.getTracks().forEach(track=>pc.addTrack(track,stream));
  return pc;
}

async function requestCall(type){
  if(!selectedUser)return alert("اختر مستخدمًا أولًا");
  currentCallType=type;
  send({type:"call-request",to:selectedUser,callType:type});
}

async function startOffer(){
  await ensurePeer();
  const offer=await pc.createOffer();
  await pc.setLocalDescription(offer);
  send({type:"offer",to:selectedUser,sdp:pc.localDescription,callType:currentCallType});
}

async function hangup(notifyPeer=true){
  if(notifyPeer && selectedUser){
    send({type:"hangup",to:selectedUser,callType:currentCallType});
  }

  pc?.close();
  pc=null;

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

let authMode="login";

$("loginTab").onclick=()=>{
  authMode="login";
  $("loginTab").classList.add("active");
  $("registerTab").classList.remove("active");
  $("registerFields").classList.add("hidden");
  $("confirmPasswordInput").classList.add("hidden");
  $("authBtn").textContent="دخول";
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

    $("authView").classList.add("hidden");
    $("appView").classList.remove("hidden");

    renderProfile();
    connect();
    loadGroups();
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

$("sendBtn").onclick=()=>{
  const text=$("messageInput").value.trim();
  if(!text)return;

  if(selectedGroup){
    send({type:"group-chat",groupId:selectedGroup.id,text});
  }else if(selectedUser){
    send({type:"chat",to:selectedUser,text});
  }else{
    return alert("اختر مستخدمًا أو مجموعة");
  }

  $("messageInput").value="";
};

$("messageInput").onkeydown=e=>{
  if(e.key==="Enter")$("sendBtn").click();
};

$("audioCallBtn").onclick=()=>requestCall("audio");
$("videoCallBtn").onclick=()=>requestCall("video");

$("acceptCallBtn").onclick=async()=>{
  $("incomingModal").classList.add("hidden");
  selectedUser=incomingFrom;
  await ensurePeer();
  send({type:"call-accept",to:incomingFrom,callType:currentCallType});
};

$("rejectCallBtn").onclick=()=>{
  send({type:"call-reject",to:incomingFrom,callType:currentCallType});
  $("incomingModal").classList.add("hidden");
};

$("hangupBtn").onclick=()=>hangup(true);

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
})();
