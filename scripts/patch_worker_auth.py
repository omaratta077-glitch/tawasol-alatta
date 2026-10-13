#!/usr/bin/env python3
"""Idempotent incremental auth API upgrade. Run on checked-out live worker.js.
Does not replace other Cloudflare features or touch Wrangler configuration.
"""
from pathlib import Path
import argparse
p=argparse.ArgumentParser()
p.add_argument('--path',default='cloudflare-site/src/worker.js')
a=p.parse_args()
path=Path(a.path)
s=path.read_text(encoding='utf-8')
assert 'export class SignalingRoom' in s and '/api/google-login' in s and '/api/register-request' in s, 'Unexpected Worker source; refusing to modify.'
if 'ALATTA_NATIVE_AUTH_V103' in s:
    print('Auth backend patch already installed; no changes.')
    raise SystemExit(0)

def once(old,new,description):
    global s
    if s.count(old)!=1:
        raise ValueError(f'Cannot safely locate {description} (found {s.count(old)}); no file modified')
    s=s.replace(old,new,1)

# User-facing fields. Account signup from the website stays compatible.
once('      country:String(body.country||"").trim(),\n      password:String(body.password||""),','      country:String(body.country||"").trim(),\n      city:String(body.city||"").trim().slice(0,80),\n      password:String(body.password||""),','registration normalization')
once('          country:reg.country,\n          passwordHash:await hashPassword(reg.password)','          country:reg.country,\n          city:reg.city,\n          passwordHash:await hashPassword(reg.password)','pending registration')
once('        country:reg.country,\n        passwordHash:reg.passwordHash,','        country:reg.country,\n        city:reg.city||"",\n        passwordHash:reg.passwordHash,','registered user')

# Hide sessions created before password reset.
# Session versions are monotonic and safe even if password changes occur within the same millisecond.
# Existing sessions without authVersion are treated as version 0 until the first password change.
once('    await this.ctx.storage.put(`session:${token}`,{username,createdAt:Date.now()});',
     '    const sessionUser=await this.user(username);\n    await this.ctx.storage.put(`session:${token}`,{username,createdAt:Date.now(),authVersion:Number(sessionUser?.authVersion||0)});',
     'attach auth version to newly created sessions')
once('    if(!u || u.banned)return null;\n    return session;',
     '    if(!u || u.banned)return null;\n    if(Number(session.authVersion||0)!==Number(u.authVersion||0))return null;\n    return session;',
     'invalidate sessions by auth version')

# Reuse the already working transactional email provider with two different purposes.
once('  async sendVerificationEmail(email,code){','  async sendVerificationEmail(email,code,purpose="registration"){','mail function signature')
once('        subject:"كود تأكيد حساب تواصل العطا",','        subject:purpose==="passwordReset" ? "كود استعادة كلمة سر تواصل العطا" : "كود تأكيد حساب تواصل العطا",','mail subject')
once('استخدم الكود التالي لتأكيد إنشاء حسابك:','${purpose==="passwordReset" ? "استخدم الكود التالي لاستعادة كلمة السر:" : "استخدم الكود التالي لتأكيد إنشاء حسابك:"}','mail contents')
once('إذا لم تطلب إنشاء الحساب فتجاهل الرسالة.','إذا لم تطلب هذه العملية فتجاهل الرسالة.','mail contents footer')

