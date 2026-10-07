
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

    if(msg.type==="chat"){if(msg.from!==me.username)notify("رسالة جديدة",`${msg.from}: ${msg.text||"مرفق"}`);addRichMessage(msg);if(msg.from===selectedUser&&msg.to===me.username)send({type:"read",with:msg.from});return;}
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
      $("incomingModal").classList.remove("hidden");
      notify("مكالمة واردة",`${incomingFrom} يتصل بك`);
      return;
    }

    if(msg.type==="call-accept"){
      selectedUser=msg.from;
      showCallOverlay(msg.from,"تم الاتصال");
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
      showCallOverlay(msg.from,"مكالمة جارية");
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
      hideCallOverlay(false);
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
      loadConversation(selectedUser);
    };
  });
}

function addMessage(from,text,mine){addRichMessage({from,to:mine?selectedUser:me?.username,text,media:"",mediaType:"text",read:false,ts:Date.now()})}
function addRichMessage(msg){const mine=msg.from===me?.username;const div=document.createElement("div");div.className="bubble"+(mine?" mine":"");let media="";if(msg.media){media=msg.mediaType==="image"?`<img class="chat-media-image" src="${msg.media}">`:msg.mediaType==="audio"?`<audio class="chat-audio" controls src="${msg.media}"></audio>`:`<a class="chat-file-link" href="${msg.media}" target="_blank">📎 فتح المرفق</a>`}div.innerHTML=`<div class="meta">${esc(msg.from)}</div>${msg.text?`<div>${esc(msg.text)}</div>`:""}${media}${mine?`<div class="message-read-state">${msg.read?"✓✓ تمت القراءة":"✓ تم الإرسال"}</div>`:""}`;$("messages").appendChild(div);$("messages").scrollTop=$("messages").scrollHeight}
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
    document.querySelector("remoteVideo").srcObject=e.streams[0];
    const empty=document.querySelector(".remote-empty-state");
    if(empty)empty.style.display="none";
  };

  const stream=await getMedia(currentCallType);
  stream.getTracks().forEach(track=>pc.addTrack(track,stream));
  return pc;
}

