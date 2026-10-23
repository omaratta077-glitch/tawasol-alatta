import { DurableObject } from "cloudflare:workers";
// BEGIN Google Apps Script email transport (server-side only)
async function sendViaGoogleAppsScript(env,email,code,type,fetcher=fetch){
  const endpoint=String(env.GAS_EMAIL_WEBAPP_URL||"").trim();
  const secret=String(env.GAS_EMAIL_API_SECRET||"").trim();
  if(!endpoint||!secret)throw new Error("EMAIL_PROVIDER_NOT_CONFIGURED");
  let url;
  try{
    url=new URL(endpoint);
    if(url.protocol!=="https:"||url.hostname!=="script.google.com"||url.username||
      url.password||url.port||url.search||url.hash||
      !/^\/macros\/s\/[A-Za-z0-9_-]+\/exec$/.test(url.pathname))throw Error();
  }catch{throw new Error("EMAIL_PROVIDER_NOT_CONFIGURED");}
  if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)||
     !/^\d{6}$/.test(code)||!["verify","reset","admin"].includes(type))
    throw new Error("EMAIL_SEND_FAILED");
  let response;
  try{
    response=await fetcher(url.href,{
      method:"POST",
      headers:{"Content-Type":"text/plain;charset=utf-8","Accept":"application/json"},
      body:JSON.stringify({secret,email,code,type}),
      redirect:"follow", // Apps Script ContentService redirects to googleusercontent.com.
      signal:AbortSignal.timeout(15000)
    });
  }catch{
    const err=new Error("EMAIL_SEND_FAILED");
    err.providerName="GoogleAppsScript";err.providerStatus=0;throw err;
  }
  let result;
  try{result=await response.json();}catch{result=null;}
  if(!response.ok||result?.ok!==true){
    const err=new Error("EMAIL_SEND_FAILED");
    err.providerName="GoogleAppsScript";err.providerStatus=response.status;throw err;
  }
  return true;
}
// END Google Apps Script email transport

const encoder = new TextEncoder();

// Football is an owner-managed catalog. Streams are played directly by the client,
// without proxying another service or obtaining its credentials.
function footballHttps(value){
  const raw=typeof value==="string"?value.trim():"";
  if(!raw || raw.length>2048 || /[\s\u0000-\u001f\u007f]/.test(raw))return "";
  try{
    const u=new URL(raw),host=u.hostname.toLowerCase();
    if(u.protocol!=="https:" || u.username || u.password || (u.port&&u.port!=="443") ||
      !/^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z](?:[a-z0-9-]*[a-z0-9])?$/i.test(host) ||
      /(^|\.)(localhost|local|internal)$/.test(host))return "";
    u.hash="";return u.href;
  }catch{return "";}
}
function footballYoutube(value){
  const safe=footballHttps(value);if(!safe)return false;
  const u=new URL(safe),host=u.hostname;
  if(!["youtube.com","www.youtube.com","m.youtube.com","youtu.be"].includes(host))return false;
  const id=host==="youtu.be"?u.pathname.slice(1):u.pathname==="/watch"?u.searchParams.get("v"):u.pathname.match(/^\/(?:live|embed|shorts)\/([^/]+)$/)?.[1];
  return /^[A-Za-z0-9_-]{11}$/.test(id||"");
}
function footballText(value,max){
  return typeof value==="string" && value.trim().length>=2 && value.trim().length<=max && !/[\u0000-\u001f<>]/.test(value)?value.trim():"";
}
function footballChange(current,body){
  if(!Number.isSafeInteger(body.revision) || body.revision!==current.revision)return {status:409,error:"القائمة تغيرت؛ حدّثها قبل الحفظ كي لا تضيع تعديلات أخرى"};
  const next=structuredClone(current),channel=body.channel||{},match=body.match||{};
  if(body.action==="saveChannel"){
    const id=channel.id||"",index=next.channels.findIndex(c=>c.id===id);
    if(id && index<0)return {status:404,error:"القناة غير موجودة؛ حدّث القائمة"};
    const name=footballText(channel.name,80),category=footballText(channel.category,60),provider=footballText(channel.provider,120);
    const sourceUrl=footballHttps(channel.sourceUrl),streamUrl=footballHttps(channel.streamUrl),format=channel.format;
    if(!name||!category||!provider||!sourceUrl||!streamUrl||!["hls","dash","youtube","external"].includes(format)||typeof channel.enabled!=="boolean" || (format==="youtube"&&!footballYoutube(streamUrl)))return {status:400,error:"أدخل اسم القناة والقسم والناشر وروابط HTTPS صحيحة واختر نوع المشغل"};
    if(next.channels.some(c=>c.id!==id && c.streamUrl===streamUrl))return {status:409,error:"رابط القناة مضاف بالفعل؛ عدّل القناة الموجودة بدل تكرارها"};
    if(index<0 && next.channels.length>=40)return {status:409,error:"الحد الأقصى 40 قناة؛ أزل قناة قديمة أولًا"};
    const item={id:id||"fc-"+crypto.randomUUID(),name,category,provider,sourceUrl,streamUrl,format,enabled:channel.enabled};
    if(index<0)next.channels.push(item);else next.channels[index]=item;
  }else if(body.action==="deleteChannel"){
    if(!next.channels.some(c=>c.id===body.id))return {status:404,error:"القناة غير موجودة"};
    next.channels=next.channels.filter(c=>c.id!==body.id);
    next.matches=next.matches.map(m=>m.channelId===body.id?{...m,channelId:""}:m);
  }else if(body.action==="saveMatch"){
    const id=match.id||"",index=next.matches.findIndex(m=>m.id===id);
    if(id && index<0)return {status:404,error:"المباراة غير موجودة؛ حدّث القائمة"};
    const home=footballText(match.home,60),away=footballText(match.away,60),league=footballText(match.league,80),channelId=typeof match.channelId==="string"?match.channelId:"";
    const score=v=>v===null||v===undefined?null:Number.isInteger(v)&&v>=0&&v<=99?v:NaN;
    const homeScore=score(match.homeScore),awayScore=score(match.awayScore);
    if(!home||!away||home===away||!league||!Number.isSafeInteger(match.startsAt)||match.startsAt<1577836800000||match.startsAt>4102444800000||!["scheduled","live","finished"].includes(match.status)||Number.isNaN(homeScore)||Number.isNaN(awayScore)||(homeScore===null)!==(awayScore===null)||(channelId&&!next.channels.some(c=>c.id===channelId)))return {status:400,error:"راجع الفريقين والبطولة والموعد والحالة والنتيجة والقناة المختارة"};
    if(next.matches.some(m=>m.id!==id && m.home===home && m.away===away && m.startsAt===match.startsAt))return {status:409,error:"المباراة مضافة بالفعل في الموعد نفسه"};
    if(index<0 && next.matches.length>=80)return {status:409,error:"الحد الأقصى 80 مباراة؛ أزل مباراة قديمة أولًا"};
    const item={id:id||"fm-"+crypto.randomUUID(),home,away,league,startsAt:match.startsAt,status:match.status,channelId,homeScore:match.status==="scheduled"?null:homeScore,awayScore:match.status==="scheduled"?null:awayScore};
    if(index<0)next.matches.push(item);else next.matches[index]=item;
  }else if(body.action==="deleteMatch"){
    if(!next.matches.some(m=>m.id===body.id))return {status:404,error:"المباراة غير موجودة"};
    next.matches=next.matches.filter(m=>m.id!==body.id);
  }else return {status:400,error:"اختر إضافة أو تعديل أو إزالة قناة أو مباراة"};
  next.revision++;next.updatedAt=Date.now();
  if(encoder.encode(JSON.stringify(next)).length>96*1024)return {status:413,error:"حجم القائمة كبير؛ اختصر روابط المصادر أو أزل إدخالات قديمة"};
  return {catalog:next};
}

async function hashPassword(text){
  const buf = await crypto.subtle.digest("SHA-256", encoder.encode(String(text)));
  return [...new Uint8Array(buf)].map(b=>b.toString(16).padStart(2,"0")).join("");
}

// Cloudflare Workers WebCrypto currently rejects PBKDF2 rounds > 100,000.
// Keep the stored format versioned as pbkdf2:<rounds>:<salt>:<digest>.
// Any older hash with a different supported round count is still read by
// passwordMatches using the rounds embedded in its stored value.
const KDF_ITERATIONS=100000;
async function securePassword(password){
  const salt=crypto.getRandomValues(new Uint8Array(16));
  const key=await crypto.subtle.importKey('raw',encoder.encode(String(password)),{name:'PBKDF2'},false,['deriveBits']);
  const result=await crypto.subtle.deriveBits({name:'PBKDF2',salt,iterations:KDF_ITERATIONS,hash:'SHA-256'},key,256);
  const hex=bytes=>[...bytes].map(b=>b.toString(16).padStart(2,'0')).join('');
  return `pbkdf2:${KDF_ITERATIONS}:${hex(salt)}:${hex(new Uint8Array(result))}`;
}
async function passwordMatches(password, stored){
  if(!stored || typeof stored!=='string')return false;
  if(!stored.startsWith('pbkdf2:'))return stored===await hashPassword(password); // backwards-compatible accounts
  const parts=stored.split(':');
  const rounds=Number(parts[1]);
  if(parts.length!==4 || !Number.isInteger(rounds) || rounds<100000 || rounds>500000 || !/^[0-9a-f]{32}$/.test(parts[2]) || !/^[0-9a-f]{64}$/.test(parts[3]))return false;
  const salt=Uint8Array.from(parts[2].match(/../g),v=>parseInt(v,16));
  const key=await crypto.subtle.importKey('raw',encoder.encode(String(password)),{name:'PBKDF2'},false,['deriveBits']);
  const derived=new Uint8Array(await crypto.subtle.deriveBits({name:'PBKDF2',salt,iterations:rounds,hash:'SHA-256'},key,256));
  const expected=Uint8Array.from(parts[3].match(/../g),v=>parseInt(v,16));
  let mismatch=0;
  for(let i=0;i<expected.length;i++)mismatch|=derived[i]^expected[i];
  return mismatch===0;
}

function j(data,status=200){
  return new Response(JSON.stringify(data),{
    status,
    headers:{"content-type":"application/json;charset=utf-8","cache-control":"no-store","x-content-type-options":"nosniff"}
  });
}

// Valid legacy post payloads fit below this bound; reject before JSON.parse,
// including chunked requests with no trustworthy Content-Length.
const MAX_API_JSON_BYTES=4*1024*1024;
const RASTER_IMAGE_TYPES=new Set(["image/jpeg","image/png","image/webp","image/gif","image/avif","image/heic","image/heif","image/bmp","image/x-ms-bmp"]);
async function readApiJson(request){
  if(Number(request.headers.get("content-length"))>MAX_API_JSON_BYTES)return {error:j({ok:false,error:"حجم الطلب أكبر من المسموح"},413)};
  if(!request.body)return {body:{}};
  const reader=request.body.getReader(),chunks=[];let size=0;
  try{
    for(;;){
      const {done,value}=await reader.read();if(done)break;
      size+=value.byteLength;
      if(size>MAX_API_JSON_BYTES){await reader.cancel();return {error:j({ok:false,error:"حجم الطلب أكبر من المسموح"},413)};}
      chunks.push(value);
    }
  }finally{reader.releaseLock();}
  if(!size)return {body:{}};
  const bytes=new Uint8Array(size);let offset=0;
  for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.byteLength;}
  try{
    const body=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(bytes));
    if(!body || Array.isArray(body) || typeof body!=="object")throw Error();
    return {body};
  }catch{return {error:j({ok:false,error:"صيغة الطلب غير صالحة"},400)};}
}

const MAX_ADHAN_BYTES=20*1024*1024;
async function readAdhanAudio(request){
  const reader=request.body.getReader(),chunks=[];let size=0;
  try{
    for(;;){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;
      if(size>MAX_ADHAN_BYTES){await reader.cancel();return null;}chunks.push(value);}
  }finally{reader.releaseLock();}
  if(!size)return null;
  const bytes=new Uint8Array(size);let offset=0;
  for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.byteLength;}
  return bytes;
}
function isAdhanAudio(bytes){
  const text=(start,len)=>String.fromCharCode(...bytes.slice(start,start+len));
  return text(0,3)==="ID3" || text(0,4)==="OggS" || text(0,4)==="fLaC" ||
    (text(0,4)==="RIFF"&&text(8,4)==="WAVE") || text(4,4)==="ftyp" ||
    (bytes[0]===255 && (bytes[1]&0xe0)===0xe0);
}

// Merge legacy likes with modern reactions; one person has one visible reaction.
function postReactionMembers(post){
  const members=new Map();
  for(const name of Array.isArray(post.likes)?post.likes:[])if(typeof name==="string"&&name)members.set(name,"like");
  for(const kind of ["like","love","haha","wow","sad","angry","dislike"]){
    for(const name of Array.isArray(post.reactions?.[kind])?post.reactions[kind]:[])if(typeof name==="string"&&name)members.set(name,kind);
  }
  return members;
}

export class SignalingRoom extends DurableObject {
  constructor(ctx, env){
    super(ctx, env);
    this.ctx=ctx;
    this.env=env;
    this.activeOperations=new Set();
    this.resetClosing=false;
    this.resetStepBusy=false;
    this.accountDeleteBusy=false;
    this.accountDeleteClosing=false;
    this.accountStatusBusy=new Set();
  }

  trackOperation(fn){
    const operation=Promise.resolve().then(fn);
    this.activeOperations.add(operation);
    operation.finally(()=>this.activeOperations.delete(operation)).catch(()=>{});
    return operation;
  }

  async resetActive(){
    const state=await this.ctx.storage.get("owner-reset:plan");
    if(state && !["accounts","posts"].includes(state.kind)){
      await this.ctx.storage.delete("owner-reset:plan");this.resetClosing=false;return false;
    }
    return this.resetClosing || state?.phase==="deleting" || this.accountDeleteClosing || !!await this.ctx.storage.get("admin-delete:job");
  }

  ownerEmail(){return String(this.env.SUPER_ADMIN_EMAIL||"").trim().toLowerCase();}

  ownerRegistrationAllowed(username,email){
    return username!==this.ownerUsername() || !!this.ownerEmail() && String(email||"").trim().toLowerCase()===this.ownerEmail();
  }

  async resetInventory(){
    const byCategory={};let cursor,total=0;
    do{
      const page=await this.ctx.storage.list({limit:200,...(cursor?{startAfter:cursor}:{})});
      const keys=[...page.keys()];
      for(const key of keys){
        if(key==="owner-reset:plan")continue;
        const category=key.includes(":")?key.split(":",1)[0]:key;
        byCategory[category]=(byCategory[category]||0)+1;total++;
      }
      cursor=keys.length===200?keys.at(-1):undefined;
    }while(cursor);
    let media={configured:!!this.env.MEDIA_BUCKET,count:0,bytes:0};
    if(this.env.MEDIA_BUCKET){
      let next;
      do{
        const page=await this.env.MEDIA_BUCKET.list({limit:1000,...(next?{cursor:next}:{})});
        for(const object of page.objects){media.count++;media.bytes+=Number(object.size||0);}
        next=page.truncated?page.cursor:undefined;
      }while(next);
    }
    return {durableObjectKeys:total,byCategory,media,keepAccount:this.ownerUsername(),keptAccounts:[this.ownerUsername()],deleteOwnerAccount:false};
  }

  async visiblePost(post,viewer,depth=0){
    if(!post || !await this.canSeeContent(viewer,post.author))return null;
    const out={...post,likes:[],comments:[],reactions:{}};
    const identities=new Map(),commentPeople=new Map();
    const identity=async name=>{
      if(identities.has(name))return identities.get(name);
      const user=await this.user(name);
      let result=null;
      if(user && !user.banned && !user.disabled && !await this.isBlockedBetween(viewer,name)){
        // Per-response opaque aliases retain distinct counts without exposing a
        // username, avatar, profile link, or a cross-post tracking identifier.
        result=user.accountHidden && name!==viewer?`~anonymous:${identities.size}`:name;
      }
      identities.set(name,result);return result;
    };
    for(const name of post.likes||[]){const id=await identity(name);if(id)out.likes.push(id)}
    for(const [kind,names] of Object.entries(post.reactions||{})){
      out.reactions[kind]=[];
      for(const name of Array.isArray(names)?names:[]){const id=await identity(name);if(id)out.reactions[kind].push(id)}
    }
    const commentView=async comment=>{
      const author=await identity(comment.author);if(!author)return null;
      // Feed previews need names, not an embedded copy of the same photo in
      // every comment. Full avatars are loaded by the paginated people panel.
      if(!commentPeople.has(author))commentPeople.set(author,await this.interactionPerson(author,false));
      return {...comment,author,authorInfo:commentPeople.get(author)};
    };
    for(const comment of post.comments||[]){
      const visible=await commentView(comment);if(!visible)continue;
      const replies=[];
      for(const reply of comment.replies||[]){const item=await commentView(reply);if(item)replies.push(item)}
      out.comments.push({...visible,replies});
    }
    delete out.sharedPost;
    if(post.sharedPostId){
      const original=await this.ctx.storage.get(`post:${post.sharedPostId}`);
      const shared=original && !original.archived && depth<3?await this.visiblePost(original,viewer,depth+1):null;
      if(shared)out.sharedPost={...shared,authorInfo:await this.publicUser(shared.author)};
      else {delete out.sharedPostId;delete out.sharedFrom;out.sharedPost=null;}
    }
    return out;
  }

  sockets(){ try{return this.ctx.getWebSockets()}catch{return []} }
  meta(ws){ try{return ws.deserializeAttachment()||{}}catch{return {}} }
  setMeta(ws,data){ try{ws.serializeAttachment(data)}catch{} }
  send(ws,obj){ try{ws.send(JSON.stringify(obj));return true}catch{return false} }

  findUser(username){
    for(const ws of this.sockets()){
      if(this.meta(ws).user===username)return ws;
    }
    return null;
  }

  async accountState(user){
    if(!user)return null;
    const state=await this.ctx.storage.get(`account-state:${user.username}`);
    return state?{...user,banned:!!state.banned,disabled:!!state.disabled,moderationVersion:Number(state.version||0)}:user;
  }

  async user(username){
    return this.accountState(await this.ctx.storage.get(`user:${username}`)||null);
  }

  async publicUser(username){
    const u=await this.user(username);
    if(!u || u.banned || u.disabled)return null;
    return {
      username:u.username,
      displayName:u.displayName||u.username,
      fullName:u.fullName||u.displayName||u.username,
      country:u.country||"",
      city:u.city||"",
      education:u.education||"",
      work:u.work||"",
      createdAt:u.createdAt||null,
      allowFriendRequests:u.allowFriendRequests!==false,
      bio:u.bio||"",
      cover:u.cover||"",
      accountPrivate:!!u.accountPrivate,
      accountHidden:!!u.accountHidden,
      avatar:u.avatar||"",
      isOnline:!!this.findUser(username),
      lastSeen:Number(await this.ctx.storage.get(`lastSeen:${username}`))||0
    };
  }


  // A hidden account is never discoverable by search, presence lists or public friend lists.
  // Direct access is reserved for its owner, confirmed friends and the receiver of a
  // friend request initiated by the hidden account. A friendship never makes it public.
  async canViewProfile(viewer, target){
    if(!viewer || !target || await this.isBlockedBetween(viewer,target))return false;
    const u=await this.user(target);
    if(!u || u.banned || u.disabled)return false;
    if(viewer===target)return true;
    if(!u.accountHidden)return true;
    if(await this.canCommunicate(viewer,target))return true;
    return (await this.incomingFriendRequests(viewer)).some(r=>r.from===target);
  }

  async friends(username){
    return await this.ctx.storage.get(`friends:${username}`)||[];
  }

  async incomingFriendRequests(username){
    return await this.ctx.storage.get(`friendIncoming:${username}`)||[];
  }

  async outgoingFriendRequests(username){
    return await this.ctx.storage.get(`friendOutgoing:${username}`)||[];
  }

  async friendState(me,target){
    if(me===target)return "self";
    if((await this.friends(me)).includes(target))return "friends";
    if((await this.incomingFriendRequests(me)).some(r=>r.from===target))return "incoming";
    if((await this.outgoingFriendRequests(me)).some(r=>r.to===target))return "outgoing";
    return "none";
  }

  async blockedUsers(username){
    return await this.ctx.storage.get(`blocked:${username}`)||[];
  }

  async isBlockedBetween(a,b){
    if(!a || !b)return false;
    return (await this.blockedUsers(a)).includes(b) || (await this.blockedUsers(b)).includes(a);
  }

  // One authorization policy for HTTP chat, WebSocket chat, call signaling and presence.
  async canCommunicate(a,b){
    if(!a || !b || a===b || await this.isBlockedBetween(a,b))return false;
    const ua=await this.user(a),ub=await this.user(b);
    if(!ua || !ub || ua.banned || ub.banned || ua.disabled || ub.disabled)return false;
    return (await this.friends(a)).includes(b) && (await this.friends(b)).includes(a);
  }

  async canSeeContent(viewer,author){
    if(!author)return false;
    if(await this.isBlockedBetween(viewer,author))return false;
    const user=await this.user(author);
    if(!user || user.banned || user.disabled)return false;
    if(viewer===author)return true;
    return !!user && ((user.accountHidden || user.accountPrivate)
      ? await this.canCommunicate(viewer,author) : true);
  }

  async interactionPerson(username,includeAvatar=true){
    if(String(username).startsWith("~anonymous:"))return {username,fullName:"حساب مخفي",displayName:"حساب مخفي",avatar:"",anonymous:true};
    const u=await this.publicUser(username);
    return u?{username:u.username,fullName:u.fullName,displayName:u.displayName,...(includeAvatar?{avatar:u.avatar}:{})}:null;
  }

  // Following/sharing still reveals identity and retains its prior policy.
  async canRevealInteraction(actor,audience){
    const u=await this.user(actor);
    return !u?.accountHidden || actor===audience || await this.canCommunicate(actor,audience);
  }

  async setBlock(username,target,block){
    const list=await this.blockedUsers(username);
    const next=block?[...new Set([...list,target])]:list.filter(name=>name!==target);
    await this.ctx.storage.put(`blocked:${username}`,next);
    if(block){
      for(const [a,b] of [[username,target],[target,username]]){
        await this.ctx.storage.put(`friends:${a}`,(await this.friends(a)).filter(x=>x!==b));
        await this.ctx.storage.put(`friendIncoming:${a}`,(await this.incomingFriendRequests(a)).filter(x=>x.from!==b));
        await this.ctx.storage.put(`friendOutgoing:${a}`,(await this.outgoingFriendRequests(a)).filter(x=>x.to!==b));
      }
    }
  }

