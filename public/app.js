
(()=>{
  const $=id=>document.getElementById(id);

  let ws=null;
  let me="";
  let selectedUser="";
  let pc=null;
  let localStream=null;
  let currentCallType="video";
  let incomingFrom="";
  let pendingOffer=null;

  const cfg=window.TAWASOL_CONFIG||{};
  const rtcConfig={ iceServers: cfg.iceServers || [] };

  function wsUrl(){
    const proto=location.protocol==="https:"?"wss":"ws";
    return `${proto}://${location.host}/ws`;
  }

  function connect(){
    ws=new WebSocket(wsUrl());

    ws.addEventListener("open",()=>{
      ws.send(JSON.stringify({type:"join",user:me}));
    });

    ws.addEventListener("message",async e=>{
      const msg=JSON.parse(e.data);

      if(msg.type==="users"){
        renderUsers(msg.users||[]);
        return;
      }

      if(msg.type==="chat"){
        addMessage(msg.from,msg.text,msg.from===me);
        return;
      }

      if(msg.type==="call-request"){
        incomingFrom=msg.from;
        currentCallType=msg.callType||"video";
        $("incomingText").textContent=`${incomingFrom} يتصل بك — ${currentCallType==="audio"?"صوت":"فيديو"}`;
        $("incomingModal").classList.remove("hidden");
        return;
      }

      if(msg.type==="call-accept"){
        if(msg.from!==selectedUser)return;
        await startOffer();
        return;
      }

      if(msg.type==="call-reject"){
        $("connectionState").textContent="تم رفض المكالمة";
        await hangup(false);
        return;
      }

      if(msg.type==="offer"){
        pendingOffer=msg;
        await ensurePeer();
        await pc.setRemoteDescription(msg.sdp);
        const answer=await pc.createAnswer();
        await pc.setLocalDescription(answer);
        send({type:"answer",to:msg.from,sdp:pc.localDescription});
        $("connectionState").textContent="المكالمة جارية";
        return;
      }

      if(msg.type==="answer"){
        if(pc)await pc.setRemoteDescription(msg.sdp);
        $("connectionState").textContent="المكالمة جارية";
        return;
      }

      if(msg.type==="ice"){
        if(pc&&msg.candidate){
          try{await pc.addIceCandidate(msg.candidate)}catch{}
        }
        return;
      }

      if(msg.type==="hangup"){
        await hangup(false);
        $("connectionState").textContent="انتهت المكالمة";
      }
    });
  }

  function send(obj){
    if(ws&&ws.readyState===WebSocket.OPEN){
      ws.send(JSON.stringify(obj));
    }
  }

  function renderUsers(users){
    const list=$("usersList");
    const others=users.filter(u=>u!==me);
    list.innerHTML=others.map(u=>`
      <button class="user-item ${u===selectedUser?"active":""}" data-user="${escapeHtml(u)}">
        <span>${escapeHtml(u)}</span>
        <span class="online-dot"></span>
      </button>
    `).join("");

    list.querySelectorAll("[data-user]").forEach(btn=>{
      btn.onclick=()=>{
        selectedUser=btn.dataset.user;
        $("selectedUserLabel").textContent=selectedUser;
        renderUsers(users);
      };
    });
  }

  function addMessage(from,text,mine){
    const div=document.createElement("div");
    div.className="msg"+(mine?" mine":"");
    div.innerHTML=`<div class="meta">${escapeHtml(from)}</div><div>${escapeHtml(text)}</div>`;
    $("messages").appendChild(div);
    $("messages").scrollTop=$("messages").scrollHeight;
  }

  function escapeHtml(s){
    return String(s??"")
      .replace(/&/g,"&amp;").replace(/</g,"&lt;")
      .replace(/>/g,"&gt;").replace(/"/g,"&quot;");
  }

  async function getMedia(type){
    if(localStream){
      localStream.getTracks().forEach(t=>t.stop());
      localStream=null;
    }

    const constraints=type==="audio"
      ? {audio:true,video:false}
      : {audio:true,video:true};

    localStream=await navigator.mediaDevices.getUserMedia(constraints);
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

    pc.onconnectionstatechange=()=>{
      $("connectionState").textContent={
        connected:"المكالمة جارية",
        connecting:"جاري الاتصال...",
        disconnected:"انقطع الاتصال",
        failed:"فشل الاتصال",
        closed:"انتهت المكالمة"
      }[pc.connectionState]||pc.connectionState;
    };

    const stream=await getMedia(currentCallType);
    stream.getTracks().forEach(track=>pc.addTrack(track,stream));

    return pc;
  }

  async function requestCall(type){
    if(!selectedUser)return alert("اختر مستخدمًا أولاً.");
    currentCallType=type;
    $("connectionState").textContent="جاري الاتصال...";
    send({type:"call-request",to:selectedUser,callType:type});
  }

  async function startOffer(){
    await ensurePeer();
    const offer=await pc.createOffer();
    await pc.setLocalDescription(offer);
    send({type:"offer",to:selectedUser,sdp:pc.localDescription});
  }

  async function hangup(notify=true){
    if(notify && selectedUser){
      send({type:"hangup",to:selectedUser});
    }
    if(pc){
      pc.ontrack=null;
      pc.onicecandidate=null;
      pc.close();
      pc=null;
    }
    if(localStream){
      localStream.getTracks().forEach(t=>t.stop());
      localStream=null;
    }
    $("localVideo").srcObject=null;
    $("remoteVideo").srcObject=null;
    $("connectionState").textContent="غير متصل بمكالمة";
  }

  async function shareScreen(){
    if(!pc)return alert("ابدأ مكالمة أولاً.");
    const display=await navigator.mediaDevices.getDisplayMedia({video:true});
    const track=display.getVideoTracks()[0];
    const sender=pc.getSenders().find(s=>s.track&&s.track.kind==="video");
    if(sender)await sender.replaceTrack(track);
    $("localVideo").srcObject=display;

    track.onended=async()=>{
      if(!localStream)return;
      const cam=localStream.getVideoTracks()[0];
      if(sender&&cam)await sender.replaceTrack(cam);
      $("localVideo").srcObject=localStream;
    };
  }

  $("loginBtn").onclick=()=>{
    const name=$("loginName").value.trim();
    if(!name)return alert("اكتب اسمك.");
    me=name;
    $("meLabel").textContent=me;
    $("loginView").classList.add("hidden");
    $("appView").classList.remove("hidden");
    connect();
  };

  $("loginName").addEventListener("keydown",e=>{
    if(e.key==="Enter")$("loginBtn").click();
  });

  $("sendBtn").onclick=()=>{
    const text=$("messageInput").value.trim();
    if(!text)return;
    send({type:"chat",to:selectedUser,text});
    $("messageInput").value="";
  };

  $("messageInput").addEventListener("keydown",e=>{
    if(e.key==="Enter")$("sendBtn").click();
  });

  $("audioCallBtn").onclick=()=>requestCall("audio");
  $("videoCallBtn").onclick=()=>requestCall("video");

  $("acceptCallBtn").onclick=async()=>{
    $("incomingModal").classList.add("hidden");
    selectedUser=incomingFrom;
    $("selectedUserLabel").textContent=selectedUser;
    await ensurePeer();
    send({type:"call-accept",to:incomingFrom,callType:currentCallType});
  };

  $("rejectCallBtn").onclick=()=>{
    send({type:"call-reject",to:incomingFrom});
    $("incomingModal").classList.add("hidden");
    incomingFrom="";
  };

  $("hangupBtn").onclick=()=>hangup(true);

  $("muteBtn").onclick=()=>{
    if(!localStream)return;
    const tracks=localStream.getAudioTracks();
    tracks.forEach(t=>t.enabled=!t.enabled);
    const enabled=tracks[0]?.enabled!==false;
    $("muteBtn").textContent=enabled?"🎙️ كتم المايك":"🔇 تشغيل المايك";
  };

  $("cameraBtn").onclick=()=>{
    if(!localStream)return;
    const tracks=localStream.getVideoTracks();
    tracks.forEach(t=>t.enabled=!t.enabled);
    const enabled=tracks[0]?.enabled!==false;
    $("cameraBtn").textContent=enabled?"📷 إيقاف الكاميرا":"📷 تشغيل الكاميرا";
  };

  $("screenBtn").onclick=shareScreen;

  window.addEventListener("beforeunload",()=>hangup(false));
})();