async function requestCall(type){
  if(!selectedUser)return alert("اختر مستخدمًا أولًا");
  currentCallType=type;
  showCallOverlay(selectedUser,"جاري الاتصال...");
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


let postImageData="";
let storyMediaData="", pendingChatMedia=null, typingTimer=null, mediaRecorder=null, voiceChunks=[], profileCoverData="";

let callOverlayOpen=false;

function showCallOverlay(peerName,stateText="مكالمة جارية"){
  callOverlayOpen=true;
  $("callOverlay").classList.remove("hidden");
  $("callStatusBar").classList.remove("hidden");
  $("callPeerName").textContent=peerName||selectedUser||incomingFrom||"مكالمة";
  $("remoteEmptyName").textContent=peerName||selectedUser||incomingFrom||"الطرف الآخر";
  $("callPeerState").textContent=stateText;
  $("callStatusText").textContent=stateText;
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

  if(pageId==="homePage")loadPosts();
  if(pageId==="notificationsPage")loadNotifications();
  if(pageId==="profilePage")renderProfilePage();
}

document.querySelectorAll(".nav-btn").forEach(btn=>{
  btn.onclick=()=>showPage(btn.dataset.page);
});

$("openProfileBtn").onclick=()=>showPage("profilePage");

async function renderProfilePage(){
  if(!me)return;
  try{const d=await api(`/api/profile-public?token=${encodeURIComponent(token)}&username=${encodeURIComponent(me.username)}`);me={...me,...d.user};
  $("profileAvatarLarge").src=me.avatar||avatarFallback(me.fullName||me.displayName||me.username);$("profileName").textContent=me.fullName||me.displayName||me.username;$("profileUsername").textContent="@"+me.username;$("profileBio").textContent=me.bio||"لا توجد نبذة بعد";$("profileCover").style.backgroundImage=me.cover?`url("${me.cover}")`:"";$("bioInput").value=me.bio||"";$("privateAccountInput").checked=!!me.accountPrivate;$("followersCount").textContent=d.followerCount||0;$("followingCount").textContent=d.followingCount||0;
  const parts=[];if(me.age)parts.push(`العمر: ${me.age}`);if(me.phone)parts.push(`الهاتف: ${me.phone}`);if(me.email)parts.push(`البريد: ${me.email}`);if(me.country)parts.push(`الدولة: ${me.country}`);$("profileMeta").innerHTML=parts.map(x=>`<div>${esc(x)}</div>`).join("");}catch(e){console.error(e)}}

async function loadPosts(){
  if(!token)return;
  try{
    const d=await api(`/api/posts?token=${encodeURIComponent(token)}`);
    const posts=d.posts||[];

    $("postsFeed").innerHTML=posts.length?posts.map(post=>{
      const a=post.authorInfo||{username:post.author,displayName:post.author};
      const avatar=a.avatar||avatarFallback(a.fullName||a.displayName||a.username);
      const comments=(post.comments||[]).slice(-5).map(c=>`
        <div class="comment-row"><strong>@${esc(c.author)}</strong> ${esc(c.text)}</div>
      `).join("");

      return `
      <article class="post-card" data-post="${post.id}">
        <div class="post-head">
          <img src="${avatar}" class="avatar" alt="">
          <div>
            <strong>${esc(a.fullName||a.displayName||a.username)}</strong>
            <small>@${esc(a.username)} · ${new Date(post.createdAt).toLocaleString("ar-SA")}</small>
          </div>
        </div>
        ${post.text?`<div class="post-text">${esc(post.text)}</div>`:""}
        ${post.image?`<img class="post-image" src="${post.image}" alt="">`:""}
        <div class="post-actions">
          <button class="like-btn ${post.likedByMe?"liked":""}" data-like="${post.id}">❤️ ${post.likeCount||0}</button>
          <button class="comment-focus-btn" data-comment-focus="${post.id}">💬 ${post.commentCount||0}</button>
        </div>
        <div class="comments-box">
          <div class="comments-list">${comments}</div>
          <div class="comment-compose">
            <input data-comment-input="${post.id}" placeholder="اكتب تعليقًا...">
            <button data-comment-send="${post.id}">إرسال</button>
          </div>
        </div>
      </article>`;
    }).join(""):`<div class="empty-card">لا توجد منشورات بعد.</div>`;

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
        <div class="user-search-actions"><button data-follow-user="${esc(u.username)}">متابعة</button><button data-message-user="${esc(u.username)}">مراسلة</button></div>
      </div>
    `).join(""):`<div class="empty-card">لا توجد نتائج</div>`;

    document.querySelectorAll("[data-follow-user]").forEach(btn=>{btn.onclick=async()=>{try{const d=await api("/api/follow",{method:"POST",body:JSON.stringify({token,username:btn.dataset.followUser})});btn.textContent=d.following?"إلغاء المتابعة":"متابعة"}catch(e){alert(e.message)}}});
    document.querySelectorAll("[data-message-user]").forEach(btn=>{
      btn.onclick=()=>{
        selectedUser=btn.dataset.messageUser;
        selectedGroup=null;
        $("selectedLabel").textContent=selectedUser;
        showPage("messagesPage");
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



async function loadStories(){if(!token)return;try{const d=await api(`/api/stories?token=${encodeURIComponent(token)}`);const stories=d.stories||[];$("storiesStrip").innerHTML=stories.length?stories.map(s=>{const u=s.authorInfo||{username:s.author};const av=u.avatar||avatarFallback(u.displayName||u.username);return `<button class="story-item" data-story="${s.id}"><span class="story-ring"><img src="${av}"></span><small>${esc(u.displayName||u.username)}</small></button>`}).join(""):'<div class="story-empty">لا توجد حالات</div>';document.querySelectorAll('[data-story]').forEach(b=>b.onclick=()=>{const s=stories.find(x=>x.id===b.dataset.story);if(!s)return;$("storyViewerAuthor").textContent=s.authorInfo?.displayName||s.author;$("storyViewerText").textContent=s.text||"";if(s.media){$("storyViewerImage").src=s.media;$("storyViewerImage").classList.remove("hidden")}else $("storyViewerImage").classList.add("hidden");$("storyViewer").classList.remove("hidden")})}catch(e){console.error(e)}}
$("addStoryBtn").onclick=()=>$("storyModal").classList.remove("hidden");$("closeStoryModalBtn").onclick=()=>$("storyModal").classList.add("hidden");$("closeStoryViewer").onclick=()=>$("storyViewer").classList.add("hidden");
$("storyMediaInput").onchange=e=>{const f=e.target.files?.[0];if(!f)return;const r=new FileReader();r.onload=()=>{storyMediaData=String(r.result||"");$("storyPreview").src=storyMediaData;$("storyPreview").classList.remove("hidden")};r.readAsDataURL(f)};
$("publishStoryBtn").onclick=async()=>{const text=$("storyTextInput").value.trim();if(!text&&!storyMediaData)return alert("اكتب حالة أو أضف صورة");try{await api('/api/stories',{method:'POST',body:JSON.stringify({token,text,media:storyMediaData})});$("storyTextInput").value="";storyMediaData="";$("storyModal").classList.add("hidden");$("storyPreview").classList.add("hidden");loadStories()}catch(e){alert(e.message)}};
$("coverInput").onchange=e=>{const f=e.target.files?.[0];if(!f)return;const r=new FileReader();r.onload=()=>{profileCoverData=String(r.result||"");$("profileCover").style.backgroundImage=`url("${profileCoverData}")`};r.readAsDataURL(f)};
$("saveProfileBtn").onclick=async()=>{try{const body={token,bio:$("bioInput").value.trim(),accountPrivate:$("privateAccountInput").checked};if(profileCoverData)body.cover=profileCoverData;const d=await api('/api/profile',{method:'POST',body:JSON.stringify(body)});me={...me,...d.user};profileCoverData="";renderProfilePage()}catch(e){alert(e.message)}};
$("chatFileInput").onchange=e=>{const f=e.target.files?.[0];if(!f)return;const r=new FileReader();r.onload=()=>{pendingChatMedia={data:String(r.result||""),type:f.type.startsWith('image/')?'image':f.type.startsWith('audio/')?'audio':'file'};$("messageInput").placeholder=`مرفق جاهز: ${f.name}`};r.readAsDataURL(f)};
$("recordVoiceBtn").onclick=async()=>{if(mediaRecorder?.state==='recording'){mediaRecorder.stop();$("recordVoiceBtn").textContent='🎤';return}try{const s=await navigator.mediaDevices.getUserMedia({audio:true});mediaRecorder=new MediaRecorder(s);voiceChunks=[];mediaRecorder.ondataavailable=e=>{if(e.data.size)voiceChunks.push(e.data)};mediaRecorder.onstop=()=>{const b=new Blob(voiceChunks,{type:'audio/webm'});const r=new FileReader();r.onload=()=>{pendingChatMedia={data:String(r.result||''),type:'audio'};$("messageInput").placeholder='رسالة صوتية جاهزة'};r.readAsDataURL(b);s.getTracks().forEach(t=>t.stop())};mediaRecorder.start();$("recordVoiceBtn").textContent='⏹️'}catch(e){alert('تعذر تشغيل الميكروفون')}};
$("messageInput").addEventListener('input',()=>{if(!selectedUser)return;send({type:'typing',to:selectedUser,active:true});clearTimeout(typingTimer);typingTimer=setTimeout(()=>send({type:'typing',to:selectedUser,active:false}),900)});

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
    $("homeAvatar").src=me.avatar||avatarFallback(me.fullName||me.displayName||me.username);
    renderProfilePage();
    connect();
    loadGroups();
    loadPosts();
    loadStories();
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

$("audioCallBtn").onclick=()=>requestCall("audio");
$("videoCallBtn").onclick=()=>requestCall("video");

$("acceptCallBtn").onclick=async()=>{
  $("incomingModal").classList.add("hidden");
  selectedUser=incomingFrom;
  showCallOverlay(incomingFrom,"مكالمة جارية");
  await ensurePeer();
  send({type:"call-accept",to:incomingFrom,callType:currentCallType});
};

$("rejectCallBtn").onclick=()=>{
  send({type:"call-reject",to:incomingFrom,callType:currentCallType});
  $("incomingModal").classList.add("hidden");
  hideCallOverlay(false);
};

$("hangupBtn").onclick=async()=>{
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
})();