  async createSession(username){
    const token=crypto.randomUUID()+crypto.randomUUID();
    const user=await this.user(username);
    await this.ctx.storage.put(`session:${token}`,{username,createdAt:Date.now(),authVersion:Number(user?.authVersion||0),moderationVersion:Number(user?.moderationVersion||0)});
    return token;
  }

  async session(token){
    const session=token ? (await this.ctx.storage.get(`session:${token}`)||null) : null;
    if(!session)return null;
    const u=await this.user(session.username);
    if(!u || u.banned || u.disabled || Number(session.moderationVersion||0)!==Number(u.moderationVersion||0) || Number(session.authVersion||0)!==Number(u.authVersion||0))return null;
    // Keep the existing sessions working, but cap lifetime for safety.
    if(Date.now()-Number(session.createdAt||0)>30*24*60*60*1000)return null;
    return session;
  }

  ownerUsername(){
    return String(this.env.SUPER_ADMIN_USERNAME||"").trim().toLowerCase();
  }

  async roleFor(username){
    const name=String(username||"").trim().toLowerCase();
    if(name && name===this.ownerUsername()){
      const user=await this.user(name);
      if(user && (!this.ownerEmail() || String(user.email||"").toLowerCase()===this.ownerEmail()))return "super_admin";
    }
    const moderators=await this.ctx.storage.get("admin-moderators:v2")||[];
    return name && moderators.includes(name)?"moderator":"user";
  }

  async requireAdmin(token, allowModerator=false){
    const session=await this.session(String(token||""));
    if(!session)return {error:j({ok:false,error:"الجلسة منتهية"},401)};
    const role=await this.roleFor(session.username);
    // All administration, including backups, is restricted to the configured owner.
    if(role!=="super_admin" && !(allowModerator && role==="moderator"))return {error:j({ok:false,error:"صلاحيات المدير الرئيسي فقط"},403)};
    return {session,role};
  }

  async requireAdminPassword(password){
    if(this.adminPasswordBusy)return j({ok:false,error:"تحقق حماية قيد التنفيذ؛ حاول بعد قليل"},409);
    this.adminPasswordBusy=true;
    try{
      const owner=this.ownerUsername(),record=await this.ctx.storage.get(`admin-security:${owner}`);
      if(!record)return j({ok:false,error:"اضبط كلمة حماية الإدارة أولًا باستخدام كود بريد المدير",code:"ADMIN_PASSWORD_SETUP_REQUIRED"},403);
      const key=`admin-security-attempts:${owner}`,storedAttempts=await this.ctx.storage.get(key),attempts=storedAttempts||{count:0,until:0};
      if(attempts.until>Date.now())return j({ok:false,error:"محاولات حماية كثيرة؛ حاول بعد 15 دقيقة",code:"ADMIN_PASSWORD_LOCKED"},429);
      if(attempts.until)attempts.count=0;
      if(typeof password!=="string" || password.length>128 || !await passwordMatches(password,record.hash)){
        attempts.count++;attempts.until=attempts.count>=5?Date.now()+900000:0;
        await this.ctx.storage.put(key,attempts);
        return j({ok:false,error:"كلمة حماية الإدارة غير صحيحة",code:"ADMIN_PASSWORD_REQUIRED"},403);
      }
      if(storedAttempts)await this.ctx.storage.delete(key);return null;
    }finally{this.adminPasswordBusy=false;}
  }

  async footballApi(request,url){
    const admin=url.pathname==="/api/admin/football";
    if(request.method!=="GET" && (!admin||request.method!=="POST"))return j({ok:false,error:"طريقة الطلب غير مدعومة"},405);
    let body={};
    if(request.method==="POST"){
      if(Number(request.headers.get("content-length")||0)>16*1024)return j({ok:false,error:"حجم الطلب أكبر من المسموح"},413);
      const reader=request.body?.getReader();if(!reader)return j({ok:false,error:"بيانات الطلب غير مكتملة"},400);
      const chunks=[];let size=0;
      try{
        for(;;){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;
          if(size>16*1024){await reader.cancel();return j({ok:false,error:"حجم الطلب أكبر من المسموح"},413);}chunks.push(value);}
        const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
        body=JSON.parse(new TextDecoder().decode(bytes));if(!body||Array.isArray(body)||typeof body!=="object")throw Error();
      }catch{return j({ok:false,error:"بيانات الطلب غير صحيحة"},400);}finally{reader.releaseLock();}
    }
    if(admin){const auth=await this.requireAdmin(request.method==="GET"?url.searchParams.get("token"):body.token);if(auth.error)return auth.error;}
    const empty=()=>({revision:0,updatedAt:0,channels:[],matches:[]});
    if(request.method==="GET"){
      const catalog=await this.ctx.storage.get("football:catalog")||empty();
      const channels=admin?catalog.channels:catalog.channels.filter(c=>c.enabled);
      const matches=admin?catalog.matches:catalog.matches.map(m=>channels.some(c=>c.id===m.channelId)?m:{...m,channelId:""});
      const response=j({ok:true,...catalog,channels,matches});response.headers.set("cache-control","no-store");return response;
    }
    const result=await this.ctx.storage.transaction(async storage=>{
      const current=await storage.get("football:catalog")||empty(),changed=footballChange(current,body);
      if(changed.catalog)await storage.put("football:catalog",changed.catalog);
      return changed;
    });
    return result.catalog?j({ok:true,...result.catalog}):j({ok:false,error:result.error},result.status);
  }

  // v1.0.14: calendar date is authoritative; do not trust submitted numeric age.
  birthDateAge(value){
    const raw=String(value||"").trim();
    if(!/^\d{4}-\d{2}-\d{2}$/.test(raw))return null;
    const [y,m,d]=raw.split("-").map(Number);
    const birth=new Date(Date.UTC(y,m-1,d));
    if(birth.getUTCFullYear()!==y || birth.getUTCMonth()!==m-1 || birth.getUTCDate()!==d)return null;
    const now=new Date();
    let age=now.getUTCFullYear()-y;
    if(now.getUTCMonth()+1<m || (now.getUTCMonth()+1===m && now.getUTCDate()<d))age--;
    return age>=13 && age<=120 ? age : null;
  }

  normalizeRegistration(body={}){
    return {
      fullName:String(body.fullName||"").trim(),
      username:String(body.username||"").trim().toLowerCase(),
      birthDate:String(body.birthDate||"").trim(),
      age:this.birthDateAge(body.birthDate),
      phone:String(body.phone||"").trim().replace(/\s+/g,""),
      email:String(body.email||"").trim().toLowerCase(),
      gender:String(body.gender||"").trim().toLowerCase(),
      accountHidden:body.accountHidden,
      country:String(body.country||"").trim(),
      password:String(body.password||""),
      confirmPassword:String(body.confirmPassword||"")
    };
  }

  async validateRegistration(reg){
    if(reg.fullName.length<3)return "اكتب الاسم الكامل";
    if(!/^[a-z0-9_]{3,24}$/.test(reg.username))return "اسم المستخدم من 3 إلى 24 حرفًا إنجليزيًا أو رقمًا أو _";
    if(reg.age===null)return "اختر تاريخ ميلاد صحيحًا من التقويم (الحد الأدنى للعمر 13 عامًا)";
    if(!["male","female"].includes(reg.gender))return "اختيار النوع (ذكر أو أنثى) إجباري";
    if(reg.gender==="female" && typeof reg.accountHidden!=="boolean")return "اختاري هل تريدين إخفاء الحساب أم لا";
    if(reg.gender==="male" && reg.accountHidden===true)return "خيار إخفاء الحساب متاح عند اختيار أنثى فقط";
    if(!reg.email)return "أدخل البريد الإلكتروني لاستلام كود التأكيد";
    if(reg.phone && !/^\+?[0-9]{8,15}$/.test(reg.phone))return "رقم الهاتف غير صحيح";
    if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(reg.email))return "البريد الإلكتروني غير صحيح";
    if(!this.ownerRegistrationAllowed(reg.username,reg.email))return "اسم المدير محجوز للبريد المعتمد في إعدادات Cloudflare";
    if(reg.password.length<6)return "كلمة المرور 6 أحرف على الأقل";
    if(reg.password!==reg.confirmPassword)return "كلمتا المرور غير متطابقتين";
    if(await this.user(reg.username))return "اسم المستخدم موجود بالفعل";

    const existingUsers=await this.ctx.storage.get("usernames")||[];

    for(const existingName of existingUsers){
      const existing=await this.user(existingName);
      if(!existing)continue;
      if(reg.phone && existing.phone===reg.phone)return "رقم الهاتف مستخدم بالفعل";
      if(existing.email===reg.email)return "البريد الإلكتروني مستخدم بالفعل";
    }