reset_routes = '''    // ALATTA_NATIVE_AUTH_V103: one-time, email-verified reset tokens (not logged in URLs).
    if(url.pathname==="/api/password-reset-request" && request.method==="POST"){
      const email=String(body.email||"").trim().toLowerCase().slice(0,255);
      if(!/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(email))return j({ok:false,error:"اكتب بريدًا إلكترونيًا صحيحًا"},400);
      if(!String(this.env.RESEND_API_KEY||"").trim())return j({ok:false,error:"خدمة البريد غير مفعّلة على السيرفر"},503);
      const now=Date.now();
      const addressHash=await hashPassword(email);
      const rateKey=`reset-rate:${addressHash}`;
      const ip=String(request.headers.get("CF-Connecting-IP")||"unknown").slice(0,64);
      const ipKey=`reset-ip:${(await hashPassword(ip)).slice(0,20)}`;
      const ipInfo=await this.ctx.storage.get(ipKey)||{count:0};
      // Always use the same response to prevent account enumeration.
      const resetId=crypto.randomUUID();
      const generic=j({ok:true,requestId:resetId,expiresIn:600,
          message:"إذا كان البريد مرتبطًا بحساب بكلمة مرور، سيصلك كود استعادة خلال دقائق"});
      if((await this.ctx.storage.get(rateKey)) || Number(ipInfo.count||0)>=8)return generic;
      await this.ctx.storage.put(rateKey,now,{expirationTtl:90});
      await this.ctx.storage.put(ipKey,{count:Number(ipInfo.count||0)+1},{expirationTtl:3600});
      const names=await this.ctx.storage.get("usernames")||[];
      let account=null;
      for(const name of names){
        const candidate=await this.user(name);
        if(candidate?.email===email && candidate.passwordHash){account=candidate;break;}
      }
      if(!account)return generic;
      const code=String(crypto.getRandomValues(new Uint32Array(1))[0]%1000000).padStart(6,"0");
      try{
        await this.sendVerificationEmail(email,code,"passwordReset");
      }catch(err){
        console.error("Password reset email send failed",String(err?.message||err));
        return generic;
      }
      await this.ctx.storage.put(`pending-password-reset:${resetId}`,{
        username:account.username,email,
        codeHash:await hashPassword(`pwreset:${resetId}:${code}`),
        expiresAt:now+600000, attempts:0
      },{expirationTtl:900});
      return generic;
    }

    if(url.pathname==="/api/password-reset-confirm" && request.method==="POST"){
      const requestId=String(body.requestId||"").trim().slice(0,120);
      const code=String(body.code||"").trim();
      const password=String(body.newPassword||"");
      const confirm=String(body.confirmPassword||"");
      if(!/^[0-9]{6}$/.test(code) || !requestId)return j({ok:false,error:"أدخل كود التأكيد المكون من 6 أرقام"},400);
      if(password.length<8 || password.length>128)return j({ok:false,error:"كلمة المرور يجب أن تكون بين 8 و128 حرفًا"},400);
      if(password!==confirm)return j({ok:false,error:"تأكيد كلمة المرور غير مطابق"},400);
      const key=`pending-password-reset:${requestId}`;
      const pending=await this.ctx.storage.get(key);
      if(!pending || Date.now()>Number(pending.expiresAt||0))return j({ok:false,error:"الكود غير صحيح أو انتهت صلاحيته"},410);
      pending.attempts=Number(pending.attempts||0)+1;
      if(pending.attempts>5){await this.ctx.storage.delete(key);return j({ok:false,error:"انتهت محاولات التأكيد. اطلب كودًا جديدًا"},429);}
      if(await hashPassword(`pwreset:${requestId}:${code}`)!==pending.codeHash){
        await this.ctx.storage.put(key,pending,{expirationTtl:900});
        return j({ok:false,error:"الكود غير صحيح أو انتهت صلاحيته"},400);
      }
      const u=await this.user(pending.username);
      if(!u || u.email!==pending.email || !u.passwordHash)return j({ok:false,error:"تعذّر تحديث كلمة المرور"},400);
      u.passwordHash=await hashPassword(password); // Uses same legacy password format as /api/login.
      u.passwordChangedAt=Date.now(); // Audit-only timestamp.
      u.authVersion=Number(u.authVersion||0)+1; // Invalidate pre-change sessions.
      await this.ctx.storage.put(`user:${u.username}`,u);
      await this.ctx.storage.delete(key); // Code is single-use.
      return j({ok:true,message:"تم تغيير كلمة المرور. يمكنك تسجيل الدخول الآن"});
    }

    // Authenticated password change. Google-only accounts use Google to authenticate.
    if(url.pathname==="/api/change-password" && request.method==="POST"){
      const session=await this.session(String(body.token||""));
      if(!session)return j({ok:false,error:"سجل الدخول أولًا"},401);
      const oldPassword=String(body.oldPassword||"");
      const newPassword=String(body.newPassword||"");
      const confirm=String(body.confirmPassword||"");
      const user=await this.user(session.username);
      if(!user?.passwordHash)return j({ok:false,error:"هذا الحساب يدخل بواسطة Google؛ لا توجد كلمة مرور محلية لتغييرها"},400);
      const attemptKey=`change-password-rate:${session.username}`;
      const info=await this.ctx.storage.get(attemptKey)||{count:0};
      if(Number(info.count||0)>=6)return j({ok:false,error:"محاولات كثيرة. حاول بعد ساعة"},429);
      await this.ctx.storage.put(attemptKey,{count:Number(info.count||0)+1},{expirationTtl:3600});
      if(user.passwordHash!==await hashPassword(oldPassword))return j({ok:false,error:"كلمة المرور الحالية غير صحيحة"},401);
      if(newPassword.length<8 || newPassword.length>128)return j({ok:false,error:"اختر كلمة مرور من 8 إلى 128 حرفًا"},400);
      if(newPassword!==confirm)return j({ok:false,error:"تأكيد كلمة المرور غير مطابق"},400);
      user.passwordHash=await hashPassword(newPassword);
      user.passwordChangedAt=Date.now();
      user.authVersion=Number(user.authVersion||0)+1;
      await this.ctx.storage.put(`user:${user.username}`,user);
      await this.ctx.storage.delete(attemptKey);
      return j({ok:true,message:"تم تغيير كلمة المرور، سجّل الدخول من جديد"});
    }

'''
once('    if(url.pathname==="/api/logout" && request.method==="POST"){',reset_routes+'    if(url.pathname==="/api/logout" && request.method==="POST"){','reset route insertion')

