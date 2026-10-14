#!/usr/bin/env python3
"""AL-ATTA v1.0.14: require date of birth for NEW accounts only.

Safe, atomic text patch for the v1.0.10-auth-patched AL-ATTA codebase.
Existing users/sessions are not migrated, renamed or edited.
DOB remains private in Durable Object user records; publicUser does not expose it.
"""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
WORKER=ROOT/'cloudflare-site/src/worker.js'
APP=ROOT/'cloudflare-site/public/app.js'
INDEX=ROOT/'cloudflare-site/public/index.html'

def once(source, old, new, label):
    if new in source:
        return source
    n=source.count(old)
    if n!=1:
        raise RuntimeError(f'{label}: expected exactly 1 match, found {n}')
    return source.replace(old,new,1)

AGE_METHOD = '''  // v1.0.14: calendar date is authoritative; do not trust submitted numeric age.
  birthDateAge(value){
    const raw=String(value||"").trim();
    if(!/^\\d{4}-\\d{2}-\\d{2}$/.test(raw))return null;
    const [y,m,d]=raw.split("-").map(Number);
    const birth=new Date(Date.UTC(y,m-1,d));
    if(birth.getUTCFullYear()!==y || birth.getUTCMonth()!==m-1 || birth.getUTCDate()!==d)return null;
    const now=new Date();
    let age=now.getUTCFullYear()-y;
    if(now.getUTCMonth()+1<m || (now.getUTCMonth()+1===m && now.getUTCDate()<d))age--;
    return age>=13 && age<=120 ? age : null;
  }

'''

def patch_worker(src):
    src=once(src,'  normalizeRegistration(body={}){',AGE_METHOD+'  normalizeRegistration(body={}){','Date of birth helper')
    src=once(src,'      age:Number(body.age||0),','      birthDate:String(body.birthDate||"").trim(),\n      age:this.birthDateAge(body.birthDate),','Registration: calculate age')
    src=once(src,'if(reg.username.length<3)return "اسم المستخدم 3 أحرف على الأقل";',
             'if(!/^[a-z0-9_]{3,24}$/.test(reg.username))return "اختر اسم مستخدم من 3 إلى 24 حرفًا إنجليزيًا أو رقمًا أو _";',
             'Valid chosen username')
    src=once(src,'if(!Number.isFinite(reg.age) || reg.age<13 || reg.age>120)return "اكتب عمرًا صحيحًا من 13 إلى 120";',
             'if(reg.age===null)return "اختر تاريخ ميلاد صحيحًا من التقويم (الحد الأدنى للعمر 13 عامًا)";',
             'Registration date validation')
    src=once(src,'          age:reg.age,\n          phone:reg.phone,',
             '          age:reg.age,\n          birthDate:reg.birthDate,\n          phone:reg.phone,','Pending registration DOB')
    src=once(src,'        age:reg.age,\n        phone:reg.phone,',
             '        age:reg.age,\n        birthDate:reg.birthDate,\n        phone:reg.phone,','New account DOB')
    src=once(src,'        const chosenAge=Number(body.age);',
             '        const chosenBirthDate=String(body.birthDate||"").trim();\n        const chosenAge=this.birthDateAge(chosenBirthDate);',
             'Google DOB derived age')
    src=once(src,'!Number.isInteger(chosenAge) || chosenAge<13 || chosenAge>120',
             'chosenAge===null','Google DOB validation')
    src=once(src,'"اكتب الاسم الظاهر والعمر الصحيح لإنشاء حساب Google"',
             '"اختر الاسم الظاهر وتاريخ الميلاد الصحيح لإنشاء حساب Google"','Google DOB validation text')
    src=once(src,'if(await this.user(username))return j({ok:false,error:"تعذر إنشاء الحساب. تواصل مع الإدارة."},409);',
             'if(await this.user(username))return j({ok:false,error:"اسم المستخدم مستخدم بالفعل؛ اختر اسمًا آخر"},409);',
             'Google username uniqueness error')
    src=once(src,'username,displayName:fullName,fullName,age,city,phone:"",email,',
             'username,displayName:fullName,fullName,age,birthDate:chosenBirthDate,city,phone:"",email,',
             'New Google profile DOB')
    src=once(src,'nativeUsernameSignup:true', 'nativeUsernameSignup:true,birthDateSignup:true','Backend capability flag')
    # Do not expose private date-of-birth through publicUser response.
    public = src[src.index('async publicUser('):src.index('async friends(')]
    if 'birthDate:' in public or 'dateOfBirth:' in public:
        raise RuntimeError('Public profile exposes DOB; refusing automatic patch')
    return src