    return "";
  }

  maskEmail(email){
    const [name,domain]=String(email||"").split("@");
    if(!domain)return email;
    const visible=name.slice(0,Math.min(2,name.length));
    return `${visible}${"*".repeat(Math.max(2,name.length-visible.length))}@${domain}`;
  }

  // Single durable record avoids unsupported TTL options and unbounded minute keys.
  async reserveGoogleEmailQuota(){
    const now=Date.now();
    const minute=Math.floor(now/60000);
    const day=new Date(now).toISOString().slice(0,10);
    const previous=await this.ctx.storage.get("google-email-quota")||{};
    const minuteCount=previous.minute===minute?Number(previous.minuteCount||0):0;
    const dayCount=previous.day===day?Number(previous.dayCount||0):0;
    if(minuteCount>=10||dayCount>=80){
      const err=new Error("EMAIL_SEND_RATE_LIMITED");
      err.providerName="GoogleAppsScript";throw err;
    }
    await this.ctx.storage.put("google-email-quota",{
      minute,minuteCount:minuteCount+1,day,dayCount:dayCount+1
    });
  }

  async sendGoogleEmailIfReady(email,code,type){
    if(!String(this.env.GAS_EMAIL_WEBAPP_URL||"").trim() ||
       !String(this.env.GAS_EMAIL_API_SECRET||"").trim())return false;
    await this.reserveGoogleEmailQuota();
    await sendViaGoogleAppsScript(this.env,email,code,type);
    return true;
  }

  async sendVerificationEmail(email,code,adminSecurity=false){
    if(await this.sendGoogleEmailIfReady(email,code,adminSecurity?"admin":"verify"))return true;
    const apiKey=String(this.env.RESEND_API_KEY||"").trim();
    const from=String(
      this.env.VERIFY_FROM_EMAIL||
      "Tawasol Al-Atta <onboarding@resend.dev>"
    ).trim();

    if(!apiKey){
      throw new Error("EMAIL_PROVIDER_NOT_CONFIGURED");
    }

    const response=await fetch("https://api.resend.com/emails",{
      method:"POST",
      headers:{
        "Authorization":`Bearer ${apiKey}`,
        "Content-Type":"application/json"
      },
      body:JSON.stringify({
        from,
        to:[email],
        subject:adminSecurity?"حماية إدارة تواصل العطا":"كود تأكيد حساب تواصل العطا",
        html:`<!doctype html>
          <html dir="rtl" lang="ar">
            <body style="margin:0;background:#0a0d10;font-family:Arial,sans-serif;color:#fff">
              <div style="max-width:520px;margin:30px auto;padding:28px;background:#11161a;border:1px solid #d7a936;border-radius:18px;text-align:center">
                <h2 style="color:#f1c75b;margin:0 0 14px">تواصل العطا</h2>
                <p style="line-height:1.8;color:#ddd">${adminSecurity?"استخدم الكود لضبط كلمة مرور حماية الإدارة. لا تشاركه مع أي شخص:":"استخدم الكود التالي لتأكيد إنشاء حسابك:"}</p>
                <div style="font-size:34px;letter-spacing:9px;font-weight:800;color:#f7d778;padding:18px 8px">${code}</div>
                <p style="color:#aaa;font-size:13px">الكود صالح لمدة 10 دقائق. إذا لم تطلب هذا الإجراء فتجاهل الرسالة.</p>
              </div>
            </body>
          </html>`
      })
    });

    const text=await response.text();

    if(!response.ok){
      let providerMessage="";
      let providerName="";
      try{
        const parsed=JSON.parse(text||"{}");
        providerMessage=String(parsed.message||parsed.error||"").slice(0,300);
        providerName=String(parsed.name||"").slice(0,100);
      }catch{
        providerMessage=String(text||"").slice(0,300);
      }

      console.error("Verification email provider error",response.status,providerName,providerMessage);

      const err=new Error("EMAIL_SEND_FAILED");
      err.providerStatus=response.status;
      err.providerName=providerName;
      err.providerMessage=providerMessage;
      throw err;
    }

    return true;
  }

  async sendPasswordResetEmail(email,code){
    if(await this.sendGoogleEmailIfReady(email,code,"reset"))return true;
    const key=String(this.env.RESEND_API_KEY||'').trim();
    if(!key)throw new Error('EMAIL_PROVIDER_NOT_CONFIGURED');
    const from=String(this.env.VERIFY_FROM_EMAIL||'Tawasol Al-Atta <onboarding@resend.dev>').trim();
    const response=await fetch('https://api.resend.com/emails',{
      method:'POST',headers:{Authorization:`Bearer ${key}`,'Content-Type':'application/json'},
      body:JSON.stringify({from,to:[email],subject:'استعادة كلمة المرور — تواصل العطا',
        html:`<div dir="rtl" style="font-family:Arial;padding:24px">كود استعادة كلمة المرور في تواصل العطا: <strong>${code}</strong><p>ينتهي بعد 10 دقائق. تجاهل الرسالة إن لم تطلبها.</p></div>`})
    });
    if(!response.ok)throw new Error('EMAIL_SEND_FAILED');
  }

  async allRecords(prefix){
    const records=[];let cursor;
    do{
      const page=await this.ctx.storage.list({prefix,limit:300,...(cursor?{startAfter:cursor}:{})});
      for(const value of page.values())records.push(prefix==="user:"?await this.accountState(value):value);
      cursor=page.size===300?[...page.keys()].at(-1):undefined;
    }while(cursor);
    return records;
  }

  async adminStatistics(){
    const users=await this.allRecords("user:"),posts=await this.allRecords("post:");
    // Calendar days use the owner's Saudi timezone, including the midnight boundary.
    const dayKey=time=>new Date(Number(time)+3*3600000).toISOString().slice(0,10);
    const now=Date.now(),today=dayKey(now);
    const daily=Array.from({length:7},(_,i)=>({date:dayKey(now-(6-i)*86400000),users:0,posts:0}));
    const byDay=new Map(daily.map(d=>[d.date,d]));
    for(const [items,field] of [[users,"users"],[posts,"posts"]])for(const item of items){
      const timestamp=Number(item?.createdAt||0);
      if(timestamp>0 && timestamp<=now){const day=byDay.get(dayKey(timestamp));if(day)day[field]++;}
    }
    const enabled=users.filter(u=>!u.banned && !u.disabled);
    const reports=await this.ctx.storage.get("reports")||[];
    const deletion=await this.ctx.storage.get("admin-delete:job");
    return {timezone:"Asia/Riyadh",generatedAt:now,daily,counts:{
      users:users.length,posts:posts.length,active:enabled.length,
      banned:users.filter(u=>u.banned).length,disabled:users.filter(u=>u.disabled).length,
      online:enabled.filter(u=>!!this.findUser(u.username)).length,
      archivedPosts:posts.filter(p=>p.archived).length,reports:reports.filter(r=>!r.resolved).length,
      usersToday:byDay.get(today).users,postsToday:byDay.get(today).posts,
      users7Days:daily.reduce((n,d)=>n+d.users,0),posts7Days:daily.reduce((n,d)=>n+d.posts,0)
    },deletion:deletion?{username:deletion.username,phase:deletion.phase,deletedMedia:deletion.deletedMedia}:null};
  }

  disconnectAccount(username,reason="account-disabled"){
    for(const ws of this.sockets())if(this.meta(ws).user===username)try{
      this.send(ws,{type:reason});ws.close(1008,"Account unavailable");
    }catch{}
  }

  async deleteAccountStep(auth,body,bulk=false){
    const target=String(body.username||"").trim().toLowerCase();
    if(!/^[a-z0-9_]{3,24}$/.test(target))return j({ok:false,error:"اسم المستخدم غير صالح"},400);
    if(target===this.ownerUsername() || target===auth.session.username)return j({ok:false,error:"لا يمكن حذف حساب المدير الرئيسي من إدارة الحسابات"},403);
    if(body.confirmation!==target)return j({ok:false,error:"اكتب اسم المستخدم نفسه لتأكيد الحذف النهائي"},400);
    if(this.accountDeleteBusy)return j({ok:false,error:"دفعة حذف قيد التنفيذ؛ أعد المحاولة",code:"DELETE_BUSY"},409);
    let job=await this.ctx.storage.get("admin-delete:job");
    if(job && job.username!==target)return j({ok:false,error:"استكمل حذف الحساب السابق من لوحة الإدارة أولًا"},409);
    const user=await this.user(target);
    if(!job && !user)return j({ok:false,error:"المستخدم غير موجود"},404);
    if(!this.env.MEDIA_BUCKET)return j({ok:false,error:"اربط MEDIA_BUCKET لحذف ملفات الحساب أيضًا"},409);
    if(this.accountDeleteBusy)return j({ok:false,error:"دفعة حذف قيد التنفيذ؛ أعد المحاولة",code:"DELETE_BUSY"},409);
    if(!bulk && (this.resetClosing || this.resetStepBusy))return j({ok:false,error:"توجد عملية تصفير جارية",code:"RESET_IN_PROGRESS"},409);
    this.accountDeleteBusy=true;
    try{
      this.accountDeleteClosing=true;
      if(!job){
        job={username:target,email:String(user.email||"").toLowerCase(),phone:String(user.phone||""),phase:"inventory",cursor:null,
          postIds:[],storyIds:[],mediaBatches:0,nextBatch:0,deletedMedia:0};
        await this.ctx.storage.put("admin-delete:job",job);
      }
      // Keep the gate durable across failures and restarts; drain writes already authorized.
      await Promise.allSettled([...this.activeOperations]);
      this.disconnectAccount(target,"account-deleted");
      if(job.phase==="inventory"){
        const page=await this.ctx.storage.list({limit:150,...(job.cursor?{startAfter:job.cursor}:{})});
        for(const [key,value] of page){
          if(key.startsWith("post:") && value.author===target)job.postIds.push(key.slice(5));
          if(key.startsWith("story:") && value.author===target)job.storyIds.push(key.slice(6));
        }
        job.postIds=[...new Set(job.postIds)];job.storyIds=[...new Set(job.storyIds)];
        job.cursor=page.size===150?[...page.keys()].at(-1):null;
        if(!job.cursor)job.phase="media_inventory";
      }else if(job.phase==="media_inventory"){
        // Inventory completes BEFORE deleting R2 keys, so pagination cannot skip objects.
        const page=await this.env.MEDIA_BUCKET.list({limit:300,include:["customMetadata"],...(job.cursor?{cursor:job.cursor}:{})});
        const keys=page.objects.filter(o=>o.customMetadata?.owner===target).map(o=>o.key);
        if(keys.length){await this.ctx.storage.put(`admin-delete-media:${job.mediaBatches}`,keys);job.mediaBatches++;}
        job.cursor=page.truncated?page.cursor:null;
        if(!job.cursor)job.phase="media_delete";
      }else if(job.phase==="media_delete"){
        if(job.nextBatch<job.mediaBatches){
          const key=`admin-delete-media:${job.nextBatch}`,keys=await this.ctx.storage.get(key)||[];
          if(keys.length)await this.env.MEDIA_BUCKET.delete(keys);
          job.deletedMedia+=keys.length;job.nextBatch++;
          // Save progress before removing its manifest; repeated deletes are safe.
          await this.ctx.storage.put("admin-delete:job",job);
          await this.ctx.storage.delete(key);
        }else{job.phase="cleanup";job.cursor=null;}
      }else if(job.phase==="cleanup"){
        const page=await this.ctx.storage.list({limit:100,...(job.cursor?{startAfter:job.cursor}:{})});
        const posts=new Set(job.postIds),stories=new Set(job.storyIds);
        const resetRateKey=`reset-rate:${await hashPassword(job.email)}`;
        const remove=async key=>this.ctx.storage.delete(key);
        const ownPrefixes=["admin-security","admin-security-code","admin-security-attempts","account-state","friends","friendIncoming","friendOutgoing","blocked","following","followers","notifications","saved","calls","callSignals","fcmTokens","hmsTokens","legal-consent","deletion-request","report-rate","lastSeen","lastCallPushResult","pushTestLast"];
        for(const [key,value] of page){
          if(key==="admin-delete:job")continue;
          if(key.startsWith("admin-delete-media:")){await remove(key);continue;}
          if(key===`user:${target}` || ownPrefixes.some(p=>key===`${p}:${target}`) ||
            key===`verify-email-rate:${job.email}` || key===resetRateKey ||
            key.startsWith("login-fail:") && [target,job.email,job.phone].filter(Boolean).includes(key.slice(11)) ||
            key.startsWith("session:") && value?.username===target ||
            key.startsWith("google-account:") && value===target || ["fcmOwner:","hmsOwner:"].some(p=>key.startsWith(p)) && value===target ||
            key.startsWith("pending-registration:") && (value?.registration?.username===target || value?.registration?.email===job.email) ||
            key.startsWith("reset:") && value?.username===target ||
            key.startsWith("messages:") && key.slice(9).split(":").includes(target)){
            await remove(key);continue;
          }
          let next=value;
          if(key==="usernames" || key==="moderators" || key==="admin-moderators:v2")next=(value||[]).filter(x=>x!==target);
          else if(key==="postIds")next=(value||[]).filter(x=>!posts.has(x));
          else if(key==="storyIds")next=(value||[]).filter(x=>!stories.has(x));
          else if(key.startsWith("post:")){
            if(value.author===target){await remove(key);continue;}
            next={...value,likes:(value.likes||[]).filter(x=>x!==target),reactions:Object.fromEntries(Object.entries(value.reactions||{}).map(([k,v])=>[k,Array.isArray(v)?v.filter(x=>x!==target):v])),
              comments:(value.comments||[]).filter(c=>c.author!==target).map(c=>({...c,replies:(c.replies||[]).filter(r=>r.author!==target)}))};
            if(posts.has(next.sharedPostId)){delete next.sharedPostId;delete next.sharedFrom;delete next.sharedPost;}
          }else if(key.startsWith("story:") && value.author===target){await remove(key);continue;}
          else if(key.startsWith("group:")){
            next={...value,members:(value.members||[]).filter(x=>x!==target)};
            if(next.createdBy===target)next.createdBy="";
            if(next.owner===target)next.owner="";
          }else if(key.startsWith("messages:"))next=(value||[]).filter(m=>m.from!==target && m.to!==target);
          else if(["friends:","blocked:","followers:","following:"].some(p=>key.startsWith(p)))next=(value||[]).filter(x=>x!==target);
          else if(key.startsWith("friendIncoming:"))next=(value||[]).filter(x=>x.from!==target);
          else if(key.startsWith("friendOutgoing:"))next=(value||[]).filter(x=>x.to!==target);
          else if(key.startsWith("saved:"))next=(value||[]).filter(x=>!posts.has(x));
          else if(["notifications:","callSignals:","calls:"].some(p=>key.startsWith(p)))next=(value||[]).filter(x=>![x.from,x.to,x.by,x.username,x.peer,x.with].includes(target) && !posts.has(x.postId) && !stories.has(x.storyId));
          else if(key==="reports")next=(value||[]).filter(x=>![x.reporter,x.username,x.targetUsername].includes(target) && !(x.targetType==="user" && x.targetId===target) && !posts.has(x.targetId));
          if(next!==value)await this.ctx.storage.put(key,next);
        }
        job.cursor=page.size===100?[...page.keys()].at(-1):null;
        if(!job.cursor){
          await this.ctx.storage.delete("admin-delete:job");
          this.accountDeleteClosing=false;
          return j({ok:true,done:true,username:target,deletedMedia:job.deletedMedia});
        }
      }
      await this.ctx.storage.put("admin-delete:job",job);
      return j({ok:true,done:false,username:target,phase:job.phase,deletedMedia:job.deletedMedia});
    }catch(err){
      console.error("Account deletion paused",err);
      return j({ok:false,error:"توقف الحذف مؤقتًا؛ استكمله من لوحة الإدارة. التطبيق ينتظر اكتمال الحذف",code:"ACCOUNT_DELETE_RETRY"},503);
    }finally{this.accountDeleteBusy=false;}
  }

  async api(request){
    const path=new URL(request.url).pathname;
    const deleting=!!await this.ctx.storage.get("admin-delete:job");
    if(path.startsWith("/api/admin/reset/")){
      const bulk=await this.ctx.storage.get("owner-reset:plan");
      if((deleting || this.accountDeleteClosing) && !(bulk?.kind==="accounts" && bulk.phase==="deleting"))return j({ok:false,error:"استكمل حذف الحساب الجاري أولًا",code:"ACCOUNT_DELETE_IN_PROGRESS"},409);
      return this.apiRequest(request);
    }
    if(path==="/api/admin/users/delete"){
      const reset=await this.ctx.storage.get("owner-reset:plan");
      if(this.resetClosing || reset?.phase==="deleting")return j({ok:false,error:"توجد عملية تصفير جارية",code:"RESET_IN_PROGRESS"},503);
      return this.apiRequest(request);
    }
    let adminRead=request.method==="GET" && ["/api/me","/api/admin/users","/api/admin/summary","/api/admin/security"].includes(path);
    if(path==="/api/me" && adminRead){const auth=await this.requireAdmin(new URL(request.url).searchParams.get("token"));adminRead=!auth.error;}
    if(deleting || this.accountDeleteClosing){
      if(!adminRead)return j({ok:false,error:"جاري حذف حساب بواسطة الإدارة؛ حاول بعد قليل",code:"ACCOUNT_DELETE_IN_PROGRESS"},503);
    }else if(!adminRead && await this.resetActive())return j({ok:false,error:"التطبيق تحت التصفير؛ أعد المحاولة بعد اكتماله",code:"RESET_IN_PROGRESS"},503);
    return this.trackOperation(()=>this.apiRequest(request));
  }

  async apiRequest(request){
    const url=new URL(request.url);

    if(url.pathname==="/api/diagnostics/env" && request.method==="GET"){
      const resend=String(this.env.RESEND_API_KEY||"").trim();
      const googleEmailUrl=String(this.env.GAS_EMAIL_WEBAPP_URL||"").trim();
      const googleEmailSecret=String(this.env.GAS_EMAIL_API_SECRET||"").trim();
      const turnId=String(this.env.TURN_KEY_ID||"").trim();
      const turnToken=String(this.env.TURN_KEY_API_TOKEN||"").trim();

      return j({
        ok:true,
        diagnostics:{
          resendApiKeyConfigured:!!resend,
          resendApiKeyLooksValid:resend.startsWith("re_"),
          googleAppsEmailUrlConfigured:!!googleEmailUrl,
          googleAppsEmailSecretConfigured:!!googleEmailSecret,
          activeEmailProvider:googleEmailUrl&&googleEmailSecret?"GoogleAppsScript":resend?"Resend":"none",
          turnKeyIdConfigured:!!turnId,
          turnKeyApiTokenConfigured:!!turnToken
        },
        note:"No secret values are exposed by this endpoint."
      });
    }
    // Google Client ID is public; never return any secret here.
    if(url.pathname==="/api/google-config" && request.method==="GET"){
      const clientId=String(this.env.GOOGLE_CLIENT_ID||"").trim();
      return j({ok:true,enabled:!!clientId,clientId,nativeUsernameSignup:true,birthDateSignup:true});
    }
    if(url.pathname==="/api/media-upload" && request.method==="POST"){
      const session=await this.session(String(url.searchParams.get("token")||""));
      if(!session)return j({ok:false,error:"الجلسة منتهية"},401);
      if(!this.env.MEDIA_BUCKET)return j({ok:false,error:"MEDIA_BUCKET_NOT_CONFIGURED"},503);

      const kind=String(url.searchParams.get("kind")||"file").toLowerCase();
      const allowedKinds=new Set(["image","video","audio","file","avatar","cover","story","adhan"]);
      if(!allowedKinds.has(kind))return j({ok:false,error:"نوع الملف غير مدعوم"},400);
      if(kind==="adhan"){
        const auth=await this.requireAdmin(String(url.searchParams.get("token")||""));
        if(auth.error)return auth.error;
      }
      const contentType=String(request.headers.get("content-type")||"").split(";")[0].trim().toLowerCase();
      const accepted=RASTER_IMAGE_TYPES.has(contentType) || contentType.startsWith("video/") ||
        contentType.startsWith("audio/") || contentType==="application/pdf";
      if(!accepted)return j({ok:false,error:"صيغة الملف غير مدعومة"},415);
      if(kind==="video" && !contentType.startsWith("video/"))return j({ok:false,error:"اختر ملف فيديو"},415);
      if(kind==="adhan" && !contentType.startsWith("audio/"))return j({ok:false,error:"اختر تسجيل أذان صوتي"},415);
      if(["image","avatar","cover"].includes(kind) && !contentType.startsWith("image/"))return j({ok:false,error:"اختر ملف صورة"},415);
      const maxBytes=kind==="adhan"?MAX_ADHAN_BYTES:50*1024*1024;
      const declaredLength=Number(request.headers.get("content-length")||0);
      if(declaredLength>maxBytes)return j({ok:false,error:kind==="adhan"?"الحد الأقصى للتسجيل 20 ميجابايت":"حجم الملف أكبر من 50 MB"},413);
      if(!request.body)return j({ok:false,error:"الملف فارغ"},400);

      const supplied=String(url.searchParams.get("name")||"media");
      const ext=(supplied.match(/\.([a-z0-9]{1,8})$/i)||[])[1]||"bin";
      const key=`${kind}/${Date.now()}-${crypto.randomUUID()}.${ext.toLowerCase()}`;
      try{
        if(kind==="adhan"){
          const bytes=await readAdhanAudio(request);
          if(!bytes)return j({ok:false,error:"اختر تسجيلًا غير فارغ حتى 20 ميجابايت"},413);
          if(!isAdhanAudio(bytes))return j({ok:false,error:"الملف ليس تسجيلًا صوتيًا مدعومًا"},415);
          const digest=await crypto.subtle.digest("SHA-256",bytes);
          const sha256=[...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,"0")).join("");
          await this.env.MEDIA_BUCKET.put(key,bytes,{httpMetadata:{contentType},customMetadata:{owner:session.username,sha256}});
          return j({ok:true,url:`/media/${key}`,size:bytes.length,sha256,contentType});
        }
        // R2 stores the binary stream; no base64 conversion or Durable Object storage.
        const object=await this.env.MEDIA_BUCKET.put(key,request.body,{
          httpMetadata:{contentType},
          customMetadata:{owner:session.username}
        });
        if(object.size>maxBytes){
          await this.env.MEDIA_BUCKET.delete(key);
          return j({ok:false,error:"حجم الملف أكبر من 50 MB"},413);
        }
        return j({ok:true,url:`/media/${key}`,size:object.size,contentType});
      }catch(err){
        console.error("R2 upload failed",err);
        return j({ok:false,error:"تعذر رفع الملف. حاول مرة أخرى"},502);
      }
    }

    if(["/api/football","/api/admin/football"].includes(url.pathname))return this.footballApi(request,url);

    let body={};
    if(request.method!=="GET"){
      const parsed=await readApiJson(request);
      if(parsed.error)return parsed.error;
      body=parsed.body;
    }

    if(url.pathname==="/api/admin/security" && request.method==="GET"){
      const auth=await this.requireAdmin(url.searchParams.get("token"));if(auth.error)return auth.error;
      return j({ok:true,configured:!!await this.ctx.storage.get(`admin-security:${this.ownerUsername()}`)});
    }
    if(url.pathname==="/api/admin/security/code" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token);if(auth.error)return auth.error;
      if(!this.ownerEmail())return j({ok:false,error:"اضبط بريد المدير الرئيسي في الخادم أولًا"},409);
      if(this.adminEnrollmentBusy)return j({ok:false,error:"طلب حماية قيد التنفيذ"},409);
      this.adminEnrollmentBusy=true;
      try{
        const key=`admin-security-code:${this.ownerUsername()}`,previous=await this.ctx.storage.get(key);
        if(previous && Date.now()-previous.createdAt<60000)return j({ok:false,error:"انتظر دقيقة قبل طلب كود جديد"},429);
        const code=String(crypto.getRandomValues(new Uint32Array(1))[0]%1000000).padStart(6,"0");
        await this.sendVerificationEmail(this.ownerEmail(),code,true);
        await this.ctx.storage.put(key,{hash:await hashPassword(code),tokenHash:await hashPassword(body.token),createdAt:Date.now(),attempts:0});
        return j({ok:true,email:this.maskEmail(this.ownerEmail())});
      }catch{return j({ok:false,error:"تعذر إرسال كود حماية الإدارة إلى بريد المدير؛ راجع إعداد البريد"},503);}
      finally{this.adminEnrollmentBusy=false;}
    }
    if(url.pathname==="/api/admin/security/password" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token);if(auth.error)return auth.error;
      if(this.adminEnrollmentBusy)return j({ok:false,error:"طلب حماية قيد التنفيذ"},409);
      this.adminEnrollmentBusy=true;
      try{
        const key=`admin-security-code:${this.ownerUsername()}`,proof=await this.ctx.storage.get(key);
        if(typeof body.currentPassword==="string" && body.currentPassword){
          const passwordError=await this.requireAdminPassword(body.currentPassword);if(passwordError)return passwordError;
        }else{
          if(!proof || Date.now()-proof.createdAt>600000 || proof.attempts>=5 || proof.tokenHash!==await hashPassword(body.token))return j({ok:false,error:"أدخل كلمة الحماية الحالية أو اطلب كود بريد جديد",code:"ADMIN_PASSWORD_PROOF_REQUIRED"},403);
          proof.attempts++;await this.ctx.storage.put(key,proof);
          if(!/^[0-9]{6}$/.test(String(body.code||"")) || proof.hash!==await hashPassword(body.code))return j({ok:false,error:"كود البريد غير صحيح"},403);
        }
        const password=String(body.newPassword||"");
        if(password.length<10 || password.length>128 || password!==body.confirmPassword)return j({ok:false,error:"كلمة حماية الإدارة من 10 إلى 128 حرفًا مع تأكيد مطابق"},400);
        const user=await this.user(auth.session.username);
        if(user?.passwordHash && await passwordMatches(password,user.passwordHash))return j({ok:false,error:"اختر كلمة حماية مختلفة عن كلمة دخول الحساب"},400);
        await this.ctx.storage.put(`admin-security:${this.ownerUsername()}`,{hash:await securePassword(password),updatedAt:Date.now()});
        await this.ctx.storage.delete(key);await this.ctx.storage.delete(`admin-security-attempts:${this.ownerUsername()}`);
        return j({ok:true});
      }finally{this.adminEnrollmentBusy=false;}
    }

    if(url.pathname==="/api/adhan-voices" && request.method==="GET"){
      const voices=(await this.allRecords("adhan-voice:")).sort((a,b)=>a.createdAt-b.createdAt)
        .map(({id,name,mosque,url,size,sha256,source})=>({id,name,mosque,url,size,sha256,source}));
      return j({ok:true,voices});
    }
    if(url.pathname==="/api/admin/adhan-voices" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token||"");if(auth.error)return auth.error;
      const name=String(body.name||"").trim(),mosque=String(body.mosque||"").trim(),audio=String(body.url||"");
      if(name.length<2 || name.length>80 || mosque.length>120 || !/^\/media\/adhan\/[a-zA-Z0-9.-]+$/.test(audio))return j({ok:false,error:"أدخل اسم المؤذن واختر تسجيلًا رفعته من الإدارة"},400);
      const object=await this.env.MEDIA_BUCKET?.head(audio.slice(7));
      if(!object || object.customMetadata?.owner!==auth.session.username || !/^[a-f0-9]{64}$/.test(object.customMetadata?.sha256||"") || !object.httpMetadata?.contentType?.startsWith("audio/") || object.size>MAX_ADHAN_BYTES)return j({ok:false,error:"التسجيل غير موجود أو لم ترفعه الإدارة"},400);
      const voices=await this.allRecords("adhan-voice:");
      if(voices.length>=50)return j({ok:false,error:"الحد الأقصى 50 تسجيلًا مضافًا؛ احذف تسجيلًا قديمًا أولًا"},409);
      const id="admin-"+crypto.randomUUID();
      const voice={id,name,mosque,url:audio,size:object.size,sha256:object.customMetadata.sha256,source:"إضافة إدارة التطبيق",createdAt:Date.now()};
      await this.ctx.storage.put("adhan-voice:"+id,voice);
      return j({ok:true,voice});
    }
    if(url.pathname==="/api/admin/adhan-voices/delete" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token||"");if(auth.error)return auth.error;
      const id=String(body.id||"");
      if(!/^admin-[a-f0-9-]{36}$/.test(id))return j({ok:false,error:"اختر تسجيلًا أضافته الإدارة"},400);
      await this.ctx.storage.delete("adhan-voice:"+id);
      // Existing offline selections continue to work; never remove a user's downloaded audio.
      return j({ok:true});
    }

    if(url.pathname==="/api/turn-credentials" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const keyId=String(this.env.TURN_KEY_ID||"").trim();
      const apiToken=String(this.env.TURN_KEY_API_TOKEN||"").trim();

      if(!keyId || !apiToken){
        return j({
          ok:false,
          error:"TURN غير مفعّل على الخادم",
          code:"TURN_NOT_CONFIGURED"
        },503);
      }

      try{
        const cf=await fetch(
          `https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(keyId)}/credentials/generate-ice-servers`,
          {
            method:"POST",
            headers:{
              "Authorization":`Bearer ${apiToken}`,
              "Content-Type":"application/json"
            },
            body:JSON.stringify({
              ttl:86400
            })
          }
        );

        const text=await cf.text();
        let data={};

        try{
          data=JSON.parse(text);
        }catch{}

        if(!cf.ok){
          console.error("Cloudflare TURN credential error",cf.status,text.slice(0,500));
          return j({
            ok:false,
            error:"تعذر إنشاء بيانات TURN",
            code:"TURN_PROVIDER_ERROR",
            providerStatus:cf.status
          },502);
        }

        const iceServers=Array.isArray(data.iceServers)?data.iceServers:[];

        if(!iceServers.length){
          return j({
            ok:false,
            error:"Cloudflare لم يُرجع خوادم ICE",
            code:"TURN_EMPTY"
          },502);
        }

        return j({
          ok:true,
          provider:"cloudflare",
          ttl:86400,
          iceServers
        });
      }catch(err){
        console.error("TURN endpoint failed",err);
        return j({
          ok:false,
          error:"تعذر الاتصال بخدمة TURN",
          code:"TURN_FETCH_FAILED"
        },502);
      }
    }

    if(
      (url.pathname==="/api/register" || url.pathname==="/api/register-request") &&
      request.method==="POST"
    ){
      if(body.legalAccepted!==true || body.legalVersion!=="2026-10-20")return j({ok:false,error:"يجب قراءة شروط الاستخدام وسياسة الخصوصية والموافقة عليهما قبل إنشاء الحساب"},400);
      const reg=this.normalizeRegistration(body);
      const validationError=await this.validateRegistration(reg);
      if(validationError)return j({ok:false,error:validationError},400);

      const emailRateKey=`verify-email-rate:${reg.email}`;
      const lastEmailSent=Number(await this.ctx.storage.get(emailRateKey)||0);
      const now=Date.now();

      if(now-lastEmailSent<60000){
        const wait=Math.ceil((60000-(now-lastEmailSent))/1000);
        return j({ok:false,error:`انتظر ${wait} ثانية قبل طلب كود جديد`,retryAfter:wait},429);
      }

      const verificationId=crypto.randomUUID();
      const code=String(crypto.getRandomValues(new Uint32Array(1))[0]%1000000).padStart(6,"0");
      const codeHash=await hashPassword(`verify:${verificationId}:${code}`);

      const pending={
        verificationId,
        registration:{
          username:reg.username,
          fullName:reg.fullName,
          age:reg.age,
          birthDate:reg.birthDate,
          phone:reg.phone,
          email:reg.email,
          gender:reg.gender,
          accountHidden:reg.gender==="female" && reg.accountHidden===true,
          country:reg.country,
          passwordHash:await securePassword(reg.password)
        },
        codeHash,
        createdAt:now,
        expiresAt:now+(10*60*1000),
        attempts:0,
        resendCount:0,
        lastSentAt:now
      };

      try{
        await this.sendVerificationEmail(reg.email,code);
      }catch(err){
        if(err?.message==="EMAIL_PROVIDER_NOT_CONFIGURED"){
          return j({
            ok:false,
            error:"إرسال كود التأكيد غير مفعّل على الخادم بعد",
            code:"EMAIL_PROVIDER_NOT_CONFIGURED"
          },503);
        }

        return j({
          ok:false,
          error:"تعذر إرسال كود التأكيد إلى البريد الإلكتروني",
          code:"EMAIL_SEND_FAILED",
          diagnostics:{
            provider:String(err?.providerName||"Resend"),
            status:Number(err?.providerStatus||0),
            name:String(err?.providerName||""),
            message:String(err?.providerMessage||"")
          }
        },502);
      }

      await this.ctx.storage.put(`pending-registration:${verificationId}`,pending,{
        expirationTtl:15*60
      });
      await this.ctx.storage.put(emailRateKey,now,{expirationTtl:60});

      return j({
        ok:true,
        verificationRequired:true,
        verificationId,
        delivery:this.maskEmail(reg.email),
        expiresIn:600
      });
    }

    if(url.pathname==="/api/register-verify" && request.method==="POST"){
      const verificationId=String(body.verificationId||"").trim();
      const code=String(body.code||"").trim().replace(/\D/g,"");

      if(!verificationId || !/^\d{6}$/.test(code)){
        return j({ok:false,error:"اكتب كود التأكيد المكون من 6 أرقام"},400);
      }

      const key=`pending-registration:${verificationId}`;
      const pending=await this.ctx.storage.get(key);

      if(!pending){
        return j({ok:false,error:"طلب التسجيل غير موجود أو انتهت صلاحيته"},410);
      }

      if(Date.now()>Number(pending.expiresAt||0)){
        await this.ctx.storage.delete(key);
        return j({ok:false,error:"انتهت صلاحية الكود. اطلب كودًا جديدًا"},410);
      }

      pending.attempts=Number(pending.attempts||0)+1;

      if(pending.attempts>6){
        await this.ctx.storage.delete(key);
        return j({ok:false,error:"تم تجاوز عدد محاولات التأكيد. ابدأ التسجيل من جديد"},429);
      }

      const inputHash=await hashPassword(`verify:${verificationId}:${code}`);

      if(inputHash!==pending.codeHash){
        await this.ctx.storage.put(key,pending,{expirationTtl:15*60});
        return j({
          ok:false,
          error:`كود التأكيد غير صحيح. المحاولات المتبقية: ${Math.max(0,6-pending.attempts)}`
        },400);
      }

      const reg=pending.registration||{};

      if(!["male","female"].includes(reg.gender) || reg.gender==="female" && typeof reg.accountHidden!=="boolean")
        return j({ok:false,error:"أعد إنشاء الحساب لاختيار النوع وإعداد الإخفاء"},400);
      if(!this.ownerRegistrationAllowed(reg.username,reg.email))return j({ok:false,error:"اسم المدير محجوز للبريد المعتمد"},403);

      if(await this.user(reg.username)){
        await this.ctx.storage.delete(key);
        return j({ok:false,error:"اسم المستخدم موجود بالفعل"},409);
      }

      const existingUsers=await this.ctx.storage.get("usernames")||[];

      for(const existingName of existingUsers){
        const existing=await this.user(existingName);
        if(!existing)continue;
        if(reg.phone && existing.phone===reg.phone){
          await this.ctx.storage.delete(key);
          return j({ok:false,error:"رقم الهاتف مستخدم بالفعل"},409);
        }
        if(existing.email===reg.email){
          await this.ctx.storage.delete(key);
          return j({ok:false,error:"البريد الإلكتروني مستخدم بالفعل"},409);
        }
      }

      await this.ctx.storage.put(`user:${reg.username}`,{
        username:reg.username,
        displayName:reg.fullName,
        fullName:reg.fullName,
        age:reg.age,
        birthDate:reg.birthDate,
        phone:reg.phone,
        email:reg.email,
        emailVerified:true,
        emailVerifiedAt:Date.now(),
        gender:reg.gender,
        accountHidden:reg.gender==="female" && reg.accountHidden===true,
        country:reg.country,
        passwordHash:reg.passwordHash,
        avatar:"",
        createdAt:Date.now()
      });

      existingUsers.push(reg.username);
      await this.ctx.storage.put("usernames",[...new Set(existingUsers)]);
      await this.ctx.storage.delete(key);

      const token=await this.createSession(reg.username);

      return j({
        ok:true,
        token,
        user:await this.publicUser(reg.username),
        verified:true
      });
    }

    if(url.pathname==="/api/register-resend" && request.method==="POST"){
      const verificationId=String(body.verificationId||"").trim();
      const key=`pending-registration:${verificationId}`;
      const pending=await this.ctx.storage.get(key);

      if(!pending){
        return j({ok:false,error:"طلب التسجيل غير موجود أو انتهت صلاحيته"},410);
      }

      const now=Date.now();
      const lastSentAt=Number(pending.lastSentAt||0);

      if(now-lastSentAt<60000){
        const wait=Math.ceil((60000-(now-lastSentAt))/1000);
        return j({ok:false,error:`يمكن إعادة الإرسال بعد ${wait} ثانية`,retryAfter:wait},429);
      }

      if(Number(pending.resendCount||0)>=4){
        return j({ok:false,error:"تم تجاوز الحد المسموح لإعادة إرسال الكود. ابدأ التسجيل من جديد"},429);
      }

      const code=String(crypto.getRandomValues(new Uint32Array(1))[0]%1000000).padStart(6,"0");

      try{
        await this.sendVerificationEmail(pending.registration.email,code);
      }catch(err){
        return j({
          ok:false,
          error:"تعذر إعادة إرسال كود التأكيد",
          code:"EMAIL_RESEND_FAILED",
          diagnostics:{
            provider:String(err?.providerName||"Resend"),
            status:Number(err?.providerStatus||0),
            name:String(err?.providerName||""),
            message:String(err?.providerMessage||"")
          }
        },502);
      }

      pending.codeHash=await hashPassword(`verify:${verificationId}:${code}`);
      pending.lastSentAt=now;
      pending.expiresAt=now+(10*60*1000);
      pending.attempts=0;
      pending.resendCount=Number(pending.resendCount||0)+1;

      await this.ctx.storage.put(key,pending,{expirationTtl:15*60});

      return j({
        ok:true,
        delivery:this.maskEmail(pending.registration.email),
        expiresIn:600
      });
    }

    if(url.pathname==="/api/logout" && request.method==="POST"){
      const sessionToken=String(body.token||url.searchParams.get("token")||"").trim();

      if(sessionToken){
        await this.ctx.storage.delete(`session:${sessionToken}`);
      }

      return j({ok:true});
    }

    // تسجيل مجاني: Google تتحقق من البريد بدلاً من إرسال كود بريد.
    // tokeninfo verifies Google signature, audience and expiry at Google's endpoint.
    if(url.pathname==="/api/google-login" && request.method==="POST"){
      const clientId=String(this.env.GOOGLE_CLIENT_ID||"").trim();
      const credential=String(body.credential||"").trim();
      if(!clientId)return j({ok:false,error:"تسجيل Google غير متاح حالياً"},503);
      if(!credential || credential.length>10000)return j({ok:false,error:"بيانات Google غير صالحة"},400);
      let claims;
      try{
        const check=await fetch("https://oauth2.googleapis.com/tokeninfo?id_token="+encodeURIComponent(credential));
        if(!check.ok)return j({ok:false,error:"فشل التحقق من هوية Google"},401);
        claims=await check.json();
      }catch{return j({ok:false,error:"تعذر الاتصال بخدمة التحقق من Google"},502)}
      if(claims.aud!==clientId || !["accounts.google.com","https://accounts.google.com"].includes(claims.iss)
          || !(claims.email_verified===true || claims.email_verified==="true") || !claims.sub || Number(claims.exp)*1000<=Date.now()){
        return j({ok:false,error:"تعذر التحقق من البريد باستخدام Google"},401);
      }
      const email=String(claims.email||"").toLowerCase().trim();
      if(!email || !email.includes("@"))return j({ok:false,error:"لم يرجع Google بريدًا صالحًا"},401);
      const idHash=(await hashPassword("google:"+claims.sub)).slice(0,22);
      const googleKey=`google-account:${idHash}`;
      let username=await this.ctx.storage.get(googleKey);
      if(username && !(await this.user(username)))username=null;
      if(!username){
        // A verified Google identity can request the signup form without creating an account.
        // Consent remains mandatory on the subsequent profile submission.
        if(!String(body.username||" ").trim())return j({ok:false,error:"USERNAME_REQUIRED: أكمل بيانات إنشاء الحساب",code:"USERNAME_REQUIRED"},409);
        if(body.legalAccepted!==true || body.legalVersion!=="2026-10-20")return j({ok:false,error:"يجب قراءة شروط الاستخدام وسياسة الخصوصية والموافقة عليهما قبل إنشاء الحساب"},400);
        const all=await this.ctx.storage.get("usernames")||[];
        for(const name of all){
          const existing=await this.user(name);
          if(existing?.email===email){
            return j({ok:false,error:"هذا البريد مرتبط بحساب موجود. ادخل بكلمة المرور القديمة أولًا؛ لن نربط الحساب تلقائيًا حفاظًا على أمانه."},409);
          }
        }
        // AL-ATTA v1.0.10: New Google users select their own username.
        const chosenUsername=String(body.username||"").trim().toLowerCase();
        if(!/^[a-z0-9_]{3,24}$/.test(chosenUsername)){
          return j({ok:false,error:"USERNAME_REQUIRED: اختر اسم مستخدم من 3 إلى 24 حرفًا إنجليزيًا أو رقمًا أو _",code:"USERNAME_REQUIRED"},409);
        }
        if(body.password!==undefined && (String(body.password).length<8 || body.password!==body.confirmPassword)){
          return j({ok:false,error:"كلمة مرور Google الجديدة غير مطابقة أو قصيرة (8 أحرف على الأقل)"},400);
        }
        const gender=String(body.gender||"").trim().toLowerCase();
        if(!this.ownerRegistrationAllowed(chosenUsername,email))return j({ok:false,error:"اسم المدير محجوز للبريد المعتمد"},403);
        if(!["male","female"].includes(gender))return j({ok:false,error:"اختيار النوع (ذكر أو أنثى) إجباري",code:"GENDER_REQUIRED"},400);
        if(gender==="female" && typeof body.accountHidden!=="boolean")return j({ok:false,error:"اختاري هل تريدين إخفاء الحساب أم لا",code:"HIDDEN_CHOICE_REQUIRED"},400);
        if(gender==="male" && body.accountHidden===true)return j({ok:false,error:"خيار إخفاء الحساب متاح عند اختيار أنثى فقط"},400);
        const chosenFullName=String(body.fullName||"").trim();
        const chosenBirthDate=String(body.birthDate||"").trim();
        const chosenAge=this.birthDateAge(chosenBirthDate);
        if(chosenFullName.length<2 || chosenFullName.length>60 || chosenAge===null){
          return j({ok:false,error:"اختر الاسم الظاهر وتاريخ الميلاد الصحيح لإنشاء حساب Google",code:"PROFILE_REQUIRED"},400);
        }
        username=chosenUsername;
        if(await this.user(username))return j({ok:false,error:"تعذر إنشاء الحساب. تواصل مع الإدارة."},409);
        const fullName=chosenFullName.slice(0,60);
        const age=chosenAge;
        const city=String(body.city||"").trim().slice(0,80);
        await this.ctx.storage.put(`user:${username}`,{
          username,displayName:fullName,fullName,age,birthDate:chosenBirthDate,city,phone:"",email,
          emailVerified:true,emailVerifiedAt:Date.now(),googleIdHash:idHash,
          gender,accountHidden:gender==="female" && body.accountHidden===true,country:"",passwordHash:(String(body.password||'').length>=8 && body.password===body.confirmPassword)
            ?await securePassword(body.password):null,avatar:"",createdAt:Date.now()
        });
        await this.ctx.storage.put("usernames",[...new Set([...all,username])]);
        await this.ctx.storage.put(googleKey,username);
      }
      const googleUser=await this.user(username);
      if(googleUser?.banned || googleUser?.disabled)return j({ok:false,error:"تم إيقاف هذا الحساب بواسطة الإدارة"},403);
      const token=await this.createSession(username);
      return j({ok:true,token,user:await this.publicUser(username)});
    }

    if(url.pathname==="/api/login" && request.method==="POST"){
      const username=String(body.username||"").trim().toLowerCase();
      const password=String(body.password||"");
      const rateKey=`login-fail:${username.slice(0,120)}`;
      const attempt=await this.ctx.storage.get(rateKey)||{count:0,until:0};
      if(attempt.until>Date.now() && attempt.count>=12)return j({ok:false,error:"محاولات كثيرة. حاول بعد 10 دقائق"},429);
      let u=await this.user(username);
      if(!u && (username.includes("@") || /^\+?[0-9]{8,15}$/.test(username))){
        const names=await this.ctx.storage.get("usernames")||[];
        for(const name of names){
          const candidate=await this.user(name);
          if(candidate && (String(candidate.email||"").toLowerCase()===username || String(candidate.phone||"")===username)){
            u=candidate;break;
          }
        }
      }

      if(!u && username.includes("@")){
        return j({ok:false,error:"EMAIL_NOT_REGISTERED: هذا البريد غير مسجل. يمكنك إنشاء حساب جديد.",code:"EMAIL_NOT_REGISTERED"},404);
      }
      if(!u || !await passwordMatches(password,u.passwordHash)){
        const fresh=attempt.until>Date.now()?attempt:{count:0,until:Date.now()+600000};
        fresh.count++;
        await this.ctx.storage.put(rateKey,fresh,{expirationTtl:610});
        return j({ok:false,error:"اسم المستخدم أو كلمة المرور غير صحيحة"},401);
      }
      if(u.banned || u.disabled)return j({ok:false,error:"تم إيقاف هذا الحساب بواسطة الإدارة"},403);
      await this.ctx.storage.delete(rateKey);
      if(!u.passwordHash.startsWith('pbkdf2:')){
        u.passwordHash=await securePassword(password);
        await this.ctx.storage.put(`user:${u.username}`,u);
      }
      const token=await this.createSession(u.username);
      return j({ok:true,token,user:await this.publicUser(u.username)});
    }

    if(url.pathname==="/api/change-password" && request.method==="POST"){
      const session=await this.session(String(body.token||""));
      if(!session)return j({ok:false,error:"الجلسة منتهية"},401);
      const user=await this.user(session.username);
      const oldPassword=String(body.oldPassword||"");
      const next=String(body.newPassword||"");
      if(!user?.passwordHash)return j({ok:false,error:"هذا الحساب يستخدم تسجيل Google"},400);
      if(!await passwordMatches(oldPassword,user.passwordHash))return j({ok:false,error:"كلمة المرور الحالية غير صحيحة"},401);
      if(next.length<8 || next.length>128 || next!==String(body.confirmPassword||""))return j({ok:false,error:"تحقق من كلمة المرور الجديدة وتأكيدها (8 أحرف على الأقل)"},400);
      user.passwordHash=await securePassword(next);
      user.authVersion=Number(user.authVersion||0)+1;
      await this.ctx.storage.put(`user:${user.username}`,user);
      return j({ok:true});
    }

    if(url.pathname==="/api/password-reset-request" && request.method==="POST"){
      const email=String(body.email||"").trim().toLowerCase();
      if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))return j({ok:false,error:"بريد إلكتروني غير صالح"},400);
      const key=`reset-rate:${await hashPassword(email)}`;
      const previous=Number(await this.ctx.storage.get(key)||0);
      if(Date.now()-previous<60000)return j({ok:false,error:"انتظر دقيقة قبل طلب كود جديد"},429);
      const names=await this.ctx.storage.get('usernames')||[];
      let found=null;
      for(const name of names){
        const user=await this.user(name);
        if(user?.passwordHash && String(user.email||'').toLowerCase()===email){found=user;break;}
      }
      const requestId=crypto.randomUUID();
      await this.ctx.storage.put(key,Date.now(),{expirationTtl:70});
      // Never disclose whether an email exists. Unknown accounts get a dummy ID.
      if(found){
        const code=String(crypto.getRandomValues(new Uint32Array(1))[0]%1000000).padStart(6,'0');
        const pending={username:found.username,codeHash:await hashPassword(`reset:${requestId}:${code}`),attempts:0,expiresAt:Date.now()+600000};
        await this.ctx.storage.put(`reset:${requestId}`,pending,{expirationTtl:650});
        try{await this.sendPasswordResetEmail(email,code);}catch(e){
          await this.ctx.storage.delete(`reset:${requestId}`);
          return j({ok:false,error:"تعذر إرسال البريد الإلكتروني حالياً"},503);
        }
      }
      return j({ok:true,requestId,expiresIn:600});
    }

    if(url.pathname==="/api/password-reset-confirm" && request.method==="POST"){
      const requestId=String(body.requestId||"");
      const code=String(body.code||"");
      const next=String(body.newPassword||"");
      if(next.length<8 || next.length>128 || next!==String(body.confirmPassword||""))return j({ok:false,error:"كلمة المرور الجديدة غير مطابقة أو قصيرة"},400);
      const pending=await this.ctx.storage.get(`reset:${requestId}`);
      if(!pending || pending.expiresAt<Date.now() || pending.attempts>=5)return j({ok:false,error:"الكود غير صالح أو انتهت صلاحيته"},400);
      if(!/^\d{6}$/.test(code) || pending.codeHash!==await hashPassword(`reset:${requestId}:${code}`)){
        pending.attempts++;
        if(pending.attempts>=5)await this.ctx.storage.delete(`reset:${requestId}`);
        else await this.ctx.storage.put(`reset:${requestId}`,pending,{expirationTtl:650});
        return j({ok:false,error:"كود الاستعادة غير صحيح"},400);
      }
      const user=await this.user(pending.username);
      if(!user || !user.passwordHash)return j({ok:false,error:"تعذر استعادة هذا الحساب"},400);
      user.passwordHash=await securePassword(next);
      user.authVersion=Number(user.authVersion||0)+1;
      await this.ctx.storage.put(`user:${user.username}`,user);
      await this.ctx.storage.delete(`reset:${requestId}`);
      return j({ok:true});
    }

    if(url.pathname==="/api/profile-media" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const u=await this.user(s.username);
      if(!u)return j({ok:false,error:"المستخدم غير موجود"},404);

      const kind=String(body.kind||"");
      const data=String(body.data||"");
      if(!["avatar","cover"].includes(kind))return j({ok:false,error:"نوع الصورة غير صحيح"},400);
      if(!data.startsWith("data:image/"))return j({ok:false,error:"ملف الصورة غير صحيح"},400);

      const max=kind==="avatar"?250000:500000;
      const image=data.slice(0,max);
      if(kind==="avatar")u.avatar=image; else u.cover=image;
      await this.ctx.storage.put(`user:${u.username}`,u);

      // V13.42: every profile-photo/cover update creates a real feed post unconditionally.
      const id=crypto.randomUUID();
      const post={
        id,
        author:u.username,
        text:kind==="avatar"?"قام بتحديث صورته الشخصية.":"قام بتحديث صورة الغلاف.",
        image,
        profileMediaKind:kind==="avatar"?"profile_avatar":"profile_cover",
        createdAt:Date.now(),
        likes:[],
        comments:[]
      };
      await this.ctx.storage.put(`post:${id}`,post);
      const ids=await this.ctx.storage.get("postIds")||[];
      ids.push(id);
      await this.ctx.storage.put("postIds",ids.slice(-500));

      return j({ok:true,user:await this.publicUser(u.username),postId:id,post});
    }

    if(url.pathname==="/api/profile" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const u=await this.user(s.username);
      if(!u)return j({ok:false,error:"المستخدم غير موجود"},404);

      const previousAvatar=String(u.avatar||"");
      const previousCover=String(u.cover||"");
      let avatarChanged=false, coverChanged=false;

      if(typeof body.displayName==="string" && body.displayName.trim()){
        u.displayName=body.displayName.trim().slice(0,60);
      }
      if(typeof body.avatar==="string"){
        const nextAvatar=body.avatar.slice(0,250000);
        avatarChanged=!!nextAvatar && nextAvatar!==previousAvatar;
        u.avatar=nextAvatar;
      }
      if(typeof body.cover==="string"){
        const nextCover=body.cover.slice(0,500000);
        coverChanged=!!nextCover && nextCover!==previousCover;
        u.cover=nextCover;
      }
      if(typeof body.bio==="string")u.bio=body.bio.slice(0,300);
      for(const field of ['country','city','education','work']){
        if(typeof body[field]==='string')u[field]=body[field].trim().slice(0,100);
      }
      if(typeof body.accountPrivate==="boolean")u.accountPrivate=body.accountPrivate;
      if(typeof body.allowFriendRequests==="boolean")u.allowFriendRequests=body.allowFriendRequests;

      await this.ctx.storage.put(`user:${u.username}`,u);

      // V13.41: profile-media updates become real feed posts and return their IDs.
      // This guarantees the post is created even if the browser refreshes immediately.
      const mediaPosts=[];
      if(avatarChanged)mediaPosts.push({text:"قام بتحديث صورته الشخصية.",image:u.avatar,kind:"profile_avatar"});
      if(coverChanged)mediaPosts.push({text:"قام بتحديث صورة الغلاف.",image:u.cover,kind:"profile_cover"});
      const createdProfilePostIds=[];
      if(mediaPosts.length){
        const ids=await this.ctx.storage.get("postIds")||[];
        for(const item of mediaPosts){
          const id=crypto.randomUUID();
          const post={id,author:u.username,text:item.text,image:item.image,profileMediaKind:item.kind,createdAt:Date.now(),likes:[],comments:[]};
          await this.ctx.storage.put(`post:${id}`,post);
          ids.push(id);
          createdProfilePostIds.push(id);
        }
        await this.ctx.storage.put("postIds",ids.slice(-500));
      }

      return j({ok:true,user:await this.publicUser(u.username),createdProfilePosts:mediaPosts.length,createdProfilePostIds});
    }

    if(url.pathname==="/api/groups" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const name=String(body.name||"").trim().slice(0,80);
      if(!name)return j({ok:false,error:"اسم المجموعة مطلوب"},400);

      const members=[...new Set([
        s.username,
        ...(Array.isArray(body.members)?body.members:[])
      ])].map(x=>String(x).trim().toLowerCase()).filter(Boolean);

      for(const member of members){
        if(!await this.user(member))return j({ok:false,error:"أحد أعضاء المجموعة غير موجود"},400);
      }
      for(const member of members){
        const u=await this.user(member);
        if(!u?.accountHidden)continue;
        for(const other of members){
          if(other!==member && !await this.canCommunicate(member,other))
            return j({ok:false,error:"خصوصية الحساب المخفي: المجموعات مع الأصدقاء فقط"},403);
        }
      }
      const id=crypto.randomUUID();
      const group={id,name,members,createdBy:s.username,createdAt:Date.now()};
      await this.ctx.storage.put(`group:${id}`,group);

      const ids=await this.ctx.storage.get("groupIds")||[];
      ids.push(id);
      await this.ctx.storage.put("groupIds",ids);

      return j({ok:true,group});
    }

    if(url.pathname==="/api/groups" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const ids=await this.ctx.storage.get("groupIds")||[];
      const groups=[];
      for(const id of ids){
        const g=await this.ctx.storage.get(`group:${id}`);
        if(g && g.members.includes(s.username)){
          const visibleMembers=[];
          for(const member of g.members){
            const u=await this.user(member);
            if(u && (!u.accountHidden || member===s.username || await this.canCommunicate(s.username,member)))visibleMembers.push(member);
          }
          const visibleGroup={...g,members:visibleMembers};
          if(g.createdBy && !await this.canViewProfile(s.username,g.createdBy))delete visibleGroup.createdBy;
          groups.push(visibleGroup);
        }
      }
      return j({ok:true,groups});
    }


    if((url.pathname==="/api/push/register" || url.pathname==="/api/push/unregister") && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const deviceToken=String(body.deviceToken||"").trim();
      if(!/^[A-Za-z0-9:._-]{40,4096}$/.test(deviceToken))return j({ok:false,error:"رمز الجهاز غير صالح"},400);
      const provider=String(body.provider||'fcm');
      if(!['fcm','hms'].includes(provider))return j({ok:false,error:"مزود الإشعارات غير صالح"},400);
      const prefix=provider==='hms'?'hms':'fcm';
      const registering=url.pathname.endsWith('/register');
      const ownerKey=prefix+'Owner:'+deviceToken;
      const previousOwner=String(await this.ctx.storage.get(ownerKey)||'');
      const key=prefix+'Tokens:'+s.username;
      const prior=await this.ctx.storage.get(key)||[];
      if(registering){
        // One physical push token must not keep alerts from a previous login.
        if(previousOwner && previousOwner!==s.username){
          const oldKey=prefix+'Tokens:'+previousOwner;
          const oldTokens=await this.ctx.storage.get(oldKey)||[];
          await this.ctx.storage.put(oldKey,oldTokens.filter(t=>t!==deviceToken));
        }
        await this.ctx.storage.put(key,[...new Set([...prior,deviceToken])].slice(-5));
        await this.ctx.storage.put(ownerKey,s.username);
      }else{
        await this.ctx.storage.put(key,prior.filter(t=>t!==deviceToken));
        if(previousOwner===s.username)await this.ctx.storage.put(ownerKey,'');
      }
      return j({ok:true,registered:registering});
    }

    // Authenticated, non-sensitive status for troubleshooting missing background ringing.
    if(url.pathname==="/api/push/status" && request.method==="GET"){
      const session=await this.session(url.searchParams.get("token")||"");
      if(!session)return j({ok:false,error:"الجلسة منتهية"},401);
      const provider=url.searchParams.get('provider')==='hms'?'hms':'fcm';
      const registered=await this.ctx.storage.get(provider+'Tokens:'+session.username)||[];
      let projectMatches=false;
      const configured=provider==='hms'?!!(this.env.HUAWEI_PUSH_APP_ID && this.env.HUAWEI_PUSH_APP_SECRET):!!this.env.FIREBASE_SERVICE_ACCOUNT_JSON;
      if(configured && provider==='hms')projectMatches=true;
      else if(configured){
        try{
          const service=JSON.parse(this.env.FIREBASE_SERVICE_ACCOUNT_JSON);
          projectMatches=!!service.private_key && !!service.client_email && !!service.project_id &&
            (!this.env.FIREBASE_PROJECT_ID || this.env.FIREBASE_PROJECT_ID===service.project_id);
        }catch(_){/* Never return credentials or private keys. */}
      }
      const lastCallPush=await this.ctx.storage.get('lastCallPushResult:'+session.username)||null;
      return j({ok:true,provider,serverConfigured:configured,projectMatches,
        registeredDevices:registered.length,lastCallPush});
    }

    // User-triggered delivery check; FCM delivery is asynchronous, not guaranteed.
    if(url.pathname==="/api/push/test" && request.method==="POST"){
      const session=await this.session(String(body.token||""));
      if(!session)return j({ok:false,error:"الجلسة منتهية"},401);
      const provider=body.provider==='hms'?'hms':'fcm';
      const tokens=await this.ctx.storage.get(provider+'Tokens:'+session.username)||[];
      const latest=Number(await this.ctx.storage.get('pushTestLast:'+session.username)||0);
      if(Date.now()-latest<60000)return j({ok:false,error:"انتظر دقيقة قبل إعادة الاختبار"},429);
      if(!(provider==='hms'?this.env.HUAWEI_PUSH_APP_ID && this.env.HUAWEI_PUSH_APP_SECRET:this.env.FIREBASE_SERVICE_ACCOUNT_JSON) || !tokens.length)
        return j({ok:false,error:"الإشعارات غير جاهزة على الخادم أو الجهاز غير مسجّل"},409);
      await this.ctx.storage.put('pushTestLast:'+session.username,Date.now());
      const result=await (provider==='hms'?this.sendHmsToUser.bind(this):this.sendFcmToUser.bind(this))(session.username,{
        type:'push-test',to:session.username,from:'',eventId:crypto.randomUUID(),createdAt:String(Date.now())
      },'60s');
      // This confirms FCM accepted the request, not that the phone displayed it.
      if(!result.accepted)return j({ok:false,error:'تعذر إرسال الإشعار التجريبي ('+(result.error||'NO_ACCEPTED_DEVICE')+')',
        diagnostic:result.error||'NO_ACCEPTED_DEVICE'},502);
      return j({ok:true,provider,acceptedByProvider:result.accepted,acceptedByFirebase:provider==='fcm'?result.accepted:0,attempted:result.attempted});
    }

    if(url.pathname==="/api/call-signal" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const to=String(body.to||"").trim().toLowerCase();
      const signal=body.signal && typeof body.signal==="object" ? body.signal : {};
      const type=String(signal.type||"");

      const allowed=new Set([
        "call-request",
        "call-ringing",
        "call-accept",
        "call-reject",
        "offer",
        "answer",
        "ice",
        "hangup"
      ]);

      if(!to)return j({ok:false,error:"المستخدم المطلوب غير محدد"},400);
      if(!allowed.has(type))return j({ok:false,error:"إشارة مكالمة غير صالحة"},400);
      if(!await this.user(to))return j({ok:false,error:"المستخدم غير موجود"},404);
      if(!await this.canCommunicate(s.username,to))return j({ok:false,error:"المكالمات متاحة للأصدقاء فقط"},403);

      const now=Date.now();
      const event={
        ...signal,
        type,
        signalId:crypto.randomUUID(),
        from:s.username,
        to,
        createdAt:now
      };

      // Keep a short-lived queue for HTTP polling fallback.
      const key=`callSignals:${to}`;
      const oldQueue=await this.ctx.storage.get(key)||[];
      const freshQueue=oldQueue.filter(x=>now-Number(x.createdAt||0)<120000);
      freshQueue.push(event);
      await this.ctx.storage.put(key,freshQueue.slice(-500));

      await this.recordCallEvent(s.username,to,event);
      // Notify Android even if its app process is not running.
      this.scheduleCallPush(to,event);

      // Fast path: if WebSocket is alive, deliver immediately too.
      const target=this.findUser(to);
      if(target)this.send(target,event);

      if(type==="call-request"){
        await this.addCallLog(s.username,to,signal.callType||"video","outgoing");
      }else if(type==="call-reject"){
        await this.addCallLog(s.username,to,signal.callType||"video","rejected");
      }else if(type==="hangup"){
        await this.addCallLog(s.username,to,signal.callType||"video","ended");
      }

      return j({
        ok:true,
        signalId:event.signalId,
        online:!!target,
        queued:true
      });
    }

    if(url.pathname==="/api/call-signals" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const key=`callSignals:${s.username}`;
      const now=Date.now();
      const queue=await this.ctx.storage.get(key)||[];

      // Drop stale signaling. A call request older than this is no longer useful.
      const signals=queue.filter(x=>{
        const age=now-Number(x.createdAt||0);
        if(x.type==="call-request")return age<45000;
        return age<120000;
      });

      // Reading the queue acknowledges it for polling.
      await this.ctx.storage.put(key,[]);

      const permitted=[];
      for(const signal of signals){
        if(await this.canCommunicate(s.username,signal.from))permitted.push(signal);
      }
      return j({ok:true,signals:permitted});
    }

    if(url.pathname==="/api/calls" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const calls=await this.ctx.storage.get(`calls:${s.username}`)||[];
      return j({ok:true,calls:calls.slice(-100).reverse()});
    }

    
    if(url.pathname==="/api/users" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const q=String(url.searchParams.get("q")||"").trim().toLowerCase();
      const names=await this.ctx.storage.get("usernames")||[];
      const users=[];

      for(const name of names){
        const u=await this.publicUser(name);
        if(!u || u.accountHidden && u.username!==s.username || await this.isBlockedBetween(s.username,u.username))continue;
        if(!q || u.username.includes(q) || String(u.fullName||u.displayName||"").toLowerCase().includes(q)){
          users.push({...u,friendState:await this.friendState(s.username,u.username)});
        }
      }

      return j({ok:true,users:users.slice(0,100)});
    }

    if(url.pathname==="/api/post" && request.method==="GET"){
      const session=await this.session(url.searchParams.get("token")||"");
      if(!session)return j({ok:false,error:"الجلسة منتهية"},401);
      const raw=await this.ctx.storage.get(`post:${url.searchParams.get("postId")||""}`);
      const post=raw && !raw.archived?await this.visiblePost(raw,session.username):null;
      if(!post)return j({ok:false,error:"المنشور غير متاح"},404);
      const members=postReactionMembers(post);
      return j({ok:true,post:{...post,authorInfo:await this.publicUser(post.author),reactionCount:members.size,
        likeCount:post.likes.length,commentCount:post.comments.length,myReaction:members.get(session.username)||"",
        likedByMe:post.likes.includes(session.username)}});
    }

    if(url.pathname==="/api/posts" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const text=String(body.text||"").trim().slice(0,5000);
      // Keep legacy small inline images/videos, but store new large media as R2 URLs.
      const mediaValue=(value,legacyLimit)=>{
        const raw=String(value||"");
        if(raw.startsWith("/media/")){
          const key=raw.slice(7);
          if(key.length<=300 && /^[a-z0-9_-]+\/[a-z0-9._-]+$/i.test(key))return raw;
          return null;
        }
        if(raw.startsWith("data:image/") || raw.startsWith("data:video/")){
          return raw.length<=legacyLimit?raw:null;
        }
        return raw===""?"":null;
      };
      const image=mediaValue(body.image,500000);
      const video=mediaValue(body.video,1750000);
      if(image===null || video===null)return j({ok:false,error:"ارفع الصورة أو الفيديو إلى R2 أولاً، ثم أرسل رابط /media/ الناتج"},413);

      if(!text && !image && !video)return j({ok:false,error:"اكتب منشورًا أو أضف صورة أو فيديو"},400);

      const id=crypto.randomUUID();
      const post={
        id,
        author:s.username,
        text,
        image,
        video,
        createdAt:Date.now(),
        likes:[],
        comments:[]
      };

      await this.ctx.storage.put(`post:${id}`,post);

      const ids=await this.ctx.storage.get("postIds")||[];
      ids.push(id);
      await this.ctx.storage.put("postIds",ids.slice(-500));

      return j({ok:true,post});
    }

    // v1.0.33: archive and restore are restricted to the original author.
    if(url.pathname==="/api/post-archive" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const id=String(body.postId||"");
      const post=await this.ctx.storage.get(`post:${id}`);
      if(!post)return j({ok:false,error:"المنشور غير موجود"},404);
      if(post.author!==s.username)return j({ok:false,error:"لا يمكنك أرشفة منشور شخص آخر"},403);
      post.archived=body.archived===true;
      await this.ctx.storage.put(`post:${id}`,post);
      return j({ok:true,archived:post.archived});
    }

    if(url.pathname==="/api/post-archive-list" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const ids=await this.ctx.storage.get("postIds")||[];
      const posts=[];
      for(const id of [...ids].reverse()){
        const p=await this.ctx.storage.get(`post:${id}`);
        if(p && p.author===s.username && p.archived===true)posts.push(await this.visiblePost(p,s.username));
        if(posts.length>=100)break;
      }
      return j({ok:true,posts});
    }

    if(url.pathname==="/api/post-delete" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const id=String(body.postId||"");
      if(!id)return j({ok:false,error:"المنشور غير محدد"},400);
      const post=await this.ctx.storage.get(`post:${id}`);
      if(!post)return j({ok:false,error:"المنشور غير موجود"},404);
      const role=await this.roleFor(s.username);
      const isAdmin=role==="super_admin";
      if(post.author!==s.username && !isAdmin)return j({ok:false,error:"غير مسموح لك بحذف هذا المنشور"},403);
      await this.ctx.storage.delete(`post:${id}`);
      const ids=await this.ctx.storage.get("postIds")||[];
      await this.ctx.storage.put("postIds",ids.filter(x=>x!==id));
      return j({ok:true});
    }

    if(url.pathname==="/api/posts" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const ids=await this.ctx.storage.get("postIds")||[];
      const posts=[];
      const paged=url.searchParams.get("page")==="1";
      const author=paged?String(url.searchParams.get("author")||"").trim().toLowerCase():"";
      if(author && !/^[a-z0-9_.-]{1,64}$/.test(author))return j({ok:false,error:"المستخدم غير صالح"},400);
      const requestedLimit=Number(url.searchParams.get("limit")||0);
      const limit=paged?Math.max(1,Math.min(20,Math.floor(requestedLimit)||10)):
        Number.isFinite(requestedLimit)&&requestedLimit>0?Math.min(50,Math.floor(requestedLimit)):ids.length;
      const ordered=[...ids].reverse();
      const before=String(url.searchParams.get("before")||"");
      const stamp=paged?[...new Uint8Array(await crypto.subtle.digest("SHA-256",encoder.encode(JSON.stringify(ids))))].map(v=>v.toString(16).padStart(2,"0")).join("").slice(0,16):"";
      const marker=before.match(/^([a-f0-9]{16})\.(\d{1,6})$/);
      if(paged && before && (!marker || marker[1]!==stamp || Number(marker[2])>ordered.length))return j({ok:false,error:"تغيرت قائمة المنشورات. حدّث الصفحة ثم تابع."},409);
      const offset=before?Number(marker?.[2]||0):0;
      const candidates=paged?ordered.slice(offset,offset+80):ordered.slice(0,limit);
      const saved=await this.ctx.storage.get(`saved:${s.username}`)||[];
      let scanned=0,lastScanned="";
      for(const id of candidates){
        scanned++;lastScanned=id;
        const raw=await this.ctx.storage.get(`post:${id}`);
        if(!raw || raw.archived===true || (author && raw.author!==author))continue;
        const p=await this.visiblePost(raw,s.username);if(!p)continue;
        const reactionCounts={};
        const reactions=p.reactions||{};
        for(const key of ["like","love","haha","wow","sad","angry","dislike"]){
          reactionCounts[key]=Array.isArray(reactions[key])?reactions[key].length:0;
        }

        const myReaction=postReactionMembers(p).get(s.username)||"";

        const sharedPost=p.sharedPost||null;

        const person=await this.publicUser(p.author);
        const authorInfo=paged && person?{username:person.username,fullName:person.fullName,displayName:person.displayName,avatar:person.avatar}:person;

        posts.push({
          ...p,
          authorInfo,
          likeCount:(p.likes||[]).length,
          reactionCount:postReactionMembers(p).size,
          commentCount:(p.comments||[]).length,
          likedByMe:(p.likes||[]).includes(s.username),
          reactionCounts,
          myReaction,
          savedByMe:saved.includes(p.id),
          sharedPost
        });
        if(paged && posts.length>=limit)break;
      }

      const nextCursor=paged && offset+scanned<ordered.length?`${stamp}.${offset+scanned}`:null;
      return j({ok:true,posts,...(paged?{paged:true,nextCursor}:{} )});
    }

    if(url.pathname==="/api/post-interactions" && request.method==="GET"){
      const session=await this.session(url.searchParams.get("token")||"");
      if(!session)return j({ok:false,error:"الجلسة منتهية"},401);
      const id=String(url.searchParams.get("postId")||"");
      const raw=await this.ctx.storage.get(`post:${id}`);
      const post=raw&&!raw.archived?await this.visiblePost(raw,session.username):null;
      if(!post)return j({ok:false,error:"المنشور غير متاح"},404);
      const kind=url.searchParams.get("kind")||"reactions";
      if(!["reactions","comments"].includes(kind))return j({ok:false,error:"نوع القائمة غير صحيح"},400);
      const requestedOffset=Number(url.searchParams.get("offset")||0),requestedLimit=Number(url.searchParams.get("limit")||30);
      const offset=Number.isFinite(requestedOffset)?Math.max(0,Math.floor(requestedOffset)):0;
      const limit=Number.isFinite(requestedLimit)?Math.max(1,Math.min(50,Math.floor(requestedLimit))):30;
      const members=postReactionMembers(post);
      const person=username=>this.interactionPerson(username);
      const result={ok:true,postId:id,kind,reactionCount:members.size,commentCount:post.comments.length,myReaction:members.get(session.username)||""};
      if(kind==="reactions"){
        const list=[...members],people=[];
        for(const [username,reaction] of list.slice(offset,offset+limit)){
          const u=await person(username);if(u)people.push({...u,reaction});
        }
        return j({...result,people,total:list.length,nextOffset:offset+limit<list.length?offset+limit:null});
      }
      const list=[...post.comments].reverse(),comments=[];
      for(const comment of list.slice(offset,offset+limit)){
        const replies=[];
        for(const reply of (comment.replies||[]).slice(-30))replies.push({...reply,authorInfo:await person(reply.author)});
        comments.push({...comment,authorInfo:await person(comment.author),replies});
      }
      return j({...result,comments,total:list.length,nextOffset:offset+limit<list.length?offset+limit:null});
    }

    if(url.pathname==="/api/post-like" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const post=await this.ctx.storage.get(`post:${body.postId}`);
      if(!post)return j({ok:false,error:"المنشور غير موجود"},404);
      if(post.archived || !await this.canSeeContent(s.username,post.author))return j({ok:false,error:"المنشور غير متاح"},403);

      const wasLike=postReactionMembers(post).get(s.username)==="like";
      post.likes=(post.likes||[]).filter(name=>name!==s.username);
      for(const kind of Object.keys(post.reactions||{}))if(Array.isArray(post.reactions[kind]))post.reactions[kind]=post.reactions[kind].filter(name=>name!==s.username);
      let liked=false;

      if(!wasLike){
        post.likes.push(s.username);
        liked=true;

        if(post.author!==s.username){
          const list=await this.ctx.storage.get(`notifications:${post.author}`)||[];
          list.push({
            id:crypto.randomUUID(),
            type:"like",
            from:s.username,
            postId:post.id,
            createdAt:Date.now(),
            read:false
          });
          await this.ctx.storage.put(`notifications:${post.author}`,list.slice(-200));
        this.scheduleUserPush(post.author,{type:"like",from:s.username,
          eventId:list[list.length-1].id,
          createdAt:list[list.length-1].createdAt});
        }
      }

      await this.ctx.storage.put(`post:${post.id}`,post);
      return j({ok:true,liked,likeCount:post.likes.length});
    }

    if(url.pathname==="/api/post-comment" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const post=await this.ctx.storage.get(`post:${body.postId}`);
      if(!post)return j({ok:false,error:"المنشور غير موجود"},404);
      if(post.archived || !await this.canSeeContent(s.username,post.author))return j({ok:false,error:"المنشور غير متاح"},403);

      const text=String(body.text||"").trim().slice(0,1000);
      if(!text)return j({ok:false,error:"اكتب تعليقًا"},400);

      const comment={
        id:crypto.randomUUID(),
        author:s.username,
        text,
        createdAt:Date.now()
      };

      post.comments=post.comments||[];
      post.comments.push(comment);
      await this.ctx.storage.put(`post:${post.id}`,post);

      if(post.author!==s.username){
        const list=await this.ctx.storage.get(`notifications:${post.author}`)||[];
        list.push({
          id:crypto.randomUUID(),
          type:"comment",
          from:s.username,
          postId:post.id,
          text,
          createdAt:Date.now(),
          read:false
        });
        await this.ctx.storage.put(`notifications:${post.author}`,list.slice(-200));
        this.scheduleUserPush(post.author,{type:"comment",from:s.username,
          eventId:list[list.length-1].id,
          createdAt:list[list.length-1].createdAt});
      }

      return j({ok:true,comment});
    }

    if(url.pathname==="/api/notifications" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const list=await this.ctx.storage.get(`notifications:${s.username}`)||[];
      const visible=[];
      const nameCache=new Map();
      for(const notification of [...list].reverse()){
        const sender=notification.from?await this.user(notification.from):null;
        if(sender?.accountHidden && ["like","reaction","comment","comment-reply"].includes(notification.type)){
          if(sender.banned || sender.disabled || await this.isBlockedBetween(s.username,sender.username))continue;
          visible.push({...notification,from:"",fromName:"حساب مخفي",anonymous:true});
          if(visible.length===100)break;
          continue;
        }
        if(!notification.from || await this.canViewProfile(s.username,notification.from)){
          const from=String(notification.from||"").trim().toLowerCase();
          let fromName="";
          if(from){
            if(!nameCache.has(from)){
              const sender=await this.user(from);
              nameCache.set(from, sender?.fullName||sender?.displayName||sender?.username||from);
            }
            fromName=nameCache.get(from);
          }
          visible.push({...notification,fromName});
        }
        if(visible.length===100)break;
      }
      return j({ok:true,notifications:visible});
    }

    if(url.pathname==="/api/notifications/read" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const list=await this.ctx.storage.get(`notifications:${s.username}`)||[];
      for(const n of list)n.read=true;
      await this.ctx.storage.put(`notifications:${s.username}`,list);
      return j({ok:true});
    }

    if(url.pathname==="/api/notifications/read-one" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const notificationId=String(body.notificationId||"");
      if(!notificationId)return j({ok:false,error:"الإشعار غير صالح"},400);
      const list=await this.ctx.storage.get(`notifications:${s.username}`)||[];
      const item=list.find(n=>n.id===notificationId);
      if(item)item.read=true;
      await this.ctx.storage.put(`notifications:${s.username}`,list);
      return j({ok:true});
    }



    if(url.pathname==="/api/follow" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const target=String(body.username||"").trim().toLowerCase();
      if(!target || target===s.username)return j({ok:false,error:"مستخدم غير صالح"},400);
      if(!await this.user(target) || !(await this.canViewProfile(s.username,target)))return j({ok:false,error:"المستخدم غير موجود"},404);
      if(await this.isBlockedBetween(s.username,target))return j({ok:false,error:"لا يمكن إرسال طلب صداقة بسبب الحظر"},403);
      if(!await this.canRevealInteraction(s.username,target))return j({ok:false,error:"الحساب المخفي يتابع الأصدقاء فقط"},403);
      const following=await this.ctx.storage.get(`following:${s.username}`)||[];
      const followers=await this.ctx.storage.get(`followers:${target}`)||[];
      const exists=following.includes(target);
      if(exists){
        await this.ctx.storage.put(`following:${s.username}`,following.filter(x=>x!==target));
        await this.ctx.storage.put(`followers:${target}`,followers.filter(x=>x!==s.username));
        return j({ok:true,following:false});
      }
      following.push(target);followers.push(s.username);
      await this.ctx.storage.put(`following:${s.username}`,[...new Set(following)]);
      await this.ctx.storage.put(`followers:${target}`,[...new Set(followers)]);
      const ns=await this.ctx.storage.get(`notifications:${target}`)||[];
      ns.push({id:crypto.randomUUID(),type:"follow",from:s.username,createdAt:Date.now(),read:false});
      await this.ctx.storage.put(`notifications:${target}`,ns.slice(-200));
        this.scheduleUserPush(target,{type:"follow",from:s.username,
          eventId:ns[ns.length-1].id,
          createdAt:ns[ns.length-1].createdAt});
      return j({ok:true,following:true});
    }

    if(url.pathname==="/api/profile-public" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const username=String(url.searchParams.get("username")||s.username).trim().toLowerCase();
      const u=await this.publicUser(username);
      if(!u || !(await this.canViewProfile(s.username,username)))return j({ok:false,error:"المستخدم غير موجود"},404);

      return j({
        ok:true,
        user:u,
        // Hide private friendships from aggregate counts shown to other people.
        friendCount:(await (async()=>{
          const names=await this.friends(username);
          if(username===s.username)return names.length;
          let count=0;
          for(const name of names){const friend=await this.user(name);if(friend && !friend.accountHidden)count++;}
          return count;
        })()),
        friendState:await this.friendState(s.username,username),
        blockedByMe:(await this.blockedUsers(s.username)).includes(username),
        blockRestricted:await this.isBlockedBetween(s.username,username)
      });
    }

    if(url.pathname==="/api/stories" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const text=String(body.text||"").trim().slice(0,500);
      const media=String(body.media||"").slice(0,700000);
      if(!text && !media)return j({ok:false,error:"أضف نصًا أو صورة للحالة"},400);
      const id=crypto.randomUUID();
      const mediaType=body.mediaType==="video"?"video":body.mediaType==="image"?"image":"";
      const story={id,author:s.username,text,media,mediaType,createdAt:Date.now(),expiresAt:Date.now()+86400000};
      await this.ctx.storage.put(`story:${id}`,story);
      const ids=await this.ctx.storage.get("storyIds")||[];ids.push(id);
      await this.ctx.storage.put("storyIds",ids.slice(-500));
      return j({ok:true,story});
    }

    if(url.pathname==="/api/stories" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const ids=await this.ctx.storage.get("storyIds")||[];const now=Date.now();const stories=[];
      for(const id of ids){
        const st=await this.ctx.storage.get(`story:${id}`);if(!st)continue;
        if(st.expiresAt<=now){await this.ctx.storage.delete(`story:${id}`);continue;}
        if(!await this.canSeeContent(s.username,st.author))continue;
        stories.push({...st,authorInfo:await this.publicUser(st.author)});
      }
      return j({ok:true,stories});
    }


    if(url.pathname==="/api/personal-block" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const target=String(body.username||"").trim().toLowerCase();
      const action=String(body.action||"");
      if(!target || target===s.username)return j({ok:false,error:"لا يمكنك حظر حسابك"},400);
      if(!await this.user(target))return j({ok:false,error:"المستخدم غير موجود"},404);
      if(!["block","unblock"].includes(action))return j({ok:false,error:"إجراء غير صالح"},400);
      await this.setBlock(s.username,target,action==="block");
      return j({ok:true,blocked:action==="block"});
    }

    if(url.pathname==="/api/message-send" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const to=String(body.to||"").trim().toLowerCase();
      if(!to)return j({ok:false,error:"اختر مستخدمًا"},400);
      if(to===s.username)return j({ok:false,error:"لا يمكنك إرسال رسالة لنفسك"},400);
      if(!await this.user(to))return j({ok:false,error:"المستخدم غير موجود"},404);
      if(!await this.canCommunicate(s.username,to))return j({ok:false,error:"المحادثات متاحة للأصدقاء فقط"},403);

      const target=this.findUser(to);
      const mediaRaw=String(body.media||"");
      if(mediaRaw.length>950000){
        return j({ok:false,error:"حجم المرفق كبير جدًا. اختر صورة أصغر أو دع التطبيق يضغطها تلقائيًا."},413);
      }

      const payload={
        type:"chat",
        id:crypto.randomUUID(),
        from:s.username,
        to,
        text:String(body.text||"").slice(0,6000),
        media:mediaRaw,
        mediaType:String(body.mediaType||"text"),
        ts:Date.now(),
        read:false,
        deliveredAt:target?Date.now():0
      };

      const key=[s.username,to].sort().join(":");
      const list=await this.ctx.storage.get(`messages:${key}`)||[];
      list.push(payload);
      await this.ctx.storage.put(`messages:${key}`,list.slice(-300));
      if(payload.mediaType!=="call-signal")this.scheduleUserPush(to,{
        type:"chat",from:s.username,eventId:payload.id,createdAt:payload.ts
      });

      if(payload.mediaType==="call-signal"){
        try{
          const signal=JSON.parse(mediaRaw);
          const event={
            type:String(signal.type||""), from:s.username, to,
            callId:String(signal.callId||""), callType:signal.callType==="video"?"video":"audio",
            signalId:String(signal.signalId||payload.id), createdAt:Date.now()
          };
          await this.queuePhoneCallEvent(to,event);
          await this.recordCallEvent(s.username,to,{...event,reason:String(signal.reason||'')});
          this.scheduleCallPush(to,event);
        }catch(_){/* Non-call chat messages are unaffected. */}
      }
      if(target){
        this.send(target,payload);
      }

      return j({
        ok:true,
        message:payload,
        delivered:!!target,
        stored:true
      });
    }

    if(url.pathname==="/api/call-media-signals" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const other=String(url.searchParams.get("with")||"").trim().toLowerCase();
      const callId=String(url.searchParams.get("callId")||"");
      if(!/^[A-Za-z0-9_-]{8,128}$/.test(callId))return j({ok:false,error:"مكالمة غير صالحة"},400);
      if(!await this.canCommunicate(s.username,other))return j({ok:false,error:"المكالمات للأصدقاء فقط"},403);
      const key=[s.username,other].sort().join(":"),messages=await this.ctx.storage.get(`messages:${key}`)||[];
      const signals=[];
      for(const m of messages){
        if(m.mediaType!=="call-signal" || m.from!==other || m.to!==s.username || Date.now()-Number(m.ts||0)>120000)continue;
        try{
          const packet=JSON.parse(m.media);
          if(packet.callId!==callId)continue;
          signals.push({...packet,from:m.from,to:m.to,createdAt:m.ts,signalId:String(packet.signalId||m.id)});
        }catch{}
      }
      const response=j({ok:true,signals});response.headers.set("cache-control","no-store");return response;
    }

    // One network response for the inbox: never send 200 messages per peer.
    // Apply the same mutual-friend / block policy as opening a conversation.
    if(url.pathname==="/api/inbox" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const raw=String(url.searchParams.get("peers")||"");
      if(raw.length>4000)return j({ok:false,error:"قائمة المحادثات طويلة"},400);
      const peers=[...new Set(raw.split(",").map(v=>v.trim().toLowerCase()).filter(Boolean))];
      if(peers.length>50 || peers.some(v=>!/^[a-z0-9_.-]{1,64}$/.test(v)))return j({ok:false,error:"قائمة المحادثات غير صالحة"},400);
      const summaries=[];
      for(const peer of peers){
        if(!await this.canCommunicate(s.username,peer))continue;
        const user=await this.publicUser(peer);if(!user)continue;
        const key=[s.username,peer].sort().join(":");
        const messages=await this.ctx.storage.get(`messages:${key}`)||[];
        // Expired call status is reflected in the preview without marking unseen
        // messages delivered or writing the whole conversation on every refresh.
        this.finishExpiredCallEvents(messages);
        const last=messages.findLast(m=>m.mediaType!=="call-signal");
        const lastMessage=last?{id:last.id,from:last.from,to:last.to,text:String(last.text||"").slice(0,160),mediaType:last.mediaType||"",callType:last.callType,callStatus:last.callStatus,ts:last.ts,deliveredAt:last.deliveredAt,readAt:last.readAt}:null;
        summaries.push({user:{username:user.username,displayName:user.displayName,fullName:user.fullName,avatar:user.avatar,isOnline:user.isOnline,lastSeen:user.lastSeen},lastMessage});
      }
      return j({ok:true,summaries});
    }

    if(url.pathname==="/api/conversation" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const other=String(url.searchParams.get("with")||"").trim().toLowerCase();
      if(!await this.canCommunicate(s.username,other))return j({ok:false,error:"المحادثات متاحة للأصدقاء فقط"},403);
      const key=[s.username,other].sort().join(":");
      const messages=await this.ctx.storage.get(`messages:${key}`)||[];

      let changed=this.finishExpiredCallEvents(messages);
      for(const m of messages){
        if(m.to===s.username && !m.deliveredAt){
          m.deliveredAt=Date.now();
          changed=true;
        }
      }

      if(changed){
        await this.ctx.storage.put(`messages:${key}`,messages.slice(-300));
      }

      return j({ok:true,messages:messages.slice(-200)});
    }

    
    if(url.pathname==="/api/post-react" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const post=await this.ctx.storage.get(`post:${body.postId}`);
      if(!post)return j({ok:false,error:"المنشور غير موجود"},404);
      if(post.archived || !await this.canSeeContent(s.username,post.author))return j({ok:false,error:"المنشور غير متاح"},403);

      const allowed=["like","love","haha","wow","sad","angry","dislike"];
      const reaction=String(body.reaction||"like");
      if(!allowed.includes(reaction))return j({ok:false,error:"تفاعل غير صالح"},400);

      post.likes=(post.likes||[]).filter(name=>name!==s.username);
      post.reactions=post.reactions||{};
      for(const key of allowed){
        post.reactions[key]=Array.isArray(post.reactions[key])?post.reactions[key]:[];
        post.reactions[key]=post.reactions[key].filter(x=>x!==s.username);
      }

      if(body.remove!==true){
        post.reactions[reaction].push(s.username);
      }

      await this.ctx.storage.put(`post:${post.id}`,post);

      if(post.author!==s.username && body.remove!==true){
        const list=await this.ctx.storage.get(`notifications:${post.author}`)||[];
        list.push({
          id:crypto.randomUUID(),
          type:"reaction",
          reaction,
          from:s.username,
          postId:post.id,
          createdAt:Date.now(),
          read:false
        });
        await this.ctx.storage.put(`notifications:${post.author}`,list.slice(-200));
        this.scheduleUserPush(post.author,{type:"reaction",from:s.username,
          eventId:list[list.length-1].id,
          createdAt:list[list.length-1].createdAt});
      }

      const counts={};
      for(const key of allowed)counts[key]=post.reactions[key].length;
      const visible=await this.visiblePost(post,s.username);
      return j({ok:true,counts,reactionCount:postReactionMembers(visible).size,commentCount:visible.comments.length,myReaction:body.remove===true?"":reaction});
    }

    if(url.pathname==="/api/post-save" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const post=await this.ctx.storage.get(`post:${body.postId}`);
      if(!post)return j({ok:false,error:"المنشور غير موجود"},404);
      if(post.archived || !await this.canSeeContent(s.username,post.author))return j({ok:false,error:"المنشور غير متاح"},403);

      let saved=await this.ctx.storage.get(`saved:${s.username}`)||[];
      const exists=saved.includes(post.id);

      if(exists)saved=saved.filter(x=>x!==post.id);
      else saved.push(post.id);

      await this.ctx.storage.put(`saved:${s.username}`,saved.slice(-300));
      return j({ok:true,saved:!exists});
    }

    if(url.pathname==="/api/saved-posts" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const ids=await this.ctx.storage.get(`saved:${s.username}`)||[];
      const posts=[];

      for(const id of [...ids].reverse()){
        const p=await this.ctx.storage.get(`post:${id}`);
        if(!p || p.archived || !await this.canSeeContent(s.username,p.author))continue;
        const visible=await this.visiblePost(p,s.username);
        if(visible)posts.push({...visible,authorInfo:await this.publicUser(p.author),savedByMe:true});
      }

      return j({ok:true,posts});
    }

    if(url.pathname==="/api/post-share" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const source=await this.ctx.storage.get(`post:${body.postId}`);
      if(!source)return j({ok:false,error:"المنشور غير موجود"},404);
      if(source.archived || !await this.canSeeContent(s.username,source.author))return j({ok:false,error:"المنشور غير متاح"},403);
      if(!await this.canRevealInteraction(s.username,source.author))return j({ok:false,error:"الحساب المخفي لا يشارك منشور غير صديق"},403);

      const id=crypto.randomUUID();
      const post={
        id,
        author:s.username,
        text:String(body.text||"").trim().slice(0,1000),
        image:"",
        createdAt:Date.now(),
        likes:[],
        comments:[],
        reactions:{},
        sharedPostId:source.id,
        sharedFrom:source.author
      };

      await this.ctx.storage.put(`post:${id}`,post);
      const ids=await this.ctx.storage.get("postIds")||[];
      ids.push(id);
      await this.ctx.storage.put("postIds",ids.slice(-500));

      if(source.author!==s.username){
        const list=await this.ctx.storage.get(`notifications:${source.author}`)||[];
        list.push({
          id:crypto.randomUUID(),
          type:"share",
          from:s.username,
          postId:source.id,
          createdAt:Date.now(),
          read:false
        });
        await this.ctx.storage.put(`notifications:${source.author}`,list.slice(-200));
        this.scheduleUserPush(source.author,{type:"share",from:s.username,
          eventId:list[list.length-1].id,
          createdAt:list[list.length-1].createdAt});
      }

      return j({ok:true,post});
    }

    if(url.pathname==="/api/comment-reply" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const post=await this.ctx.storage.get(`post:${body.postId}`);
      if(!post)return j({ok:false,error:"المنشور غير موجود"},404);
      if(post.archived || !await this.canSeeContent(s.username,post.author))return j({ok:false,error:"المنشور غير متاح"},403);

      const text=String(body.text||"").trim().slice(0,1000);
      if(!text)return j({ok:false,error:"اكتب ردًا"},400);

      const comment=(post.comments||[]).find(c=>c.id===body.commentId);
      if(!comment)return j({ok:false,error:"التعليق غير موجود"},404);

      comment.replies=comment.replies||[];
      comment.replies.push({
        id:crypto.randomUUID(),
        author:s.username,
        text,
        createdAt:Date.now()
      });

      await this.ctx.storage.put(`post:${post.id}`,post);
      if(comment.author!==s.username && await this.user(comment.author) && !await this.isBlockedBetween(s.username,comment.author)){
        const notification={id:crypto.randomUUID(),type:"comment-reply",from:s.username,
          postId:post.id,commentId:comment.id,createdAt:Date.now(),read:false};
        const list=await this.ctx.storage.get(`notifications:${comment.author}`)||[];
        list.push(notification);
        await this.ctx.storage.put(`notifications:${comment.author}`,list.slice(-200));
        this.scheduleUserPush(comment.author,{type:"comment-reply",from:s.username,
          eventId:notification.id,createdAt:notification.createdAt});
      }
      return j({ok:true});
    }

    if(url.pathname==="/api/explore" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const blocked=await this.ctx.storage.get(`blocked:${s.username}`)||[];
      const q=String(url.searchParams.get("q")||"").trim().toLowerCase();
      if(!q)return j({ok:true,posts:[]});
      const ids=await this.ctx.storage.get("postIds")||[];
      const posts=[];

      for(const id of ids){
        const raw=await this.ctx.storage.get(`post:${id}`);
        if(!raw || raw.archived || blocked.includes(raw.author))continue;
        const p=await this.visiblePost(raw,s.username);if(!p)continue;
        const authorInfo=await this.publicUser(p.author);
        const haystack=`${p.text||""} ${p.author||""} ${authorInfo?.fullName||authorInfo?.displayName||""}`.toLowerCase();
        if(!haystack.includes(q))continue;

        const reactions=p.reactions||{};
        const reactionCount=Object.values(reactions).reduce((n,a)=>n+(Array.isArray(a)?a.length:0),0);
        const score=reactionCount+(p.comments||[]).length*2+(p.likes||[]).length;

        posts.push({
          ...p,
          authorInfo:await this.publicUser(p.author),
          score
        });
      }

      posts.sort((a,b)=>b.score-a.score || b.createdAt-a.createdAt);
      return j({ok:true,posts:posts.slice(0,60)});
    }

    if(url.pathname==="/api/block-user" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const target=String(body.username||"").trim().toLowerCase();
      if(!target || target===s.username)return j({ok:false,error:"مستخدم غير صالح"},400);

      if(!await this.user(target))return j({ok:false,error:"المستخدم غير موجود"},404);
      const exists=(await this.blockedUsers(s.username)).includes(target);
      await this.setBlock(s.username,target,!exists);
      return j({ok:true,blocked:!exists});
    }

    if(url.pathname==="/api/legal-consent" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      if(body.version!=="2026-10-20" || typeof body.accepted!=="boolean")return j({ok:false,error:"إصدار الموافقة غير صحيح"},400);
      const key=`legal-consent:${s.username}`,old=await this.ctx.storage.get(key);
      if(old?.version!==body.version || old?.accepted!==body.accepted)
        await this.ctx.storage.put(key,{version:body.version,accepted:body.accepted,updatedAt:Date.now()});
      return j({ok:true});
    }

    if(url.pathname==="/api/account-deletion-request"){
      const s=await this.session(String(request.method==="GET"?url.searchParams.get("token")||"":body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const key=`deletion-request:${s.username}`;
      if(request.method==="GET")return j({ok:true,request:await this.ctx.storage.get(key)||null});
      if(request.method!=="POST")return j({ok:false,error:"طريقة غير مسموحة"},405);
      if(body.username && body.username!==s.username)return j({ok:false,error:"يمكن طلب حذف حسابك فقط"},403);
      if(body.action==="cancel"){
        const previous=await this.ctx.storage.get(key);
        if(previous?.status==="pending")await this.ctx.storage.put(key,{...previous,status:"cancelled",updatedAt:Date.now()});
        return j({ok:true,cancelled:true});
      }
      if(body.confirmation!==s.username)return j({ok:false,error:"اكتب اسم المستخدم لتأكيد طلب الحذف"},400);
      const previous=await this.ctx.storage.get(key);
      const record=previous?.status==="pending"?previous:{id:crypto.randomUUID(),username:s.username,
        status:"pending",requestedAt:Date.now(),reason:String(body.reason||"").trim().slice(0,500)};
      await this.ctx.storage.put(key,record);
      return j({ok:true,request:record,deleted:false});
    }

    if(url.pathname==="/api/admin/deletion-requests" && request.method==="GET"){
      const auth=await this.requireAdmin(url.searchParams.get("token")||"");if(auth.error)return auth.error;
      const records=await this.allRecords("deletion-request:");
      return j({ok:true,requests:records.filter(r=>r.status==="pending").sort((a,b)=>a.requestedAt-b.requestedAt)});
    }

    if(url.pathname==="/api/report" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const targetType=String(body.targetType||"post"),targetId=String(body.targetId||"").trim(),reason=String(body.reason||"").trim();
      if(!['post','user'].includes(targetType) || !targetId || reason.length<3 || reason.length>500)
        return j({ok:false,error:"حدد المحتوى وسبب البلاغ من 3 إلى 500 حرف"},400);
      let targetUsername=targetId;
      if(targetType==='post'){
        const target=await this.ctx.storage.get(`post:${targetId}`);
        if(!target || target.archived || !await this.canSeeContent(s.username,target.author))return j({ok:false,error:"المحتوى غير متاح"},404);
        targetUsername=target.author;
      }else if(!await this.canViewProfile(s.username,targetId))return j({ok:false,error:"الحساب غير متاح"},404);
      if(targetUsername===s.username)return j({ok:false,error:"لا يمكنك الإبلاغ عن نفسك"},400);
      const reports=await this.ctx.storage.get("reports")||[];
      if(reports.some(r=>!r.resolved && r.reporter===s.username && r.targetType===targetType && r.targetId===targetId))
        return j({ok:true,alreadyReported:true});
      const rateKey=`report-rate:${s.username}`,rate=await this.ctx.storage.get(rateKey)||{start:Date.now(),count:0};
      if(Date.now()-rate.start>=600000){rate.start=Date.now();rate.count=0;}
      if(rate.count>=5)return j({ok:false,error:"انتظر قليلًا قبل إرسال بلاغ آخر"},429);
      const report={
        id:crypto.randomUUID(),
        reporter:s.username,
        targetType,targetId,targetUsername,reason,
        createdAt:Date.now()
      };

      reports.push(report);
      while(encoder.encode(JSON.stringify(reports)).length>100000){
        const resolved=reports.findIndex(r=>r.resolved);
        if(resolved<0)return j({ok:false,error:"البلاغات الحالية قيد المعالجة؛ تواصل مع الدعم"},503);
        reports.splice(resolved,1);
      }
      await this.ctx.storage.put("reports",reports);
      await this.ctx.storage.put(rateKey,{...rate,count:rate.count+1});
      return j({ok:true,id:report.id});
    }



    if(url.pathname==="/api/me" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const u=await this.user(s.username);
      if(!u)return j({ok:false,error:"المستخدم غير موجود"},404);
      return j({ok:true,user:{
        username:u.username,
        displayName:u.displayName||u.username,
        fullName:u.fullName||u.displayName||u.username,
        age:u.age??"",
        phone:u.phone||"",
        email:u.email||"",
        gender:u.gender||"",
        country:u.country||"",
        city:u.city||"",education:u.education||"",work:u.work||"",
        createdAt:u.createdAt||null,
        allowFriendRequests:u.allowFriendRequests!==false,
        bio:u.bio||"",
        cover:u.cover||"",
        accountPrivate:!!u.accountPrivate,
        accountHidden:!!u.accountHidden,
        avatar:u.avatar||"",
        role:await this.roleFor(u.username)
      }});
    }

    // Protected scoped cleanup. No endpoint can remove the configured owner.
    if(url.pathname==="/api/admin/reset/plan" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token||"");if(auth.error)return auth.error;
      if(!this.ownerEmail())return j({ok:false,error:"اضبط بريد المدير الرئيسي أولًا"},409);
      let previous=await this.ctx.storage.get("owner-reset:plan");
      if(previous && !["accounts","posts"].includes(previous.kind)){await this.ctx.storage.delete("owner-reset:plan");previous=null;this.resetClosing=false;}
      if(previous?.phase==="deleting"){
        if(!["accounts","posts"].includes(previous.kind))return j({ok:false,error:"خطة التصفير القديمة ملغاة لحماية حساب المدير",code:"LEGACY_RESET_DISABLED"},409);
        return j({ok:true,owner:this.ownerUsername(),planId:previous.id,confirmation:previous.confirmation,scope:previous.scope,resuming:true});
      }
      const kind=body.scope||"accounts";
      if(!["accounts","posts"].includes(kind))return j({ok:false,error:"اختر الحسابات أو المنشورات"},400);
      if(kind==="accounts" && !this.env.MEDIA_BUCKET)return j({ok:false,error:"اربط مخزن الوسائط قبل حذف الحسابات"},409);
      const inventory=await this.resetInventory();
      const targets=kind==="accounts"?(await this.allRecords("user:")).map(u=>u.username).filter(n=>n!==this.ownerUsername()):[];
      const targetSet=new Set(targets);
      const postsToDelete=kind==="posts"?inventory.byCategory.post||0:(await this.allRecords("post:")).filter(p=>targetSet.has(p.author)).length;
      const scope={...inventory,kind,accountsToDelete:targets.length,postsToDelete};
      const confirmation=kind==="accounts"?"امسح الحسابات عدا المدير":"امسح كل المنشورات";
      const plan={id:crypto.randomUUID(),owner:this.ownerUsername(),kind,targets,completed:0,cursor:null,phase:"review",expiresAt:Date.now()+600000,scope,confirmation,deletedMedia:0};
      await this.ctx.storage.put("owner-reset:plan",plan);
      return j({ok:true,owner:this.ownerUsername(),planId:plan.id,confirmation,scope,resuming:false});
    }

    if(url.pathname==="/api/admin/reset/execute" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token||"");if(auth.error)return auth.error;
      const plan=await this.ctx.storage.get("owner-reset:plan");
      if(!plan || plan.id!==body.planId || plan.owner!==this.ownerUsername() || !["accounts","posts"].includes(plan.kind))
        return j({ok:false,error:"اعرض نطاق الحذف الجديد أولًا؛ الخطط القديمة ملغاة"},409);
      if(plan.kind==="accounts"){const passwordError=await this.requireAdminPassword(body.adminPassword);if(passwordError)return passwordError;}
      if(body.confirmation!==plan.confirmation)return j({ok:false,error:"اكتب عبارة التأكيد كما ظهرت في التقرير"},400);
      if(plan.phase!=="deleting" && Date.now()>plan.expiresAt)return j({ok:false,error:"انتهت صلاحية التقرير؛ اعرضه من جديد"},409);
      if(this.resetStepBusy || this.accountDeleteBusy)return j({ok:false,error:"دفعة حذف قيد التنفيذ",code:"RESET_BUSY"},409);
      this.resetStepBusy=true;
      try{
        this.resetClosing=true;plan.phase="deleting";
        await this.ctx.storage.put("owner-reset:plan",plan);
        await Promise.allSettled([...this.activeOperations]);
        if(plan.kind==="accounts" && plan.completed<plan.targets.length){
          const target=plan.targets[plan.completed];
          if(target===this.ownerUsername())throw Error("OWNER_PROTECTED");
          const response=await this.deleteAccountStep(auth,{username:target,confirmation:target},true);
          const result=await response.json();
          if(!result.ok)return j(result,response.status);
          if(result.done){plan.completed++;plan.deletedMedia+=result.deletedMedia||0;}
        }else if(plan.kind==="posts"){
          const page=await this.ctx.storage.list({limit:100,...(plan.cursor?{startAfter:plan.cursor}:{})});
          for(const [key,value] of page){
            if(key.startsWith("post:")){await this.ctx.storage.delete(key);plan.completed++;}
            else if(key==="postIds" || key.startsWith("saved:"))await this.ctx.storage.put(key,[]);
            else if(key.startsWith("notifications:"))await this.ctx.storage.put(key,(value||[]).filter(n=>!n.postId));
            else if(key==="reports")await this.ctx.storage.put(key,(value||[]).filter(r=>r.targetType!=="post"));
          }
          plan.cursor=page.size===100?[...page.keys()].at(-1):null;
          plan.postsDone=!plan.cursor;
        }
        const done=plan.kind==="accounts"?plan.completed>=plan.targets.length:plan.postsDone;
        if(done){
          await this.ctx.storage.delete("owner-reset:plan");this.resetClosing=false;
          return j({ok:true,done:true,completed:plan.completed,keptAccounts:[this.ownerUsername()],loggedOut:false});
        }
        await this.ctx.storage.put("owner-reset:plan",plan);
        return j({ok:true,done:false,completed:plan.completed,deletedMedia:plan.deletedMedia});
      }catch{
        return j({ok:false,error:"توقف الحذف مؤقتًا؛ استكمله من لوحة الإدارة",code:"RESET_RETRY_REQUIRED"},503);
      }finally{this.resetStepBusy=false;}
    }

    if(url.pathname==="/api/admin/reset/preview" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token||"");if(auth.error)return auth.error;
      return j({ok:true,dryRun:true,owner:this.ownerUsername(),...await this.resetInventory(),deleted:false});
    }

    if(url.pathname==="/api/admin/reset/export" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token||"");
      if(auth.error)return auth.error;
      const passwordError=await this.requireAdminPassword(body.adminPassword);if(passwordError)return passwordError;
      // The export includes password hashes and session tokens. Treat it as SECRET.
      const limit=Math.max(1,Math.min(30,Number(body.limit)||15));
      const startAfter=String(body.startAfter||"");
      const entries=await this.ctx.storage.list({limit,...(startAfter?{startAfter}:{})});
      const values=[...entries.entries()].map(([key,value])=>({key,value}));
      const next=values.length===limit?values[values.length-1].key:null;
      const response=j({ok:true,entries:values,next,limit});
      response.headers.set("cache-control","no-store");
      return response;
    }

    if(url.pathname==="/api/admin/reset/media-list" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token||"");
      if(auth.error)return auth.error;
      if(!this.env.MEDIA_BUCKET)return j({ok:false,error:"MEDIA_BUCKET_NOT_CONFIGURED"},503);
      const page=await this.env.MEDIA_BUCKET.list({limit:100,
        ...(body.cursor?{cursor:String(body.cursor)}:{})});
      const response=j({ok:true,objects:page.objects.map(o=>({key:o.key,size:o.size,etag:o.etag})),
        next:page.truncated?page.cursor:null});
      response.headers.set("cache-control","no-store");
      return response;
    }

    if(url.pathname==="/api/admin/summary" && request.method==="GET"){
      const auth=await this.requireAdmin(url.searchParams.get("token")||"",true);
      if(auth.error)return auth.error;
      return j({ok:true,role:auth.role,owner:this.ownerUsername(),securityConfigured:!!await this.ctx.storage.get(`admin-security:${this.ownerUsername()}`),...await this.adminStatistics()});
    }

    if(url.pathname==="/api/admin/users" && request.method==="GET"){
      const auth=await this.requireAdmin(url.searchParams.get("token")||"",true);if(auth.error)return auth.error;
      const query=String(url.searchParams.get("q")||"").trim().toLowerCase();
      const list=[];
      for(const u of await this.allRecords("user:")){
        if(query && !`${u.username} ${u.displayName||""} ${u.fullName||""}`.toLowerCase().includes(query))continue;
        list.push({username:u.username,displayName:u.displayName||u.fullName||u.username,avatar:u.avatar||"",banned:!!u.banned,disabled:!!u.disabled,role:await this.roleFor(u.username),createdAt:u.createdAt||0});
      }
      list.sort((a,b)=>b.createdAt-a.createdAt || a.username.localeCompare(b.username));
      const offset=Math.max(0,Number(url.searchParams.get("offset"))||0);
      const limit=Math.max(1,Math.min(100,Number(url.searchParams.get("limit"))||100));
      return j({ok:true,users:list.slice(offset,offset+limit),total:list.length,nextOffset:offset+limit<list.length?offset+limit:null});
    }

    if(url.pathname==="/api/admin/users/delete" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token||"");if(auth.error)return auth.error;
      const passwordError=await this.requireAdminPassword(body.adminPassword);if(passwordError)return passwordError;
      return this.deleteAccountStep(auth,body);
    }

    if(url.pathname==="/api/admin/reports" && request.method==="GET"){
      const auth=await this.requireAdmin(url.searchParams.get("token")||"",true);
      if(auth.error)return auth.error;
      const reports=await this.ctx.storage.get("reports")||[];
      return j({ok:true,reports:[...reports].reverse().slice(0,500)});
    }

    if(url.pathname==="/api/admin/posts" && request.method==="GET"){
      const auth=await this.requireAdmin(url.searchParams.get("token")||"",true);
      if(auth.error)return auth.error;
      const query=String(url.searchParams.get("q")||"").trim().toLowerCase();
      const posts=(await this.allRecords("post:")).filter(p=>!query || `${p.id} ${p.author} ${p.text||""}`.toLowerCase().includes(query))
        .sort((a,b)=>(b.createdAt||0)-(a.createdAt||0)).slice(0,200)
        .map(p=>({id:p.id,author:p.author,text:String(p.text||"").slice(0,10000),createdAt:p.createdAt||0,hasImage:!!p.image}));
      return j({ok:true,posts});
    }

    if(url.pathname==="/api/admin/action" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token||"",true);
      if(auth.error)return auth.error;
      const action=String(body.action||"");
      const target=String(body.username||"").trim().toLowerCase();
      const owner=this.ownerUsername();

      const sensitive=["delete_post","update_post","update_user","make_moderator","remove_moderator"].includes(action);
      if(sensitive){
        if(auth.role!=="super_admin")return j({ok:false,error:"الحذف وتعديل البيانات والصلاحيات للمدير الرئيسي فقط"},403);
      }
      if(action==="update_post"){
        const id=String(body.postId||""),post=await this.ctx.storage.get(`post:${id}`);
        if(!post)return j({ok:false,error:"المنشور غير موجود"},404);
        if(typeof body.text!=="string" || body.text.length>10000)return j({ok:false,error:"نص المنشور غير صالح"},400);
        post.text=body.text;post.updatedAt=Date.now();post.editedBy=auth.session.username;
        await this.ctx.storage.put(`post:${id}`,post);return j({ok:true});
      }
      if(action==="update_user"){
        const user=await this.user(target);if(!user)return j({ok:false,error:"المستخدم غير موجود"},404);
        if(typeof body.displayName!=="string" || body.displayName.trim().length<2 || body.displayName.length>60)return j({ok:false,error:"الاسم من حرفين إلى 60 حرفًا"},400);
        user.displayName=body.displayName.trim();user.fullName=user.displayName;
        await this.ctx.storage.put(`user:${target}`,user);return j({ok:true});
      }
      if(["ban","unban","suspend","activate","make_moderator","remove_moderator"].includes(action)){
        let u=await this.user(target);
        if(!target || !u)return j({ok:false,error:"المستخدم غير موجود"},404);
        if(target===owner)return j({ok:false,error:"لا يمكن إيقاف حساب المدير الرئيسي"},403);
        if(["make_moderator","remove_moderator"].includes(action)){
          const moderators=await this.ctx.storage.get("admin-moderators:v2")||[];
          await this.ctx.storage.put("admin-moderators:v2",action==="make_moderator"?[...new Set([...moderators,target])]:moderators.filter(n=>n!==target));
          return j({ok:true});
        }
        if(auth.role==="moderator" && await this.roleFor(target)==="moderator")return j({ok:false,error:"لا يمكن للمشرف إيقاف مشرف آخر"},403);
        if(this.accountStatusBusy.has(target))return j({ok:false,error:"جاري تحديث هذا الحساب؛ حاول بعد قليل"},409);
        this.accountStatusBusy.add(target);
        try{
        u=await this.user(target);
        u.banned=action==="ban";u.disabled=action==="suspend";
        u.bannedAt=u.banned?Date.now():0;u.bannedBy=u.banned?auth.session.username:"";
        u.disabledAt=u.disabled?Date.now():0;u.disabledBy=u.disabled?auth.session.username:"";
        // Reactivation requires a fresh login: old sessions must never come back to life.
        u.authVersion=Number(u.authVersion||0)+1;
        u.moderationVersion=Number(u.moderationVersion||0)+1;
        // This separate state cannot be undone by an in-flight profile save with stale fields.
        await this.ctx.storage.put(`account-state:${target}`,{banned:u.banned,disabled:u.disabled,version:u.moderationVersion,changedAt:Date.now()});
        await this.ctx.storage.put(`user:${target}`,u);
        if(u.banned || u.disabled)this.disconnectAccount(target);
        return j({ok:true,status:u.banned?"banned":u.disabled?"disabled":"active"});
        }finally{this.accountStatusBusy.delete(target);}
      }

      if(action==="delete_post"){
        const id=String(body.postId||"");
        if(!id)return j({ok:false,error:"المنشور غير محدد"},400);
        await this.ctx.storage.delete(`post:${id}`);
        const ids=await this.ctx.storage.get("postIds")||[];
        await this.ctx.storage.put("postIds",ids.filter(x=>x!==id));
        return j({ok:true});
      }

      if(action==="resolve_report"){
        const id=String(body.reportId||"");
        const reports=await this.ctx.storage.get("reports")||[];
        const r=reports.find(x=>x.id===id); if(r){r.resolved=true;r.resolvedAt=Date.now();r.resolvedBy=auth.session.username;}
        await this.ctx.storage.put("reports",reports);
        return j({ok:true});
      }

      return j({ok:false,error:"إجراء إداري غير صالح"},400);
    }

    if(url.pathname==="/api/friend-request" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const target=String(body.username||"").trim().toLowerCase();
      if(!target || target===s.username)return j({ok:false,error:"مستخدم غير صالح"},400);
      const targetUser=await this.user(target);
      if(!targetUser || (targetUser.accountHidden && !(await this.canViewProfile(s.username,target))))return j({ok:false,error:"المستخدم غير موجود"},404);
      if(await this.isBlockedBetween(s.username,target))return j({ok:false,error:"لا يمكن طلب الصداقة بسبب الحظر"},403);
      if(targetUser.accountHidden)return j({ok:false,error:"هذا الحساب لا يستقبل طلبات صداقة جديدة"},403);
      if(targetUser.allowFriendRequests===false)return j({ok:false,error:"هذا المستخدم لا يستقبل طلبات صداقة"},403);

      const state=await this.friendState(s.username,target);
      if(state==="friends")return j({ok:false,error:"أنتما أصدقاء بالفعل"},409);
      if(state==="outgoing")return j({ok:false,error:"طلب الصداقة مرسل بالفعل"},409);
      if(state==="incoming")return j({ok:false,error:"هذا المستخدم أرسل لك طلبًا بالفعل"},409);

      const now=Date.now();
      const outgoing=await this.outgoingFriendRequests(s.username);
      const incoming=await this.incomingFriendRequests(target);
      outgoing.push({to:target,createdAt:now});
      incoming.push({from:s.username,createdAt:now});
      await this.ctx.storage.put(`friendOutgoing:${s.username}`,outgoing.slice(-300));
      await this.ctx.storage.put(`friendIncoming:${target}`,incoming.slice(-300));

      const ns=await this.ctx.storage.get(`notifications:${target}`)||[];
      ns.push({id:crypto.randomUUID(),type:"friend-request",from:s.username,createdAt:now,read:false});
      await this.ctx.storage.put(`notifications:${target}`,ns.slice(-300));
        this.scheduleUserPush(target,{type:"friend-request",from:s.username,
          eventId:ns[ns.length-1].id,
          createdAt:ns[ns.length-1].createdAt});

      const targetWs=this.findUser(target);
      if(targetWs){
        this.send(targetWs,{
          type:"friend-request",
          from:s.username,
          user:await this.publicUser(s.username),
          createdAt:now
        });
      }

      return j({ok:true,state:"outgoing"});
    }

    if(url.pathname==="/api/friend-action" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const other=String(body.username||"").trim().toLowerCase();
      const action=String(body.action||"");
      if(await this.isBlockedBetween(s.username,other))return j({ok:false,error:"لا يمكن تنفيذ طلب الصداقة بسبب الحظر"},403);

      let myIn=await this.incomingFriendRequests(s.username);
      let myOut=await this.outgoingFriendRequests(s.username);
      let otherIn=await this.incomingFriendRequests(other);
      let otherOut=await this.outgoingFriendRequests(other);

      if(action==="accept"){
        if(!myIn.some(r=>r.from===other))return j({ok:false,error:"طلب الصداقة غير موجود"},404);
        myIn=myIn.filter(r=>r.from!==other);
        otherOut=otherOut.filter(r=>r.to!==s.username);
        const a=await this.friends(s.username), b=await this.friends(other);
        if(!a.includes(other))a.push(other);
        if(!b.includes(s.username))b.push(s.username);
        await this.ctx.storage.put(`friends:${s.username}`,a);
        await this.ctx.storage.put(`friends:${other}`,b);
        const acceptedAt=Date.now();
        const ns=await this.ctx.storage.get(`notifications:${other}`)||[];
        ns.push({id:crypto.randomUUID(),type:"friend-accepted",from:s.username,createdAt:acceptedAt,read:false});
        await this.ctx.storage.put(`notifications:${other}`,ns.slice(-300));
        this.scheduleUserPush(other,{type:"friend-accepted",from:s.username,
          eventId:ns[ns.length-1].id,
          createdAt:ns[ns.length-1].createdAt});

        const otherWs=this.findUser(other);
        if(otherWs){
          this.send(otherWs,{
            type:"friend-accepted",
            from:s.username,
            user:await this.publicUser(s.username),
            createdAt:acceptedAt
          });
        }

        const meWs=this.findUser(s.username);
        if(meWs){
          this.send(meWs,{
            type:"friends-changed",
            with:other,
            action:"accept"
          });
        }
      }else if(action==="decline"){
        myIn=myIn.filter(r=>r.from!==other);
        otherOut=otherOut.filter(r=>r.to!==s.username);
      }else if(action==="cancel"){
        myOut=myOut.filter(r=>r.to!==other);
        otherIn=otherIn.filter(r=>r.from!==s.username);
      }else if(action==="remove"){
        await this.ctx.storage.put(`friends:${s.username}`,(await this.friends(s.username)).filter(x=>x!==other));
        await this.ctx.storage.put(`friends:${other}`,(await this.friends(other)).filter(x=>x!==s.username));
      }else{
        return j({ok:false,error:"إجراء غير صالح"},400);
      }

      await this.ctx.storage.put(`friendIncoming:${s.username}`,myIn);
      await this.ctx.storage.put(`friendOutgoing:${s.username}`,myOut);
      await this.ctx.storage.put(`friendIncoming:${other}`,otherIn);
      await this.ctx.storage.put(`friendOutgoing:${other}`,otherOut);

      if(action!=="accept"){
        const otherWs=this.findUser(other);
        if(otherWs){
          this.send(otherWs,{
            type:"friends-changed",
            with:s.username,
            action
          });
        }
      }

      return j({ok:true,state:await this.friendState(s.username,other)});
    }

    if(url.pathname==="/api/friend-requests" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const requests=[];
      for(const r of [...await this.incomingFriendRequests(s.username)].reverse()){
        const u=await this.publicUser(r.from);
        if(u)requests.push({...r,user:u});
      }
      return j({ok:true,requests});
    }

    if(url.pathname==="/api/friends" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const username=String(url.searchParams.get("username")||s.username).trim().toLowerCase();
      if(await this.isBlockedBetween(s.username,username))return j({ok:false,error:"قائمة الأصدقاء غير متاحة"},403);
      if(!await this.user(username) || !(await this.canViewProfile(s.username,username)))return j({ok:false,error:"المستخدم غير موجود"},404);
      const result=[];
      for(const name of await this.friends(username)){
        if(await this.isBlockedBetween(username,name))continue;
        const u=await this.publicUser(name);
        // A friend may be known directly but is never exposed on somebody else's list.
        if(u && (username===s.username || !u.accountHidden))result.push(u);
      }
      return j({ok:true,friends:result});
    }

    return j({ok:false,error:"Not found"},404);
  }

  async fetch(request){
    if(new URL(request.url).pathname.startsWith("/api/"))return this.api(request);
    if(await this.resetActive())return new Response("Application reset in progress",{status:503});
    return this.trackOperation(()=>this.socketFetch(request));
  }

  async socketFetch(request){
    const url=new URL(request.url);

    if(url.pathname.startsWith("/api/")){
      return this.api(request);
    }

    if(url.pathname!=="/ws"){
      return new Response("Not found",{status:404});
    }

    if((request.headers.get("Upgrade")||"").toLowerCase()!=="websocket"){
      return new Response("WebSocket only",{status:426});
    }

    const token=url.searchParams.get("token")||"";
    const s=await this.session(token);
    if(!s)return new Response("Unauthorized",{status:401});

    const pair=new WebSocketPair();
    const [client,server]=Object.values(pair);

    this.ctx.acceptWebSocket(server);
    this.setMeta(server,{user:s.username,token});
    await this.ctx.storage.put(`lastSeen:${s.username}`,Date.now());

    queueMicrotask(async()=>{
      if(await this.resetActive())return;
      this.send(server,{type:"joined",user:{...await this.publicUser(s.username),role:await this.roleFor(s.username)}});
      await this.trackOperation(async()=>{
        await this.deliverPendingMessages(s.username,server);
        await this.broadcastUsers();
      });
    });

    return new Response(null,{status:101,webSocket:client});
  }


  async deliverPendingMessages(username, ws){
    try{
      const entries=await this.ctx.storage.list({prefix:"messages:"});

      for(const [key,listValue] of entries){
        if(!Array.isArray(listValue))continue;

        let changed=false;

        for(const m of listValue){
          if(m.to!==username || m.read || m.deliveredAt)continue;
          if(!await this.canCommunicate(m.from,username))continue;

          if(this.send(ws,{...m,type:"chat",offlineDelivery:true})){
            m.deliveredAt=Date.now();
            changed=true;

            const sender=this.findUser(m.from);
            if(sender){
              this.send(sender,{
                type:"delivered",
                messageId:m.id,
                by:username,
                deliveredAt:m.deliveredAt
              });
            }
          }
        }

        if(changed){
          await this.ctx.storage.put(key,listValue.slice(-300));
        }
      }
    }catch(err){
      console.warn("deliverPendingMessages failed",err);
    }
  }

  async broadcastUsers(){
    const users=[];
    for(const ws of this.sockets()){
      const username=this.meta(ws).user;
      if(!username)continue;
      const pu=await this.publicUser(username);
      if(pu && !users.some(x=>x.username===pu.username))users.push(pu);
    }

    for(const ws of this.sockets()){
      const viewer=this.meta(ws).user;
      this.send(ws,{type:"users",users:users.filter(u=>!u.accountHidden || u.username===viewer)});
    }
  }

  // One canonical per-call summary in the existing private conversation, NOT
  // repeated signaling messages. HTTP and WebSocket copies share the call ID.
  async recordCallEvent(from,to,event){
    const id=String(event.callId||'').trim();
    if(!/^[A-Za-z0-9_-]{8,128}$/.test(id))return;
    const type=String(event.type||'');
    if(!['call-request','call-accept','call-reject','hangup'].includes(type))return;
    const key=[from,to].sort().join(':');
    const storageKey='messages:'+key;
    const messages=await this.ctx.storage.get(storageKey)||[];
    let record=messages.find(m=>m.mediaType==='call-event' && m.callId===id);
    if(!record){
      // A reply/end is not sufficient proof of who called whom.
      if(type!=='call-request')return;
      record={type:'chat',id:'call:'+id,from,to,mediaType:'call-event',
        media:'',text:'',callId:id,callType:event.callType==='video'?'video':'audio',
        callStatus:'ringing',ts:Date.now(),read:false,deliveredAt:0};
      messages.push(record);
    }else if(type==='call-accept' && record.callStatus==='ringing'){
      record.callStatus='answered';
    }else if(type==='call-reject' && !['completed','rejected','missed','cancelled'].includes(record.callStatus)){
      record.callStatus=event.reason==='busy'?'busy':'rejected';
    }else if(type==='hangup' && !['completed','rejected','missed','cancelled','busy'].includes(record.callStatus)){
      record.callStatus=record.callStatus==='answered'?'completed':'cancelled';
    }
    await this.ctx.storage.put(storageKey,messages.slice(-300));
  }

  // A call unanswered after its invitation expires becomes a missed-call entry.
  // This runs during chat/inbox reads, even when both devices were offline.
  finishExpiredCallEvents(messages){
    const now=Date.now();let changed=false;
    for(const m of messages){
      if(m.mediaType==='call-event' && m.callStatus==='ringing' &&
          now-Number(m.ts||0)>55000){m.callStatus='missed';changed=true;}
    }
    return changed;
  }

  // Phone foreground polling fallback: persist only the small invitation/cancellation
  // events, not ICE/SDP media signaling, and never expose them without authentication.
  async queuePhoneCallEvent(to,event){
    if(!["call-request","call-reject","hangup"].includes(event.type) || !event.callId)return;
    const now=Date.now();
    const key=`callSignals:${to}`;
    const prior=await this.ctx.storage.get(key)||[];
    const recent=prior.filter(x=>now-Number(x.createdAt||0)<45000);
    if(!recent.some(x=>x.signalId && x.signalId===event.signalId))recent.push(event);
    await this.ctx.storage.put(key,recent.slice(-60));
  }

  // FCM tokens are stored per authenticated account, not exposed via user profiles.
  // Firebase's service-account JSON belongs ONLY in a Cloudflare Worker secret.
  async fcmAccessToken(){
    const raw=this.env.FIREBASE_SERVICE_ACCOUNT_JSON;
    if(!raw)return null;
    if(this.fcmToken && this.fcmExpires>Date.now()+60000)return this.fcmToken;
    const sa=JSON.parse(raw);
    if(!sa.client_email||!sa.private_key||!sa.project_id)return null;
    const jwtPart=obj=>btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(obj))))
      .replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
    const now=Math.floor(Date.now()/1000);
    const claim=jwtPart({iss:sa.client_email,
      scope:'https://www.googleapis.com/auth/firebase.messaging',
      aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3300});
    const head=jwtPart({alg:'RS256',typ:'JWT'});
    const unsigned=head+'.'+claim;
    const pem=sa.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,'');
    const binary=Uint8Array.from(atob(pem),c=>c.charCodeAt(0));
    const key=await crypto.subtle.importKey('pkcs8',binary,
      {name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
    const signature=new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key,encoder.encode(unsigned)));
    const signed=unsigned+'.'+btoa(String.fromCharCode(...signature))
      .replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
    const params=new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:signed});
    const resp=await fetch('https://oauth2.googleapis.com/token',{
      method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:params});
    if(!resp.ok)throw Error('Firebase OAuth token request failed: '+resp.status);
    const result=await resp.json();
    this.fcmToken=result.access_token;
    this.fcmExpires=Date.now()+Math.min(Number(result.expires_in||3000),3300)*1000;
    return this.fcmToken;
  }

  async sendFcmToUser(to,data,ttl){
    if(!this.env.FIREBASE_SERVICE_ACCOUNT_JSON)
      return {accepted:0,attempted:0,error:'FCM_NOT_CONFIGURED'};
    let attempted=0,accepted=0,lastError='';
    try {
      const tokens=await this.ctx.storage.get('fcmTokens:'+to)||[];
      if(!tokens.length)return {accepted:0,attempted:0,error:'NO_REGISTERED_DEVICES'};
      const accessToken=await this.fcmAccessToken();
      if(!accessToken)return {accepted:0,attempted:0,error:'FCM_AUTH_NOT_AVAILABLE'};
      const sa=JSON.parse(this.env.FIREBASE_SERVICE_ACCOUNT_JSON);
      const project=String(this.env.FIREBASE_PROJECT_ID||sa.project_id||'');
      if(!project || project!==sa.project_id)throw Error('Firebase project ID mismatch');
      const url='https://fcm.googleapis.com/v1/projects/'+encodeURIComponent(project)+'/messages:send';
      // Firebase reserves data key "from"; keep it in our internal call/chat
      // events, but rename it only in the outgoing FCM payload.
      const fcmData={...data};
      if(Object.prototype.hasOwnProperty.call(fcmData,'from')){
        fcmData.sender=String(fcmData.from??'');
        delete fcmData.from;
      }
      const survivors=[];
      for(const deviceToken of tokens.slice(-5)){
        attempted++;
        try {
          // Call invitations must have a visible OS notification when Android
          // suspends our process. Data-only messages can be delayed/deprioritized
          // by Doze/OEM background restrictions, even if push-test works.
          // The service handles this same payload while the app is foregrounded;
          // Android displays the notification itself when it is backgrounded.
          const android={priority:'HIGH',ttl};
          if(fcmData.type==='call-request'){
            android.notification={
              channel_id:'alatta_incoming_call_v2',icon:'atta_notification_icon',
              title:fcmData.callType==='video'?'مكالمة فيديو واردة':'مكالمة صوتية واردة',
              body:'مكالمة واردة من تواصل العطا',
              visibility:'PUBLIC',notification_priority:'PRIORITY_MAX',
              tag:'alatta-call-'+String(fcmData.callId||'').slice(0,128)
            };
          }
          const resp=await fetch(url,{method:'POST',headers:{
            Authorization:'Bearer '+accessToken,'Content-Type':'application/json'
          },body:JSON.stringify({message:{token:deviceToken,data:fcmData,android}})});
          if(resp.ok){accepted++;survivors.push(deviceToken);continue;}
          const body=await resp.json().catch(()=>({}));
          const badToken=resp.status===404 || (resp.status===400 &&
            JSON.stringify(body).includes('UNREGISTERED'));
          if(!badToken)survivors.push(deviceToken);
          const firebaseError=body?.error||{};
          const safeReason=String(firebaseError.status||'UNKNOWN').slice(0,48);
          const field=(firebaseError.details||[]).flatMap(item=>
            Array.isArray(item.fieldViolations)?item.fieldViolations.map(v=>v.field):[])
            .filter(v=>typeof v==='string').slice(0,3).join(',');
          lastError='FCM_HTTP_'+resp.status;
          // Don't log registration tokens, credentials or the whole response body.
          console.warn('FCM delivery rejected:',resp.status,safeReason,field);
        }catch(_){survivors.push(deviceToken);lastError='FCM_NETWORK_ERROR';}
      }
      if(survivors.length!==tokens.length)
        await this.ctx.storage.put('fcmTokens:'+to,survivors);
      return {attempted,accepted,...(lastError?{error:lastError}:{})};
    }catch(e){
      console.warn('FCM push unavailable:',String(e.message||e));
      return {attempted,accepted,error:'FCM_SERVER_ERROR'};
    }
  }

  async hmsAccessToken(){
    if(!this.env.HUAWEI_PUSH_APP_ID || !this.env.HUAWEI_PUSH_APP_SECRET)return null;
    if(this.hmsToken && this.hmsExpires>Date.now()+60000)return this.hmsToken;
    const response=await fetch('https://oauth-login.cloud.huawei.com/oauth2/v3/token',{
      method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body:new URLSearchParams({grant_type:'client_credentials',client_id:String(this.env.HUAWEI_PUSH_APP_ID),client_secret:String(this.env.HUAWEI_PUSH_APP_SECRET)})});
    if(!response.ok)throw Error('HMS_AUTH_HTTP_'+response.status);
    const result=await response.json();
    if(!result.access_token)throw Error('HMS_AUTH_UNAVAILABLE');
    this.hmsToken=result.access_token;this.hmsExpires=Date.now()+Math.min(Number(result.expires_in)||3000,3300)*1000;
    return this.hmsToken;
  }

  async sendHmsToUser(to,data,ttl){
    if(!this.env.HUAWEI_PUSH_APP_ID || !this.env.HUAWEI_PUSH_APP_SECRET)return {accepted:0,attempted:0,error:'HMS_NOT_CONFIGURED'};
    let accepted=0,attempted=0,lastError='';
    try{
      const tokens=await this.ctx.storage.get('hmsTokens:'+to)||[];
      if(!tokens.length)return {accepted,attempted,error:'NO_REGISTERED_DEVICES'};
      const access=await this.hmsAccessToken(),survivors=[];
      for(const deviceToken of tokens.slice(-5)){
        attempted++;
        try{
          const response=await fetch('https://push-api.cloud.huawei.com/v1/'+encodeURIComponent(this.env.HUAWEI_PUSH_APP_ID)+'/messages:send',{
            method:'POST',headers:{Authorization:'Bearer '+access,'Content-Type':'application/json'},
            body:JSON.stringify({validate_only:false,message:{token:[deviceToken],data:JSON.stringify(data),android:{urgency:'HIGH',ttl}}})});
          const result=await response.json().catch(()=>({}));
          if(response.ok && String(result.code)==='80000000'){accepted++;survivors.push(deviceToken);}
          else {lastError='HMS_REJECTED_'+String(result.code||response.status).replace(/[^A-Z0-9_]/gi,'').slice(0,32);
            if(String(result.code)!=='80300007')survivors.push(deviceToken);}
        }catch{survivors.push(deviceToken);lastError='HMS_NETWORK_ERROR';}
      }
      if(survivors.length!==tokens.length)await this.ctx.storage.put('hmsTokens:'+to,survivors);
      return {accepted,attempted,...(lastError?{error:lastError}:{})};
    }catch{return {accepted,attempted,error:'HMS_SERVER_ERROR'};}
  }

  async sendDevicePush(to,data,ttl){
    const results=await Promise.all([this.sendFcmToUser(to,data,ttl),this.sendHmsToUser(to,data,ttl)]);
    const accepted=results.reduce((sum,r)=>sum+r.accepted,0),attempted=results.reduce((sum,r)=>sum+r.attempted,0);
    return {accepted,attempted,...(!accepted?{error:results.map(r=>r.error).filter(Boolean).join(';')}:{} )};
  }

  async deliverCallPush(to,event){
    if(!['call-request','hangup','call-reject'].includes(event.type))return;
    const result=await this.sendDevicePush(to,{
      type:String(event.type),from:String(event.from),to:String(to),
      callId:String(event.callId),callType:event.callType==='video'?'video':'audio',
      createdAt:String(event.createdAt||Date.now())
    },'45s');
    if(event.type==='call-request'){
      // Keep just the last delivery outcome for the recipient's diagnostic menu.
      // Do not store or expose user ids, FCM tokens, call ids, or credentials.
      await this.ctx.storage.put('lastCallPushResult:'+to,{
        timestamp:Date.now(),attempted:result.attempted||0,
        accepted:result.accepted||0,error:String(result.error||'').slice(0,48)
      });
    }
  }

  // Social/message push is sent only after the authenticated event was stored.
  // No chat body or user token is included in the FCM data payload.
  scheduleUserPush(to,event){
    if(!this.env.FIREBASE_SERVICE_ACCOUNT_JSON)return;
    if(!['chat','friend-request','friend-accepted','like','reaction','comment',
      'comment-reply','follow','share'].includes(event.type))return;
    this.ctx.waitUntil(this.trackOperation(()=>this.deliverUserPush(to,event)));
  }

  async deliverUserPush(to,event){
    const sender=await this.user(event.from||"");
    const anonymous=!!sender?.accountHidden && ["like","reaction","comment","comment-reply"].includes(event.type);
    if(sender && (sender.banned || sender.disabled || await this.isBlockedBetween(to,event.from)))return;
    await this.sendDevicePush(to,{
      type:String(event.type),to:String(to),from:anonymous?"":String(event.from||''),
      fromName:anonymous?"حساب مخفي":String(sender?.fullName||sender?.displayName||sender?.username||""),
      eventId:String(event.eventId),createdAt:String(event.createdAt||Date.now())
    },'86400s');
  }

  scheduleCallPush(to,event){
    // Avoid duplicate notifications when the same call is sent via HTTP and WS.
    // Never delay or fail actual call signaling because push is unavailable.
    if(!this.env.FIREBASE_SERVICE_ACCOUNT_JSON ||
      !['call-request','hangup','call-reject'].includes(event.type))return;
    const now=Date.now();
    if(!this.recentPush)this.recentPush=new Map();
    if(this.recentPush.size>400){
      for(const [key,ts] of this.recentPush)if(now-ts>60000)this.recentPush.delete(key);
    }
    const key=to+':'+event.callId+':'+event.type;
    if(this.recentPush.has(key) && now-this.recentPush.get(key)<30000)return;
    this.recentPush.set(key,now);
    this.ctx.waitUntil(this.trackOperation(()=>this.deliverCallPush(to,event)));
  }

  async addCallLog(a,b,kind,status){
    const ts=Date.now();
    for(const [owner,other] of [[a,b],[b,a]]){
      const list=await this.ctx.storage.get(`calls:${owner}`)||[];
      list.push({
        id:crypto.randomUUID(),
        with:other,
        kind,
        status,
        ts
      });
      await this.ctx.storage.put(`calls:${owner}`,list.slice(-200));
    }
  }

  async webSocketMessage(ws,message){
    if(await this.resetActive()){try{ws.close(1012,"Application reset");}catch{}return;}
    return this.trackOperation(()=>this.handleSocketMessage(ws,message));
  }

  async handleSocketMessage(ws,message){
    // Native/chat text and SDP fit below the same API limit. Close oversized
    // packets before decoding/parsing them, including binary WebSocket messages.
    if((typeof message==="string"?message.length:message.byteLength)>MAX_API_JSON_BYTES){
      try{ws.close(1009,"Message too large");}catch{}return;
    }
    let msg;
    try{
      msg=JSON.parse(typeof message==="string"?message:new TextDecoder().decode(message));
      if(!msg || Array.isArray(msg) || typeof msg!=="object")return;
    }catch{return}

    const attachment=this.meta(ws);
    const from=attachment.user;
    if(!from)return;
    const active=attachment.token ? await this.session(attachment.token) : null;
    if(!active || active.username!==from){
      try{ws.close(1008,"Session expired");}catch{}
      return;
    }

    if(msg.type==="chat"){
      const to=String(msg.to||"").trim().toLowerCase();
      if(!to || to===from){
        this.send(ws,{type:"error",message:"مستلم الرسالة غير صالح"});
        return;
      }
      if(!await this.user(to)){
        this.send(ws,{type:"error",message:"المستخدم غير موجود"});
        return;
      }
      if(!await this.canCommunicate(from,to)){
        this.send(ws,{type:"error",message:"المحادثات متاحة للأصدقاء فقط"});
        return;
      }

      const target=this.findUser(to);
      const mediaRaw=String(msg.media||"");
      if(mediaRaw.length>950000){
        this.send(ws,{type:"error",message:"حجم المرفق كبير جدًا"});
        return;
      }
      const payload={
        type:"chat",
        id:crypto.randomUUID(),
        from,
        to,
        text:String(msg.text||"").slice(0,6000),
        media:mediaRaw,
        mediaType:String(msg.mediaType||"text"),
        ts:Date.now(),
        read:false,
        deliveredAt:target?Date.now():0
      };

      const key=[from,to].sort().join(":");
      const list=await this.ctx.storage.get(`messages:${key}`)||[];
      list.push(payload);
      await this.ctx.storage.put(`messages:${key}`,list.slice(-300));
      if(payload.mediaType!=="call-signal")this.scheduleUserPush(to,{
        type:"chat",from,eventId:payload.id,createdAt:payload.ts
      });

      if(target){
        this.send(target,payload);
      }

      // Always confirm to sender: message is stored even if recipient is offline.
      this.send(ws,{
        ...payload,
        stored:true,
        delivered:!!target
      });
      return;
    }

    if(msg.type==="typing"){
      if(!await this.canCommunicate(from,String(msg.to||'').trim().toLowerCase()))return;
      const target=this.findUser(String(msg.to||"").trim().toLowerCase());
      if(target)this.send(target,{type:"typing",from,active:!!msg.active});return;
    }

    if(msg.type==="read"){
      const other=String(msg.with||"").trim().toLowerCase();
      if(!await this.canCommunicate(from,other))return;
      const key=[from,other].sort().join(":");
      const list=await this.ctx.storage.get(`messages:${key}`)||[];
      for(const m of list)if(m.to===from)m.read=true;
      await this.ctx.storage.put(`messages:${key}`,list);
      const target=this.findUser(other);if(target)this.send(target,{type:"read",from});return;
    }

    if(msg.type==="group-chat"){
      const group=await this.ctx.storage.get(`group:${msg.groupId}`);
      if(!group || !group.members.includes(from))return;

      const payload={
        type:"group-chat",
        groupId:group.id,
        from,
        text:String(msg.text||""),
        ts:Date.now()
      };

      for(const username of group.members){
        const target=this.findUser(username);
        if(target)this.send(target,payload);
      }
      return;
    }

    if(["offer","answer","ice","call-request","call-ringing","call-accept","call-reject","hangup"].includes(msg.type)){
      const to=String(msg.to||"").trim().toLowerCase();
      if(!await this.canCommunicate(from,to)){
        this.send(ws,{type:"error",message:"المكالمات متاحة للأصدقاء فقط"});return;
      }
      const target=this.findUser(to);
      // Queue incoming invitations even when the sender could only use WebSocket.
      // The HTTP copy has the same signalId and is deduplicated by queuePhoneCallEvent.
      const event={
        type:msg.type, from, to, callId:String(msg.callId||""),
        callType:msg.callType==="video"?"video":"audio",
        signalId:String(msg.signalId||crypto.randomUUID()), createdAt:Date.now()
      };
      await this.queuePhoneCallEvent(to,event);
      await this.recordCallEvent(from,to,{...event,reason:String(msg.reason||'')});
      this.scheduleCallPush(to,event);

      if(msg.type==="call-request"){
        await this.addCallLog(from,to,msg.callType||"video","outgoing");
      }else if(msg.type==="call-reject"){
        await this.addCallLog(from,to,msg.callType||"video","rejected");
      }else if(msg.type==="hangup"){
        await this.addCallLog(from,to,msg.callType||"video","ended");
      }

      if(target)this.send(target,{...msg,from});
      else this.send(ws,{type:"user-offline",user:to});
    }
  }

  async webSocketClose(ws){
    if(await this.resetActive())return;
    return this.trackOperation(()=>this.handleSocketClose(ws));
  }
  async handleSocketClose(ws){
    const user=this.meta(ws).user;
    if(user && await this.session(this.meta(ws).token))await this.ctx.storage.put(`lastSeen:${user}`,Date.now());
    await this.broadcastUsers();
  }
  async webSocketError(ws){
    return this.webSocketClose(ws);
  }
}