once('      if(!username){\n        const all=await this.ctx.storage.get("usernames")||[];','''      if(!username){
        if(body.signupFlow==="android-login")return j({ok:false,error:"لا يوجد حساب Google سابق. اختر «إنشاء حساب عن طريق Google» أولًا."},404);
        const all=await this.ctx.storage.get("usernames")||[];''','Google existing-account guard')
once('''        const fullName=String(claims.name||claims.given_name||"مستخدم Google").trim().slice(0,60);
        await this.ctx.storage.put(`user:${username}`,{
          username,displayName:fullName,fullName,age:0,phone:"",email,
          emailVerified:true,emailVerifiedAt:Date.now(),googleIdHash:idHash,
          gender:"",country:"",passwordHash:null,avatar:"",createdAt:Date.now()
        });''','''        // Android signup verifies the Google ID token first and lets the user
        // edit the suggested name, provide age and optionally city.
        const androidSignup=body.signupFlow==="android-signup";
        const suggested=String(claims.name||claims.given_name||"مستخدم Google").trim();
        const fullName=(androidSignup?String(body.fullName||""):suggested).trim().slice(0,60);
        const age=androidSignup?Number(body.age):0;
        const city=androidSignup?String(body.city||"").trim().slice(0,80):"";
        if(androidSignup && (fullName.length<2 || !Number.isInteger(age) || age<13 || age>120))
          return j({ok:false,error:"اكتب الاسم والعمر الصحيح (13 إلى 120) قبل إنشاء حساب Google"},400);
        await this.ctx.storage.put(`user:${username}`,{
          username,displayName:fullName,fullName,age,city,phone:"",email,
          emailVerified:true,emailVerifiedAt:Date.now(),googleIdHash:idHash,
          gender:"",country:"",passwordHash:null,avatar:"",createdAt:Date.now()
        });''','Google profile creation')
# Ensure web browser and old Android login still work when flow absent.
path.write_text(s,encoding='utf-8')
print(f'Auth patch installed in {path}; total {len(s)} chars.')
