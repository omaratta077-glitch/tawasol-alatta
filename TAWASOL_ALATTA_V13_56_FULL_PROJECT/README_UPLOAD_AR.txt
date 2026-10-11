تواصل العطا — V13.56 FULL PROJECT
=================================

هذه نسخة كاملة لمشروع Cloudflare وليست ملفي تعديل فقط.

المجلد المطلوب في GitHub هو:
cloudflare-site/

داخله مباشرة:
- package.json
- wrangler.toml
- src/worker.js
- public/ وكل ملفات الموقع

مهم:
1) لا تنشئ public أو src في جذر المستودع من هذه النسخة.
2) Cloudflare Root Directory يبقى: cloudflare-site
3) Build/Deploy command: npx wrangler deploy
4) ملفات تسجيل الدخول المنظفة موجودة داخل cloudflare-site/public.
5) worker.js يتضمن مسار Android Digital Asset Links الخاص بتطبيق TWA.

الإصدار: V13.56
