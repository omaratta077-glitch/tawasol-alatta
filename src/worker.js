import { DurableObject } from "cloudflare:workers";

const encoder = new TextEncoder();

async function hashPassword(text){
  const buf = await crypto.subtle.digest("SHA-256", encoder.encode(String(text)));
  return [...new Uint8Array(buf)].map(b=>b.toString(16).padStart(2,"0")).join("");
}

function j(data,status=200){
  return new Response(JSON.stringify(data),{
    status,
    headers:{"content-type":"application/json;charset=utf-8"}
  });
}

export class SignalingRoom extends DurableObject {
  constructor(ctx, env){
    super(ctx, env);
    this.ctx=ctx;
    this.env=env;
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

  async user(username){
    return await this.ctx.storage.get(`user:${username}`)||null;
  }

  async publicUser(username){
    const u=await this.user(username);
    if(!u)return null;
    return {
      username:u.username,
      displayName:u.displayName||u.username,
      avatar:u.avatar||""
    };
  }

  async createSession(username){
    const token=crypto.randomUUID()+crypto.randomUUID();
    await this.ctx.storage.put(`session:${token}`,{username,createdAt:Date.now()});
    return token;
  }

  async session(token){
    return token ? (await this.ctx.storage.get(`session:${token}`)||null) : null;
  }

  async api(request){
    const url=new URL(request.url);
    let body={};
    if(request.method!=="GET"){
      try{body=await request.json()}catch{}
    }

    if(url.pathname==="/api/register" && request.method==="POST"){
      const username=String(body.username||"").trim().toLowerCase();
      const displayName=String(body.displayName||username).trim();
      const password=String(body.password||"");

      if(username.length<3)return j({ok:false,error:"اسم المستخدم 3 أحرف على الأقل"},400);
      if(password.length<6)return j({ok:false,error:"كلمة المرور 6 أحرف على الأقل"},400);
      if(await this.user(username))return j({ok:false,error:"اسم المستخدم موجود بالفعل"},409);

      await this.ctx.storage.put(`user:${username}`,{
        username,
        displayName,
        passwordHash:await hashPassword(password),
        avatar:"",
        createdAt:Date.now()
      });

      const token=await this.createSession(username);
      return j({ok:true,token,user:await this.publicUser(username)});
    }

    if(url.pathname==="/api/login" && request.method==="POST"){
      const username=String(body.username||"").trim().toLowerCase();
      const password=String(body.password||"");
      const u=await this.user(username);

      if(!u || u.passwordHash!==await hashPassword(password)){
        return j({ok:false,error:"اسم المستخدم أو كلمة المرور غير صحيحة"},401);
      }

      const token=await this.createSession(username);
      return j({ok:true,token,user:await this.publicUser(username)});
    }

    if(url.pathname==="/api/profile" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const u=await this.user(s.username);
      if(!u)return j({ok:false,error:"المستخدم غير موجود"},404);

      if(typeof body.displayName==="string" && body.displayName.trim()){
        u.displayName=body.displayName.trim().slice(0,60);
      }
      if(typeof body.avatar==="string"){
        u.avatar=body.avatar.slice(0,250000);
      }

      await this.ctx.storage.put(`user:${u.username}`,u);
      return j({ok:true,user:await this.publicUser(u.username)});
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
        if(g && g.members.includes(s.username))groups.push(g);
      }
      return j({ok:true,groups});
    }

    if(url.pathname==="/api/calls" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const calls=await this.ctx.storage.get(`calls:${s.username}`)||[];
      return j({ok:true,calls:calls.slice(-100).reverse()});
    }

    return j({ok:false,error:"Not found"},404);
  }

  async fetch(request){
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

    const s=await this.session(url.searchParams.get("token")||"");
    if(!s)return new Response("Unauthorized",{status:401});

    const pair=new WebSocketPair();
    const [client,server]=Object.values(pair);

    this.ctx.acceptWebSocket(server);
    this.setMeta(server,{user:s.username});

    queueMicrotask(async()=>{
      this.send(server,{type:"joined",user:await this.publicUser(s.username)});
      await this.broadcastUsers();
    });

    return new Response(null,{status:101,webSocket:client});
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
      this.send(ws,{type:"users",users});
    }
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
    let msg;
    try{
      msg=JSON.parse(typeof message==="string"?message:new TextDecoder().decode(message));
    }catch{return}

    const from=this.meta(ws).user;
    if(!from)return;

    if(msg.type==="chat"){
      const to=String(msg.to||"").trim().toLowerCase();
      const payload={type:"chat",from,to,text:String(msg.text||""),ts:Date.now()};

      const target=this.findUser(to);
      if(target)this.send(target,payload);
      else this.send(ws,{type:"user-offline",user:to});

      this.send(ws,payload);
      return;
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

    if(["offer","answer","ice","call-request","call-accept","call-reject","hangup"].includes(msg.type)){
      const to=String(msg.to||"").trim().toLowerCase();
      const target=this.findUser(to);

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

  async webSocketClose(){ await this.broadcastUsers(); }
  async webSocketError(){ await this.broadcastUsers(); }
}

export default {
  async fetch(request,env){
    const url=new URL(request.url);

    if(url.pathname==="/health"){
      return Response.json({
        ok:true,
        app:"تواصل العطا",
        version:"V4"
      });
    }

    if(url.pathname==="/ws" || url.pathname.startsWith("/api/")){
      const id=env.SIGNALING.idFromName("global-room");
      return env.SIGNALING.get(id).fetch(request);
    }

    return env.ASSETS.fetch(request);
  }
};
