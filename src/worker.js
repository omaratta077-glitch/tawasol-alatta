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
      fullName:u.fullName||u.displayName||u.username,
      country:u.country||"",
      bio:u.bio||"",
      cover:u.cover||"",
      accountPrivate:!!u.accountPrivate,
      avatar:u.avatar||""
    };
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
      const fullName=String(body.fullName||"").trim();
      const username=String(body.username||"").trim().toLowerCase();
      const age=Number(body.age||0);
      const phone=String(body.phone||"").trim().replace(/\s+/g,"");
      const email=String(body.email||"").trim().toLowerCase();
      const gender=String(body.gender||"").trim();
      const country=String(body.country||"").trim();
      const password=String(body.password||"");
      const confirmPassword=String(body.confirmPassword||"");

      if(fullName.length<3)return j({ok:false,error:"اكتب الاسم الكامل"},400);
      if(username.length<3)return j({ok:false,error:"اسم المستخدم 3 أحرف على الأقل"},400);
      if(!Number.isFinite(age) || age<13 || age>120)return j({ok:false,error:"اكتب عمرًا صحيحًا من 13 إلى 120"},400);
      if(!phone && !email)return j({ok:false,error:"أدخل رقم الهاتف أو البريد الإلكتروني على الأقل"},400);
      if(phone && !/^\+?[0-9]{8,15}$/.test(phone))return j({ok:false,error:"رقم الهاتف غير صحيح"},400);
      if(email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))return j({ok:false,error:"البريد الإلكتروني غير صحيح"},400);
      if(password.length<6)return j({ok:false,error:"كلمة المرور 6 أحرف على الأقل"},400);
      if(password!==confirmPassword)return j({ok:false,error:"كلمتا المرور غير متطابقتين"},400);
      if(await this.user(username))return j({ok:false,error:"اسم المستخدم موجود بالفعل"},409);

      const existingUsers=await this.ctx.storage.get("usernames")||[];
      for(const existingName of existingUsers){
        const existing=await this.user(existingName);
        if(!existing)continue;
        if(phone && existing.phone===phone)return j({ok:false,error:"رقم الهاتف مستخدم بالفعل"},409);
        if(email && existing.email===email)return j({ok:false,error:"البريد الإلكتروني مستخدم بالفعل"},409);
      }

      await this.ctx.storage.put(`user:${username}`,{
        username,
        displayName:fullName,
        fullName,
        age,
        phone,
        email,
        gender,
        country,
        passwordHash:await hashPassword(password),
        avatar:"",
        createdAt:Date.now()
      });

      existingUsers.push(username);
      await this.ctx.storage.put("usernames",[...new Set(existingUsers)]);

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
      if(typeof body.avatar==="string")u.avatar=body.avatar.slice(0,250000);
      if(typeof body.cover==="string")u.cover=body.cover.slice(0,500000);
      if(typeof body.bio==="string")u.bio=body.bio.slice(0,300);
      if(typeof body.accountPrivate==="boolean")u.accountPrivate=body.accountPrivate;

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

    
    if(url.pathname==="/api/users" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const q=String(url.searchParams.get("q")||"").trim().toLowerCase();
      const names=await this.ctx.storage.get("usernames")||[];
      const users=[];

      for(const name of names){
        const u=await this.publicUser(name);
        if(!u)continue;
        if(!q || u.username.includes(q) || String(u.fullName||u.displayName||"").toLowerCase().includes(q)){
          users.push(u);
        }
      }

      return j({ok:true,users:users.slice(0,100)});
    }

    if(url.pathname==="/api/posts" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const text=String(body.text||"").trim().slice(0,5000);
      const image=String(body.image||"").slice(0,500000);

      if(!text && !image)return j({ok:false,error:"اكتب منشورًا أو أضف صورة"},400);

      const id=crypto.randomUUID();
      const post={
        id,
        author:s.username,
        text,
        image,
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

    if(url.pathname==="/api/posts" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const ids=await this.ctx.storage.get("postIds")||[];
      const posts=[];

      for(const id of [...ids].reverse()){
        const p=await this.ctx.storage.get(`post:${id}`);
        if(!p)continue;
        const saved=await this.ctx.storage.get(`saved:${s.username}`)||[];
        const reactionCounts={};
        const reactions=p.reactions||{};
        for(const key of ["like","love","haha","wow","sad"]){
          reactionCounts[key]=Array.isArray(reactions[key])?reactions[key].length:0;
        }

        let myReaction="";
        for(const key of Object.keys(reactionCounts)){
          if((reactions[key]||[]).includes(s.username)){myReaction=key;break;}
        }

        let sharedPost=null;
        if(p.sharedPostId){
          const original=await this.ctx.storage.get(`post:${p.sharedPostId}`);
          if(original){
            sharedPost={...original,authorInfo:await this.publicUser(original.author)};
          }
        }

        posts.push({
          ...p,
          authorInfo:await this.publicUser(p.author),
          likeCount:(p.likes||[]).length,
          commentCount:(p.comments||[]).length,
          likedByMe:(p.likes||[]).includes(s.username),
          reactionCounts,
          myReaction,
          savedByMe:saved.includes(p.id),
          sharedPost
        });
      }

      return j({ok:true,posts});
    }

    if(url.pathname==="/api/post-like" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const post=await this.ctx.storage.get(`post:${body.postId}`);
      if(!post)return j({ok:false,error:"المنشور غير موجود"},404);

      post.likes=post.likes||[];
      const idx=post.likes.indexOf(s.username);
      let liked=false;

      if(idx>=0){
        post.likes.splice(idx,1);
      }else{
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
      }

      return j({ok:true,comment});
    }

    if(url.pathname==="/api/notifications" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const list=await this.ctx.storage.get(`notifications:${s.username}`)||[];
      return j({ok:true,notifications:[...list].reverse().slice(0,100)});
    }

    if(url.pathname==="/api/notifications/read" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const list=await this.ctx.storage.get(`notifications:${s.username}`)||[];
      for(const n of list)n.read=true;
      await this.ctx.storage.put(`notifications:${s.username}`,list);
      return j({ok:true});
    }



    if(url.pathname==="/api/follow" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const target=String(body.username||"").trim().toLowerCase();
      if(!target || target===s.username)return j({ok:false,error:"مستخدم غير صالح"},400);
      if(!await this.user(target))return j({ok:false,error:"المستخدم غير موجود"},404);
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
      return j({ok:true,following:true});
    }

    if(url.pathname==="/api/profile-public" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const username=String(url.searchParams.get("username")||s.username).trim().toLowerCase();
      const u=await this.publicUser(username);
      if(!u)return j({ok:false,error:"المستخدم غير موجود"},404);

      return j({
        ok:true,
        user:u,
        friendCount:(await this.friends(username)).length,
        friendState:await this.friendState(s.username,username)
      });
    }

    if(url.pathname==="/api/stories" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const text=String(body.text||"").trim().slice(0,500);
      const media=String(body.media||"").slice(0,700000);
      if(!text && !media)return j({ok:false,error:"أضف نصًا أو صورة للحالة"},400);
      const id=crypto.randomUUID();
      const story={id,author:s.username,text,media,createdAt:Date.now(),expiresAt:Date.now()+86400000};
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
        stories.push({...st,authorInfo:await this.publicUser(st.author)});
      }
      return j({ok:true,stories});
    }

    if(url.pathname==="/api/conversation" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const other=String(url.searchParams.get("with")||"").trim().toLowerCase();
      const key=[s.username,other].sort().join(":");
      return j({ok:true,messages:(await this.ctx.storage.get(`messages:${key}`)||[]).slice(-200)});
    }

    
    if(url.pathname==="/api/post-react" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const post=await this.ctx.storage.get(`post:${body.postId}`);
      if(!post)return j({ok:false,error:"المنشور غير موجود"},404);

      const allowed=["like","love","haha","wow","sad"];
      const reaction=String(body.reaction||"like");
      if(!allowed.includes(reaction))return j({ok:false,error:"تفاعل غير صالح"},400);

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
      }

      const counts={};
      for(const key of allowed)counts[key]=post.reactions[key].length;
      return j({ok:true,counts});
    }

    if(url.pathname==="/api/post-save" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const post=await this.ctx.storage.get(`post:${body.postId}`);
      if(!post)return j({ok:false,error:"المنشور غير موجود"},404);

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
        if(!p)continue;
        posts.push({...p,authorInfo:await this.publicUser(p.author),savedByMe:true});
      }

      return j({ok:true,posts});
    }

    if(url.pathname==="/api/post-share" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const source=await this.ctx.storage.get(`post:${body.postId}`);
      if(!source)return j({ok:false,error:"المنشور غير موجود"},404);

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
      }

      return j({ok:true,post});
    }

    if(url.pathname==="/api/comment-reply" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const post=await this.ctx.storage.get(`post:${body.postId}`);
      if(!post)return j({ok:false,error:"المنشور غير موجود"},404);

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
      return j({ok:true});
    }

    if(url.pathname==="/api/explore" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const blocked=await this.ctx.storage.get(`blocked:${s.username}`)||[];
      const ids=await this.ctx.storage.get("postIds")||[];
      const posts=[];

      for(const id of ids){
        const p=await this.ctx.storage.get(`post:${id}`);
        if(!p || blocked.includes(p.author))continue;

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

      let blocked=await this.ctx.storage.get(`blocked:${s.username}`)||[];
      const exists=blocked.includes(target);

      if(exists)blocked=blocked.filter(x=>x!==target);
      else blocked.push(target);

      await this.ctx.storage.put(`blocked:${s.username}`,blocked);
      return j({ok:true,blocked:!exists});
    }

    if(url.pathname==="/api/report" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const report={
        id:crypto.randomUUID(),
        reporter:s.username,
        targetType:String(body.targetType||"post"),
        targetId:String(body.targetId||""),
        reason:String(body.reason||"").slice(0,500),
        createdAt:Date.now()
      };

      const reports=await this.ctx.storage.get("reports")||[];
      reports.push(report);
      await this.ctx.storage.put("reports",reports.slice(-1000));
      return j({ok:true});
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
        bio:u.bio||"",
        cover:u.cover||"",
        accountPrivate:!!u.accountPrivate,
        avatar:u.avatar||""
      }});
    }

    if(url.pathname==="/api/friend-request" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const target=String(body.username||"").trim().toLowerCase();
      if(!target || target===s.username)return j({ok:false,error:"مستخدم غير صالح"},400);
      if(!await this.user(target))return j({ok:false,error:"المستخدم غير موجود"},404);

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
      return j({ok:true,state:"outgoing"});
    }

    if(url.pathname==="/api/friend-action" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);
      const other=String(body.username||"").trim().toLowerCase();
      const action=String(body.action||"");

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
        const ns=await this.ctx.storage.get(`notifications:${other}`)||[];
        ns.push({id:crypto.randomUUID(),type:"friend-accepted",from:s.username,createdAt:Date.now(),read:false});
        await this.ctx.storage.put(`notifications:${other}`,ns.slice(-300));
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
      const result=[];
      for(const name of await this.friends(username)){
        const u=await this.publicUser(name);
        if(u)result.push(u);
      }
      return j({ok:true,friends:result});
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
      const payload={type:"chat",id:crypto.randomUUID(),from,to,text:String(msg.text||""),media:String(msg.media||"").slice(0,700000),mediaType:String(msg.mediaType||"text"),ts:Date.now(),read:false};
      const key=[from,to].sort().join(":");
      const list=await this.ctx.storage.get(`messages:${key}`)||[];list.push(payload);
      await this.ctx.storage.put(`messages:${key}`,list.slice(-300));
      const target=this.findUser(to);if(target)this.send(target,payload);else this.send(ws,{type:"user-offline",user:to});
      this.send(ws,payload);return;
    }

    if(msg.type==="typing"){
      const target=this.findUser(String(msg.to||"").trim().toLowerCase());
      if(target)this.send(target,{type:"typing",from,active:!!msg.active});return;
    }

    if(msg.type==="read"){
      const other=String(msg.with||"").trim().toLowerCase();const key=[from,other].sort().join(":");
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
        version:"V12-Reference-Match"
      });
    }

    if(url.pathname==="/ws" || url.pathname.startsWith("/api/")){
      const id=env.SIGNALING.idFromName("global-room");
      return env.SIGNALING.get(id).fetch(request);
    }

    return env.ASSETS.fetch(request);
  }
};