def patch_app(src):
    # Website ordinary registration now sends actual date rather than typed numeric age.
    src=once(src,'      body.age=$("ageInput").value;',
             '      body.birthDate=$("ageInput").value;','Website signup date payload')
    # Upgrade Google signup modal created by v1.0.10, without modifying login UI.
    src=once(src,'<input name="age" type="number" required min="13" max="120" placeholder="العمر"',
             '<label style="color:#e6b94e;font-size:13px">تاريخ الميلاد (اليوم / الشهر / السنة)</label>\n      <input name="birthDate" type="date" required aria-label="تاريخ الميلاد"',
             'Website Google calendar date input')
    src=once(src,'age:Number(form.elements.age.value)',
             'birthDate:form.elements.birthDate.value','Website Google date payload')
    src=once(src,'    document.body.appendChild(overlay);\n    form.elements.fullName.focus();',
             '''    document.body.appendChild(overlay);
    const born=form.elements.birthDate;
    if(born){ born.min=attaCalendarLimit(-120); born.max=attaCalendarLimit(-13); }
    form.elements.fullName.focus();''',
             'Google modal DOB date bounds')
    # Format the calendar min/max dates in local time; avoid timezone shifts from toISOString().
    insert='''// AL-ATTA v1.0.14: use real calendar birthdays, not a typed age.
function attaCalendarLimit(offsetYears){
  const now=new Date();
  const d=new Date(now.getFullYear()+offsetYears,now.getMonth(),now.getDate());
  const p=n=>String(n).padStart(2,"0");
  return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())}`;
}
const birthDateInput=document.getElementById("ageInput");
if(birthDateInput){birthDateInput.min=attaCalendarLimit(-120);birthDateInput.max=attaCalendarLimit(-13);}
'''
    src=once(src,'let pendingVerificationId="";',insert+'\nlet pendingVerificationId="";','Website calendar configuration')
    return src


def patch_index(src):
    src=once(src,'<input id="ageInput" type="number" min="13" max="120" placeholder="العمر">',
             '<label for="ageInput" style="display:block;color:#e6b94e;font-size:13px;margin:6px 0 3px">تاريخ الميلاد (اليوم / الشهر / السنة)</label>\n          <input id="ageInput" type="date" required aria-label="تاريخ الميلاد" title="تاريخ الميلاد">',
             'Website signup calendar input')
    return src


def main():
    paths=(WORKER,APP,INDEX)
    for f in paths:
        if not f.is_file():raise SystemExit(f'Missing {f}')
    original={f:f.read_text(encoding='utf-8') for f in paths}
    try:
        results={WORKER:patch_worker(original[WORKER]),
                 APP:patch_app(original[APP]),
                 INDEX:patch_index(original[INDEX])}
    except Exception as e:
        raise SystemExit(f'SAFE ABORT: {e}; no files modified') from e
    for f,content in results.items():
        if content!=original[f]:
            f.write_text(content,encoding='utf-8')
            print('Updated:',f.relative_to(ROOT))
        else:print('Already current:',f.relative_to(ROOT))
    print('DOB update finished; deploy the Worker and static site. Existing accounts are unchanged.')

if __name__=='__main__':main()