export default {
  async fetch(request,env){
    const url=new URL(request.url);

    if(url.pathname==="/health"){
      return Response.json({
        ok:true,
        app:"تواصل العطا",
        version:"V13.88-Post-Media"
      });
    }

    if(url.pathname==="/privacy" || url.pathname==="/privacy/"){
      return env.ASSETS.fetch(new Request(new URL("/privacy.html", url.origin), request));
    }

    if(url.pathname==="/terms" || url.pathname==="/terms/"){
      return env.ASSETS.fetch(new Request(new URL("/terms.html", url.origin), request));
    }

    if(url.pathname.startsWith("/media/")){
      if(!env.MEDIA_BUCKET)return new Response("Media storage is not configured",{status:404});
      let key;
      try{key=decodeURIComponent(url.pathname.slice("/media/".length));}
      catch{return new Response("Invalid media path",{status:400});}
      if(!key || key.includes("..") || key.includes("\\") || key.startsWith("/")){
        return new Response("Invalid media path",{status:400});
      }
      if(!["GET","HEAD"].includes(request.method))return new Response("Method not allowed",{status:405,headers:{allow:"GET, HEAD"}});
      // Ordinary images/full downloads need only one R2 read; seeking needs metadata first.
      const meta=request.method==="HEAD" || request.headers.has("range")
        ?await env.MEDIA_BUCKET.head(key):await env.MEDIA_BUCKET.get(key);
      if(!meta)return new Response("Not found",{status:404});
      const headers=new Headers();
      meta.writeHttpMetadata(headers);
      headers.set("x-content-type-options","nosniff");
      const type=String(headers.get("content-type")||"").split(";")[0].trim().toLowerCase();
      if(!RASTER_IMAGE_TYPES.has(type) && !type.startsWith("video/") && !type.startsWith("audio/") && type!=="application/pdf"){
        // Protect existing SVG/HTML/XML objects uploaded before the raster-only
        // rule. A direct link must never execute script on the application's origin.
        headers.set("content-security-policy","sandbox; default-src 'none'");
        headers.set("content-disposition","attachment");
      }
      headers.set("etag",meta.httpEtag);
      headers.set("accept-ranges","bytes");
      headers.set("cache-control","public, max-age=31536000, immutable");
      headers.set("content-length",String(meta.size));
      if(request.method==="HEAD")return new Response(null,{headers});
      // Android's frame extractor seeks within MP4s. Returning the whole file for
      // every Range request leaves older video posts with a black preview.
      let range;
      const raw=request.headers.get("range");
      const ifRange=request.headers.get("if-range");
      if(raw && (!ifRange || ifRange===meta.httpEtag)){
        const m=/^bytes=(\d*)-(\d*)$/.exec(raw);
        let start=0,end=meta.size-1;
        if(m && (m[1] || m[2])){
          if(!m[1])start=Math.max(0,meta.size-Number(m[2]));
          else {start=Number(m[1]);if(m[2])end=Math.min(end,Number(m[2]));}
        }
        if(!m || (!m[1]&&!m[2]) || !Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start<0 || start>=meta.size || end<start){
          headers.set("content-range",`bytes */${meta.size}`);headers.set("content-length","0");
          return new Response(null,{status:416,headers});
        }
        range={offset:start,length:end-start+1};
        headers.set("content-range",`bytes ${start}-${end}/${meta.size}`);
        headers.set("content-length",String(range.length));
      }
      const obj="body" in meta?meta:await env.MEDIA_BUCKET.get(key,range?{range}:undefined);
      if(!obj)return new Response("Not found",{status:404});
      return new Response(obj.body,{status:range?206:200,headers});
    }

    // YouTube Data API requests are server-side. Never expose YOUTUBE_API_KEY.
    if(url.pathname==="/api/youtube" && request.method==="GET"){
      const key=String(env.YOUTUBE_API_KEY||"").trim();
      if(!key)return Response.json({ok:false,error:"مفتاح YouTube غير مُعد"},{status:503});
      const query=(url.searchParams.get("q")||"").trim().slice(0,100)||"تلاوة القرآن الكريم";
      const pageToken=(url.searchParams.get("pageToken")||"").trim();
      if(pageToken && !/^[A-Za-z0-9_-]{1,100}$/.test(pageToken))return Response.json({ok:false,error:"صفحة غير صالحة"},{status:400});
      const mode=query?"search":"popular";
      const params=new URLSearchParams({key,part:query?"snippet":"snippet,statistics",maxResults:"12",regionCode:"SA"});
      let endpoint;
      if(query){
        params.set("q",query);params.set("type","video");params.set("videoEmbeddable","true");params.set("safeSearch","moderate");
        endpoint="https://www.googleapis.com/youtube/v3/search";
      }else{
        params.set("chart","mostPopular");endpoint="https://www.googleapis.com/youtube/v3/videos";
      }
      if(pageToken)params.set("pageToken",pageToken);
      const cacheUrl=new URL(url.origin+"/youtube-api-cache");
      cacheUrl.searchParams.set("mode",mode);cacheUrl.searchParams.set("q",query.toLowerCase());cacheUrl.searchParams.set("pageToken",pageToken);
      const cacheKey=new Request(cacheUrl.toString());
      const cache=caches.default;
      const hit=await cache.match(cacheKey);
      if(hit)return hit;
      try{
        const response=await fetch(endpoint+"?"+params.toString(),{headers:{accept:"application/json"}});
        const data=await response.json();
        if(!response.ok){
          console.warn("YouTube API error",response.status,data?.error?.status);
          return Response.json({ok:false,error:response.status===403?"حصة يوتيوب انتهت أو المفتاح غير مصرح له":"تعذر تحميل فيديوهات يوتيوب"},{status:response.status===403?429:502});
        }
        const items=(data.items||[]).map(v=>({
          id:query?v.id?.videoId:v.id,
          title:v.snippet?.title||"فيديو",
          channel:v.snippet?.channelTitle||"",
          thumbnail:v.snippet?.thumbnails?.medium?.url||v.snippet?.thumbnails?.default?.url||"",
          publishedAt:v.snippet?.publishedAt||""
        })).filter(v=>/^[A-Za-z0-9_-]{11}$/.test(v.id||""));
        const result=Response.json({ok:true,items,nextPageToken:data.nextPageToken||""},{headers:{"Cache-Control":"public, max-age=600"}});
        // Cache is per Cloudflare edge; search still costs 100 YouTube quota units on cache miss.
        await cache.put(cacheKey,result.clone());
        return result;
      }catch(e){
        console.error("YouTube fetch error",e);
        return Response.json({ok:false,error:"تعذر الاتصال بيوتيوب"},{status:502});
      }
    }

    if(url.pathname==="/ws" || url.pathname.startsWith("/api/")){
      const id=env.SIGNALING.idFromName("global-room");
      return env.SIGNALING.get(id).fetch(request);
    }

    // One source of truth for the APK button: public/index.html.
    return env.ASSETS.fetch(request);
  }
};
