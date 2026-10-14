#!/usr/bin/env python3
"""Patch only the exact legacy Google username-collision response in existing Worker.

Run from the GitHub repo root after checking that cloudflare-site/src/worker.js
is the currently deployed Worker source. NEVER replace the Worker with an old
archived file; this tool changes one error response and leaves all else intact.
"""
from pathlib import Path

worker = Path("cloudflare-site/src/worker.js")
if not worker.is_file():
    raise SystemExit("Worker not found: cloudflare-site/src/worker.js. Update the actual Cloudflare project instead; no files were modified.")
text = worker.read_text(encoding="utf-8")
legacy = 'if(await this.user(username))return j({ok:false,error:"تعذر إنشاء الحساب. تواصل مع الإدارة."},409);'
updated = 'if(await this.user(username))return j({ok:false,error:"اسم المستخدم موجود بالفعل، اختر اسمًا آخر.",code:"USERNAME_TAKEN"},409);'
if updated in text:
    print("Cloudflare duplicate-username response is already patched.")
elif text.count(legacy) != 1:
    raise SystemExit("Expected exactly one legacy Google collision branch; inspect your current Worker manually. No files modified.")
else:
    worker.write_text(text.replace(legacy, updated), encoding="utf-8")
    print("Patched Cloudflare duplicate-username error. DEPLOY the Worker separately for website/API clients.")
