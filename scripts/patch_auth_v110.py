#!/usr/bin/env python3
"""Safely patch CURRENT AL-ATTA Cloudflare auth (v1.0.10).

Updates are computed in memory and only written when every marker validates.
Supports the existing Android Google signup block (introduced before v1.0.10).
Preserves Google-linked existing users and never edits user data.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
WORKER = ROOT / "cloudflare-site/src/worker.js"
WEB = ROOT / "cloudflare-site/public/app.js"
INDEX = ROOT / "cloudflare-site/public/index.html"


def once(source, old, new, label):
    if new in source:
        return source
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one source marker, found {count}; no files modified")
    return source.replace(old, new, 1)


def worker_patch(s):
    s = once(
        s,
        'return j({ok:true,enabled:!!clientId,clientId});',
        'return j({ok:true,enabled:!!clientId,clientId,nativeUsernameSignup:true});',
        'Google config capability',
    )
    start = s.find('if(url.pathname==="/api/google-login"')
    end = s.find('if(url.pathname==="/api/login"', start)
    if start < 0 or end < 0:
        raise RuntimeError('Google/login handlers not found; no files modified')
    block = s[start:end]
    new_username = '''// AL-ATTA v1.0.10: New Google users select their own username.
        const chosenUsername=String(body.username||"").trim().toLowerCase();
        if(!/^[a-z0-9_]{3,24}$/.test(chosenUsername)){
          return j({ok:false,error:"USERNAME_REQUIRED: اختر اسم مستخدم من 3 إلى 24 حرفًا إنجليزيًا أو رقمًا أو _",code:"USERNAME_REQUIRED"},409);
        }
        const chosenFullName=String(body.fullName||"").trim();
        const chosenAge=Number(body.age);
        if(chosenFullName.length<2 || chosenFullName.length>60 || !Number.isInteger(chosenAge) || chosenAge<13 || chosenAge>120){
          return j({ok:false,error:"اكتب الاسم الظاهر والعمر الصحيح لإنشاء حساب Google",code:"PROFILE_REQUIRED"},400);
        }
        username=chosenUsername;'''
    block = once(block, 'username="g_"+idHash;', new_username, 'Google username generation')

    # The old patch expected a single-line fullName declaration; current server
    # already has Android signup fields. Handle that exact existing block instead.
    old_android = '''// Android signup verifies the Google ID token first and lets the user
        // edit the suggested name, provide age and optionally city.
        const androidSignup=body.signupFlow==="android-signup";
        const suggested=String(claims.name||claims.given_name||"مستخدم Google").trim();
        const fullName=(androidSignup?String(body.fullName||""):suggested).trim().slice(0,60);
        const age=androidSignup?Number(body.age):0;
        const city=androidSignup?String(body.city||"").trim().slice(0,80):"";
        if(androidSignup && (fullName.length<2 || !Number.isInteger(age) || age<13 || age>120))
          return j({ok:false,error:"اكتب الاسم والعمر الصحيح (13 إلى 120) قبل إنشاء حساب Google"},400);'''
    new_android = '''// Full name and age are required when a new Google account is created.
        const fullName=chosenFullName.slice(0,60);
        const age=chosenAge;
        const city=String(body.city||"").trim().slice(0,80);'''
    if new_android not in block:
        if old_android in block:
            block = once(block, old_android, new_android, 'Google Android signup profile fields')
        else:
            # An older server without the Android-specific signup path.
            old_legacy = '''const fullName=String(claims.name||claims.given_name||"مستخدم Google").trim().slice(0,60);'''
            if old_legacy not in block:
                raise RuntimeError('Google signup profile markers changed; no files modified')
            block = once(block, old_legacy, 'const fullName=chosenFullName.slice(0,60);\n        const age=chosenAge;\n        const city=String(body.city||"").trim().slice(0,80);', 'Google legacy fullName')
            block = once(block, 'username,displayName:fullName,fullName,age:0,phone:"",email,', 'username,displayName:fullName,fullName,age,city,phone:"",email,', 'Google legacy age/city')
    s = s[:start] + block + s[end:]

    login_start = s.find('if(url.pathname==="/api/login"')
    login_end = s.find('if(url.pathname==="/api/', login_start + 8)
    if login_start < 0 or login_end < 0:
        raise RuntimeError('Login block not found; no files modified')
    login = s[login_start:login_end]
    login = once(login, 'const u=await this.user(username);', '''let u=await this.user(username);
      if(!u && (username.includes("@") || /^\\+?[0-9]{8,15}$/.test(username))){
        const names=await this.ctx.storage.get("usernames")||[];
        for(const name of names){
          const candidate=await this.user(name);
          if(candidate && (String(candidate.email||"").toLowerCase()===username || String(candidate.phone||"")===username)){
            u=candidate;break;
          }
        }
      }''', 'Phone/email login lookup')
    login = once(login, 'const token=await this.createSession(username);', 'const token=await this.createSession(u.username);', 'Canonical session user')
    login = once(login, 'return j({ok:true,token,user:await this.publicUser(username)});', 'return j({ok:true,token,user:await this.publicUser(u.username)});', 'Canonical public profile')
    return s[:login_start] + login + s[login_end:]


GOOGLE_MODAL = r'''// AL-ATTA v1.0.10: manually choose profile for first-time Google accounts.
function requestGoogleProfile(){
  return new Promise(resolve=>{
    const overlay=document.createElement("div");
    overlay.style.cssText="position:fixed;inset:0;z-index:999999;background:rgba(0,0,0,.85);display:grid;place-items:center;padding:18px;direction:rtl";
    overlay.innerHTML=`<form style="width:min(94vw,420px);background:#111619;border:1px solid #e6b94e;border-radius:22px;padding:24px;display:grid;gap:12px;box-shadow:0 12px 50px #000;color:#fff" autocomplete="on">
      <h2 style="margin:0;color:#e6b94e;font-size:21px">إنشاء حساب تواصل العطا</h2>
      <p style="margin:0;color:#b8bec1;font-size:13px">اختَر اسمك بنفسك. اسم المستخدم لا يظهر تحت اسمك في الملف الشخصي.</p>
      <input name="fullName" required minlength="2" maxlength="60" placeholder="اسمك الذي يظهر للآخرين" style="padding:13px;border-radius:11px;background:#080b0d;color:#fff;border:1px solid #514125">
      <input name="username" required minlength="3" maxlength="24" pattern="[a-zA-Z0-9_]{3,24}" placeholder="اسم مستخدم فريد (إنجليزي/أرقام)" style="padding:13px;border-radius:11px;background:#080b0d;color:#fff;border:1px solid #514125" autocapitalize="off">
      <input name="age" type="number" required min="13" max="120" placeholder="العمر" style="padding:13px;border-radius:11px;background:#080b0d;color:#fff;border:1px solid #514125">
      <input name="city" maxlength="80" placeholder="المدينة (اختياري)" style="padding:13px;border-radius:11px;background:#080b0d;color:#fff;border:1px solid #514125">
      <button type="submit" style="border:0;background:#e6b94e;color:#070707;padding:13px;border-radius:13px;font-weight:800">تأكيد وإنشاء الحساب</button>
      <button type="button" data-cancel style="background:transparent;color:#d9c89a;border:1px solid #6b5429;padding:11px;border-radius:13px">إلغاء</button>
    </form>`;
    const form=overlay.querySelector("form");
    const finish=value=>{overlay.remove();resolve(value)};
    overlay.querySelector("[data-cancel]").onclick=()=>finish(null);
    form.onsubmit=e=>{
      e.preventDefault();
      finish({fullName:form.elements.fullName.value.trim(),username:form.elements.username.value.trim().toLowerCase(),age:Number(form.elements.age.value),city:form.elements.city.value.trim()});
    };
    document.body.appendChild(overlay);
    form.elements.fullName.focus();
  });
}
'''


def web_patch(s):
    marker='let googleWidgetStarted=false;'
    if 'function requestGoogleProfile()' not in s:
        s=once(s, marker, GOOGLE_MODAL+'\n'+marker, 'Web Google registration modal')
    old='''          const result=await api("/api/google-login",{
            method:"POST",body:JSON.stringify({credential})
          });
          await startAuthenticatedApp(result.token,result.user);'''
    new='''          let result;
          try{
            result=await api("/api/google-login",{
              method:"POST",body:JSON.stringify({credential})
            });
          }catch(firstError){
            if(!String(firstError.message||"").includes("USERNAME_REQUIRED"))throw firstError;
            const profile=await requestGoogleProfile();
            if(!profile){hint.textContent="لم يتم إنشاء الحساب";return;}
            result=await api("/api/google-login",{
              method:"POST",body:JSON.stringify({credential,...profile})
            });
          }
          await startAuthenticatedApp(result.token,result.user);'''
    s=once(s, old, new, 'Google callback integration')
    s=once(s, '$('+'"profileUsername"'+').textContent="@"+p.username;', '$('+'"profileUsername"'+').textContent=""; $('+'"profileUsername"'+').hidden=true; $('+'"profileUsername"'+').style.display="none";', 'Hide handle below profile name')
    hint_hook='// AL-ATTA v1.0.10 login/register hint'
    if hint_hook not in s:
        hint='''let pendingVerificationId="";
// AL-ATTA v1.0.10 login/register hint
function refreshLoginIdentifierHint(){
  const field=document.getElementById("usernameInput");
  if(field)field.placeholder=authMode==="register" ? "اختر اسم مستخدم لحسابك" : "الهاتف أو البريد أو اسم المستخدم";
}
["loginTab","registerTab","attaRegisterShortcut"].forEach(id=>document.getElementById(id)?.addEventListener("click",refreshLoginIdentifierHint));'''
        s=once(s,'let pendingVerificationId="";',hint,'Context-aware login hint')
    return s


def index_patch(s):
    if 'id="usernameInput"' not in s:
        raise RuntimeError('Web login input not found; no files modified')
    return re.sub(r'(<input id="usernameInput" placeholder=")[^"]+("[^>]*>)',
                  r'\1الهاتف أو البريد أو اسم المستخدم\2',s,count=1)


def main():
    for f in (WORKER,WEB,INDEX):
        if not f.is_file():
            raise SystemExit(f'Cannot find {f}; upload script to repository scripts/ folder first')
    originals={f:f.read_text(encoding='utf-8') for f in (WORKER,WEB,INDEX)}
    try:
        patched={WORKER:worker_patch(originals[WORKER]),
                 WEB:web_patch(originals[WEB]),
                 INDEX:index_patch(originals[INDEX])}
    except Exception as exc:
        raise SystemExit(f'SAFE ABORT: {exc}') from exc
    for path, content in patched.items():
        if content!=originals[path]:
            path.write_text(content,encoding='utf-8')
            print(f'Updated: {path.relative_to(ROOT)}')
        else:
            print(f'Unchanged: {path.relative_to(ROOT)}')
    print('Patch complete. Deploy Cloudflare worker and site assets before testing new Google signup.')

if __name__=='__main__':main()
