#!/usr/bin/env python3
"""Verify and label deployed-source Google signup support (AL-ATTA v1.0.23).

Safe/idempotent: checks the existing Worker really accepts a verified Google
credential, user-selected name/username/birthdate, and creates an immediate
session without email OTP. Refuses to modify unknown/older code. No user data,
secrets or Google keys are touched.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
worker = root / 'cloudflare-site/src/worker.js'
s = worker.read_text(encoding='utf-8')
a = s.find('if(url.pathname==="/api/google-login"')
b = s.find('if(url.pathname==="/api/login"', a + 1)
if a < 0 or b < 0:
    raise SystemExit('SAFE ABORT: Google login handler markers were not found. No files modified.')
block = s[a:b]
required = {
    'verified Google ID token': 'oauth2.googleapis.com/tokeninfo',
    'Web OAuth client ID audience': 'claims.aud!==clientId',
    'unique user-supplied username': 'chosenUsername',
    'date-of-birth validation': 'this.birthDateAge(chosenBirthDate)',
    'account and Google mapping storage': 'this.ctx.storage.put(googleKey,username)',
    'immediate app session': 'this.createSession(username)',
}
missing = [label for label, fragment in required.items() if fragment not in block]
if missing:
    raise SystemExit('SAFE ABORT: Cloudflare source lacks '+', '.join(missing)+
        '. Apply earlier auth + birthdate workflow patches to main first. No files modified.')
# Ensure the supported flow never uses the e-mail verification endpoint.
if '/api/register-request' in block or '/api/register-verify' in block:
    raise SystemExit('SAFE ABORT: Google signup handler calls email OTP. No files modified.')
# The existing endpoint is read-only and already exposes nonsecret capabilities.
old = 'return j({ok:true,enabled:!!clientId,clientId,nativeUsernameSignup:true,birthDateSignup:true});'
previous = 'return j({ok:true,enabled:!!clientId,clientId,nativeUsernameSignup:true,birthDateSignup:true,googleSignupVersion:122});'
new = 'return j({ok:true,enabled:!!clientId,clientId,nativeUsernameSignup:true,birthDateSignup:true,googleSignupVersion:123});'
if new in s:
    print('Google signup v1.0.23 already enabled; nothing to change.')
elif s.count(old) == 1 or s.count(previous) == 1:
    old_marker = old if old in s else previous
    s = s.replace(old_marker, new, 1)
    worker.write_text(s, encoding='utf-8')
    print('Verified Google signup capability 123 in source. DEPLOY Cloudflare next.')
else:
    raise SystemExit('SAFE ABORT: Google config marker changed or older; no files modified.')
