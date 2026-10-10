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
    const session=token ? (await this.ctx.storage.get(`session:${token}`)||null) : null;
    if(!session)return null;
    const u=await this.user(session.username);
    if(!u || u.banned)return null;
    return session;
  }

  ownerUsername(){
    return String(this.env.SUPER_ADMIN_USERNAME||"").trim().toLowerCase();
  }

  async roleFor(username){
    const name=String(username||"").trim().toLowerCase();
    if(name && name===this.ownerUsername())return "super_admin";
    const moderators=await this.ctx.storage.get("moderators")||[];
    return moderators.includes(name)?"moderator":"user";
  }

  async requireAdmin(token){
    const session=await this.session(String(token||""));
    if(!session)return {error:j({ok:false,error:"الجلسة منتهية"},401)};
    const role=await this.roleFor(session.username);
    if(role!=="super_admin" && role!=="moderator")return {error:j({ok:false,error:"غير مصرح لك بالدخول إلى لوحة الإدارة"},403)};
    return {session,role};
  }

  normalizeRegistration(body={}){
    return {
      fullName:String(body.fullName||"").trim(),
      username:String(body.username||"").trim().toLowerCase(),
      age:Number(body.age||0),
      phone:String(body.phone||"").trim().replace(/\s+/g,""),
      email:String(body.email||"").trim().toLowerCase(),
      gender:String(body.gender||"").trim(),
      country:String(body.country||"").trim(),
      password:String(body.password||""),
      confirmPassword:String(body.confirmPassword||"")
    };
  }

  async validateRegistration(reg){
    if(reg.fullName.length<3)return "اكتب الاسم الكامل";
    if(reg.username.length<3)return "اسم المستخدم 3 أحرف على الأقل";
    if(!Number.isFinite(reg.age) || reg.age<13 || reg.age>120)return "اكتب عمرًا صحيحًا من 13 إلى 120";
    if(!reg.email)return "أدخل البريد الإلكتروني لاستلام كود التأكيد";
    if(reg.phone && !/^\+?[0-9]{8,15}$/.test(reg.phone))return "رقم الهاتف غير صحيح";
    if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(reg.email))return "البريد الإلكتروني غير صحيح";
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

  async sendVerificationEmail(email,code){
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
        subject:"كود تأكيد حساب تواصل العطا",
        html:`<!doctype html>
          <html dir="rtl" lang="ar">
            <body style="margin:0;background:#0a0d10;font-family:Arial,sans-serif;color:#fff">
              <div style="max-width:520px;margin:30px auto;padding:28px;background:#11161a;border:1px solid #d7a936;border-radius:18px;text-align:center">
                <h2 style="color:#f1c75b;margin:0 0 14px">تواصل العطا</h2>
                <p style="line-height:1.8;color:#ddd">استخدم الكود التالي لتأكيد إنشاء حسابك:</p>
                <div style="font-size:34px;letter-spacing:9px;font-weight:800;color:#f7d778;padding:18px 8px">${code}</div>
                <p style="color:#aaa;font-size:13px">الكود صالح لمدة 10 دقائق. إذا لم تطلب إنشاء الحساب فتجاهل الرسالة.</p>
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

  async api(request){
    const url=new URL(request.url);

    if(url.pathname==="/api/diagnostics/env" && request.method==="GET"){
      const resend=String(this.env.RESEND_API_KEY||"").trim();
      const turnId=String(this.env.TURN_KEY_ID||"").trim();
      const turnToken=String(this.env.TURN_KEY_API_TOKEN||"").trim();

      return j({
        ok:true,
        diagnostics:{
          resendApiKeyConfigured:!!resend,
          resendApiKeyLength:resend.length,
          resendApiKeyLooksValid:resend.startsWith("re_"),
          turnKeyIdConfigured:!!turnId,
          turnKeyApiTokenConfigured:!!turnToken
        },
        note:"No secret values are exposed by this endpoint."
      });
    }
    // Google Client ID is public; never return any secret here.
    if(url.pathname==="/api/google-config" && request.method==="GET"){
      const clientId=String(this.env.GOOGLE_CLIENT_ID||"").trim();
      return j({ok:true,enabled:!!clientId,clientId});
    }
    let body={};
    if(request.method!=="GET"){
      try{body=await request.json()}catch{}
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
          phone:reg.phone,
          email:reg.email,
          gender:reg.gender,
          country:reg.country,
          passwordHash:await hashPassword(reg.password)
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
            provider:"Resend",
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
        phone:reg.phone,
        email:reg.email,
        emailVerified:true,
        emailVerifiedAt:Date.now(),
        gender:reg.gender,
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
            provider:"Resend",
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
          || claims.email_verified!=="true" || !claims.sub || Number(claims.exp)*1000<=Date.now()){
        return j({ok:false,error:"تعذر التحقق من البريد باستخدام Google"},401);
      }
      const email=String(claims.email||"").toLowerCase().trim();
      if(!email || !email.includes("@"))return j({ok:false,error:"لم يرجع Google بريدًا صالحًا"},401);
      const idHash=(await hashPassword("google:"+claims.sub)).slice(0,22);
      const googleKey=`google-account:${idHash}`;
      let username=await this.ctx.storage.get(googleKey);
      if(username && !(await this.user(username)))username=null;
      if(!username){
        const all=await this.ctx.storage.get("usernames")||[];
        for(const name of all){
          const existing=await this.user(name);
          if(existing?.email===email){
            return j({ok:false,error:"هذا البريد مرتبط بحساب موجود. ادخل بكلمة المرور القديمة أولًا؛ لن نربط الحساب تلقائيًا حفاظًا على أمانه."},409);
          }
        }
        username="g_"+idHash;
        if(await this.user(username))return j({ok:false,error:"تعذر إنشاء الحساب. تواصل مع الإدارة."},409);
        const fullName=String(claims.name||claims.given_name||"مستخدم Google").trim().slice(0,60);
        await this.ctx.storage.put(`user:${username}`,{
          username,displayName:fullName,fullName,age:0,phone:"",email,
          emailVerified:true,emailVerifiedAt:Date.now(),googleIdHash:idHash,
          gender:"",country:"",passwordHash:null,avatar:"",createdAt:Date.now()
        });
        await this.ctx.storage.put("usernames",[...new Set([...all,username])]);
        await this.ctx.storage.put(googleKey,username);
      }
      const googleUser=await this.user(username);
      if(googleUser?.banned)return j({ok:false,error:"تم إيقاف هذا الحساب بواسطة الإدارة"},403);
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
      if(u.banned)return j({ok:false,error:"تم إيقاف هذا الحساب بواسطة الإدارة"},403);

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


    if(url.pathname==="/api/call-signal" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const to=String(body.to||"").trim().toLowerCase();
      const signal=body.signal && typeof body.signal==="object" ? body.signal : {};
      const type=String(signal.type||"");

      const allowed=new Set([
        "call-request",
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

      return j({ok:true,signals});
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
          users.push({...u,friendState:await this.friendState(s.username,u.username)});
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
        for(const key of ["like","love","haha","wow","sad","angry","dislike"]){
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

        const authorInfo=await this.publicUser(p.author);

        posts.push({
          ...p,
          authorInfo,
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


    if(url.pathname==="/api/message-send" && request.method==="POST"){
      const s=await this.session(String(body.token||""));
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const to=String(body.to||"").trim().toLowerCase();
      if(!to)return j({ok:false,error:"اختر مستخدمًا"},400);
      if(to===s.username)return j({ok:false,error:"لا يمكنك إرسال رسالة لنفسك"},400);
      if(!await this.user(to))return j({ok:false,error:"المستخدم غير موجود"},404);

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

    if(url.pathname==="/api/conversation" && request.method==="GET"){
      const s=await this.session(url.searchParams.get("token")||"");
      if(!s)return j({ok:false,error:"الجلسة منتهية"},401);

      const other=String(url.searchParams.get("with")||"").trim().toLowerCase();
      const key=[s.username,other].sort().join(":");
      const messages=await this.ctx.storage.get(`messages:${key}`)||[];

      let changed=false;
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

      const allowed=["like","love","haha","wow","sad","angry","dislike"];
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
      const q=String(url.searchParams.get("q")||"").trim().toLowerCase();
      if(!q)return j({ok:true,posts:[]});
      const ids=await this.ctx.storage.get("postIds")||[];
      const posts=[];

      for(const id of ids){
        const p=await this.ctx.storage.get(`post:${id}`);
        if(!p || blocked.includes(p.author))continue;
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
        avatar:u.avatar||"",
        role:await this.roleFor(u.username)
      }});
    }

    if(url.pathname==="/api/admin/summary" && request.method==="GET"){
      const auth=await this.requireAdmin(url.searchParams.get("token")||"");
      if(auth.error)return auth.error;
      const usernames=await this.ctx.storage.get("usernames")||[];
      const postIds=await this.ctx.storage.get("postIds")||[];
      const reports=await this.ctx.storage.get("reports")||[];
      let banned=0;
      for(const name of usernames){ const u=await this.user(name); if(u?.banned)banned++; }
      return j({ok:true,role:auth.role,owner:this.ownerUsername(),counts:{users:usernames.length,posts:postIds.length,reports:reports.filter(r=>!r.resolved).length,banned}});
    }

    if(url.pathname==="/api/admin/users" && request.method==="GET"){
      const auth=await this.requireAdmin(url.searchParams.get("token")||"");
      if(auth.error)return auth.error;
      const usernames=await this.ctx.storage.get("usernames")||[];
      const list=[];
      for(const name of usernames){
        const u=await this.user(name); if(!u)continue;
        list.push({username:u.username,displayName:u.displayName||u.fullName||u.username,avatar:u.avatar||"",banned:!!u.banned,role:await this.roleFor(u.username),createdAt:u.createdAt||0});
      }
      list.sort((a,b)=>b.createdAt-a.createdAt);
      return j({ok:true,users:list.slice(0,500)});
    }

    if(url.pathname==="/api/admin/reports" && request.method==="GET"){
      const auth=await this.requireAdmin(url.searchParams.get("token")||"");
      if(auth.error)return auth.error;
      const reports=await this.ctx.storage.get("reports")||[];
      return j({ok:true,reports:[...reports].reverse().slice(0,500)});
    }

    if(url.pathname==="/api/admin/posts" && request.method==="GET"){
      const auth=await this.requireAdmin(url.searchParams.get("token")||"");
      if(auth.error)return auth.error;
      const ids=await this.ctx.storage.get("postIds")||[];
      const posts=[];
      for(const id of [...ids].reverse().slice(0,200)){ const p=await this.ctx.storage.get(`post:${id}`); if(p)posts.push({id:p.id,author:p.author,text:String(p.text||"").slice(0,180),createdAt:p.createdAt||0,hasImage:!!p.image}); }
      return j({ok:true,posts});
    }

    if(url.pathname==="/api/admin/action" && request.method==="POST"){
      const auth=await this.requireAdmin(body.token||"");
      if(auth.error)return auth.error;
      const action=String(body.action||"");
      const target=String(body.username||"").trim().toLowerCase();
      const owner=this.ownerUsername();

      if(["ban","unban","make_moderator","remove_moderator"].includes(action)){
        if(!target || !await this.user(target))return j({ok:false,error:"المستخدم غير موجود"},404);
        if(target===owner)return j({ok:false,error:"لا يمكن تعديل صلاحيات أو إيقاف حساب المدير الرئيسي"},403);
        if(["make_moderator","remove_moderator"].includes(action) && auth.role!=="super_admin")return j({ok:false,error:"هذه العملية للمدير الرئيسي فقط"},403);
        const u=await this.user(target);
        if(action==="ban" || action==="unban"){ u.banned=action==="ban"; u.bannedAt=u.banned?Date.now():0; u.bannedBy=u.banned?auth.session.username:""; await this.ctx.storage.put(`user:${target}`,u); }
        if(action==="make_moderator" || action==="remove_moderator"){ let mods=await this.ctx.storage.get("moderators")||[]; mods=action==="make_moderator"?[...new Set([...mods,target])]:mods.filter(x=>x!==target); await this.ctx.storage.put("moderators",mods); }
        return j({ok:true});
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
      await this.deliverPendingMessages(s.username,server);
      await this.broadcastUsers();
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
      if(!to)return;

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
        text:String(msg.text||""),
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
        version:"V13.37-Profile-Cover-Posts"
      });
    }

    if(url.pathname==="/privacy" || url.pathname==="/privacy/"){
      return env.ASSETS.fetch(new Request(new URL("/privacy.html", url.origin), request));
    }

    if(url.pathname==="/terms" || url.pathname==="/terms/"){
      return env.ASSETS.fetch(new Request(new URL("/terms.html", url.origin), request));
    }

    if(url.pathname==="/ws" || url.pathname.startsWith("/api/")){
      const id=env.SIGNALING.idFromName("global-room");
      return env.SIGNALING.get(id).fetch(request);
    }

    return env.ASSETS.fetch(request);
  }
};
