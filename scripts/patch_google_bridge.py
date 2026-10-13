#!/usr/bin/env python3
"""Safely append a nonce+SHA256-bound Google browser handoff to the CURRENT Cloudflare Worker.
Does not change /api/google-login or website sign-in. Idempotent; aborts on unknown worker shape.
"""
from pathlib import Path
import argparse
arg = argparse.ArgumentParser()
arg.add_argument('--path', default='cloudflare-site/src/worker.js')
opt=arg.parse_args()
p=Path(opt.path)
s=p.read_text(encoding='utf8')
if 'ALATTA_ANDROID_GOOGLE_BRIDGE_V104' in s:
    print('Bridge already installed')
    raise SystemExit(0)
anchor='    if(url.pathname==="/api/turn-credentials" && request.method==="GET"){' 
if s.count(anchor)!=1 or '    let body={};' not in s or '/api/google-login' not in s:
    raise ValueError('Unknown Worker layout; aborting without modifying file')
routes=r'''    // ALATTA_ANDROID_GOOGLE_BRIDGE_V104
    // Google login is completed in the first-party website on the real system browser.
    // An ephemeral code is bound to the app's SHA-256 verifier. It is NOT an ID token.
    if(url.pathname==="/api/android-google-bridge/start" && request.method==="POST"){
      if(request.headers.get("Origin")!==url.origin)
        return j({ok:false,error:"غير مسموح بطلب مصادقة من مصدر آخر"},403);
      if(!String(this.env.GOOGLE_CLIENT_ID||"").trim())
        return j({ok:false,error:"تسجيل Google غير مفعّل على الموقع"},503);
      const challenge=String(body.challenge||"");
      const credential=String(body.credential||"");
      if(!/^[A-Za-z0-9_-]{43}$/.test(challenge)||credential.length<100||credential.length>10000)
        return j({ok:false,error:"طلب تأكيد Google غير صالح"},400);
      const ip=String(request.headers.get("CF-Connecting-IP")||"unknown");
      const rateKey=`google-bridge-rate:${(await hashPassword(ip)).slice(0,32)}`;
      const rate=await this.ctx.storage.get(rateKey)||{count:0};
      if(rate.count>=12)return j({ok:false,error:"محاولات كثيرة. حاول بعد دقيقة"},429);
      await this.ctx.storage.put(rateKey,{count:rate.count+1},{expirationTtl:60});
      const code=crypto.randomUUID().replaceAll("-","")+crypto.randomUUID().replaceAll("-","");
      const key=`google-bridge:${await hashPassword(code)}`;
      await this.ctx.storage.put(key,{credential,challenge,expiresAt:Date.now()+300000},{expirationTtl:320});
      return j({ok:true,code,expiresIn:300});
    }
    if(url.pathname==="/api/android-google-bridge/exchange" && request.method==="POST"){
      const code=String(body.code||"");
      const verifier=String(body.verifier||"");
      if(!/^[a-f0-9]{64}$/.test(code)||!/^[A-Za-z0-9_-]{43}$/.test(verifier))
        return j({ok:false,error:"رابط المصادقة غير صالح"},400);
      const key=`google-bridge:${await hashPassword(code)}`;
      const saved=await this.ctx.storage.get(key);
      if(!saved||Date.now()>saved.expiresAt)return j({ok:false,error:"انتهت جلسة Google، حاول مرة أخرى"},410);
      const digest=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(verifier));
      const challenge=btoa(String.fromCharCode(...new Uint8Array(digest))).replaceAll("+","-").replaceAll("/","_").replace(/=+$/g,"");
      if(challenge!==saved.challenge)return j({ok:false,error:"تعذّر التحقق من ربط الجهاز"},403);
      await this.ctx.storage.delete(key); // one time, prevents replay
      // Signature, audience, issuer, email verification and expiration are checked
      // by the UNMODIFIED /api/google-login endpoint before any session is created.
      return j({ok:true,credential:saved.credential});
    }

'''
s=s.replace(anchor,routes+anchor,1)
p.write_text(s,encoding='utf8')
print('Installed browser Google bridge safely.')
