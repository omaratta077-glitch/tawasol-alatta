#!/usr/bin/env python3
"""Apply focused, guarded AL-ATTA 1.0.10 auth changes to a checked-out site.

This intentionally patches the user's CURRENT worker/app.js rather than copying old
snapshots over the deployed site. If upstream changes conflict, fail before writing.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
WORKER = ROOT / 'cloudflare-site/src/worker.js'
WEB = ROOT / 'cloudflare-site/public/app.js'
INDEX = ROOT / 'cloudflare-site/public/index.html'


def once(source, old, new, label):
    if new in source:
        return source
    occurrences = source.count(old)
    if occurrences != 1:
        raise RuntimeError(f'{label}: expected one source marker, found {occurrences}; no files modified')
    return source.replace(old, new, 1)


def worker_patch(s):
    s = once(s,
        'return j({ok:true,enabled:!!clientId,clientId});',
        'return j({ok:true,enabled:!!clientId,clientId,nativeUsernameSignup:true});',
        'Google config capability')
    # Only Google *new account* branch; existing account mapping is left untouched.
    block_start = s.find('if(url.pathname==="/api/google-login"')
    block_end = s.find('if(url.pathname==="/api/login"', block_start)
    if block_start < 0 or block_end < 0:
        raise RuntimeError('Google/login handlers not found; no files modified')
    block = s[block_start:block_end]
    block = once(block,
        'username="g_"+idHash;',
        '''// New accounts must select their username; never manufacture g_<hash>.
        const chosenUsername=String(body.username||"").trim().toLowerCase();
        if(!/^[a-z0-9_]{3,24}$/.test(chosenUsername)){
          return j({ok:false,error:"USERNAME_REQUIRED: اختر اسم مستخدم من 3 إلى 24 حرفًا إنجليزيًا أو رقمًا أو _",code:"USERNAME_REQUIRED"},409);
        }
        const chosenFullName=String(body.fullName||"").trim();
        const chosenAge=Number(body.age);
        if(chosenFullName.length<2 || chosenFullName.length>60 || !Number.isInteger(chosenAge) || chosenAge<13 || chosenAge>120){
          return j({ok:false,error:"اكتب الاسم الظاهر والعمر الصحيح لإنشاء حساب Google",code:"PROFILE_REQUIRED"},400);
        }
        username=chosenUsername;''',
        'Google username generation')
    block = once(block,
        'const fullName=String(claims.name||claims.given_name||"مستخدم Google").trim().slice(0,60);',
        'const fullName=chosenFullName.slice(0,60);',
        'Google fullName')
    block = once(block,
        'username,displayName:fullName,fullName,age:0,phone:"",email,',
        'username,displayName:fullName,fullName,age:chosenAge,city:String(body.city||"").trim().slice(0,80),phone:"",email,',
        'Google age/city')
    s = s[:block_start]+block+s[block_end:]
    # Allow login by username, verified account email or recorded phone.
    login_start = s.find('if(url.pathname==="/api/login"')
    login_end = s.find('if(url.pathname==="/api/', login_start+8)
    if login_start < 0 or login_end < 0: raise RuntimeError('Login block missing; no files modified')
    b = s[login_start:login_end]
    b = once(b,
        'const u=await this.user(username);',
        '''let u=await this.user(username);
      if(!u && (username.includes("@") || /^\\+?[0-9]{8,15}$/.test(username))){
        const names=await this.ctx.storage.get("usernames")||[];
        for(const name of names){
          const candidate=await this.user(name);
          if(candidate && (String(candidate.email||"").toLowerCase()===username || String(candidate.phone||"")===username)){
            u=candidate;break;
          }
        }
      }''',
        'Phone/email login lookup')
    b = once(b, 'const token=await this.createSession(username);', 'const token=await this.createSession(u.username);', 'Canonical session user')
    b = once(b, 'return j({ok:true,token,user:await this.publicUser(username)});', 'return j({ok:true,token,user:await this.publicUser(u.username)});', 'Canonical public profile')
    return s[:login_start]+b+s[login_end:]


GOOGLE_MODAL = r'''// AL-ATTA v1.0.10: manual Google registration for first-time users.
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
        s=once(s,marker,GOOGLE_MODAL+'\n'+marker,'Web Google registration modal')
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
    s=once(s,old,new,'Google callback integration')
    s=once(s,'$("profileUsername").textContent="@"+p.username;',
        '$("profileUsername").textContent=""; $("profileUsername").hidden=true; $("profileUsername").style.display="none";',
        'Hide handle below profile name')
    # The same field is used by both tabs; use mode-sensitive prompts.
    hint_hook = "// AL-ATTA v1.0.10 login/register hint"
    if hint_hook not in s:
        hint_js = '''let pendingVerificationId="";
// AL-ATTA v1.0.10 login/register hint
function refreshLoginIdentifierHint(){
  const field=document.getElementById("usernameInput");
  if(field)field.placeholder=authMode==="register" ? "اختر اسم مستخدم لحسابك" : "الهاتف أو البريد أو اسم المستخدم";
}
["loginTab","registerTab","attaRegisterShortcut"].forEach(id=>document.getElementById(id)?.addEventListener("click",refreshLoginIdentifierHint));'''
        s=once(s,'let pendingVerificationId="";', hint_js, 'Context-aware login hint')
    return s


def index_patch(s):
    import re
    if 'id="usernameInput"' not in s:raise RuntimeError('Web login input not found')
    return re.sub(r'(<input id="usernameInput" placeholder=")[^"]+("[^>]*>)',
                  r'\1الهاتف أو البريد أو اسم المستخدم\2',s,count=1)


def main():
    for f in (WORKER,WEB,INDEX):
        if not f.is_file():raise SystemExit(f'Cannot find {f}; upload scripts into repository root first.')
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
        else: print(f'Unchanged: {path.relative_to(ROOT)}')
    print('Patch complete. Publish updated Cloudflare worker AND site assets before creating a new Google account.')

if __name__=='__main__':main()
