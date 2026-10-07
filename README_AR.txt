تواصل العطا V1

النسخة الأولى تشمل:
- تسجيل اسم المستخدم.
- قائمة المستخدمين المتصلين.
- محادثات نصية لحظية.
- مكالمات صوتية.
- مكالمات فيديو.
- قبول / رفض المكالمة.
- كتم المايك.
- تشغيل / إيقاف الكاميرا.
- مشاركة الشاشة.
- إنهاء المكالمة.
- WebRTC.
- Cloudflare Worker + Durable Object للإشارات Signaling.

طريقة التشغيل محلياً:
1) ثبّت Node.js.
2) افتح Terminal داخل المشروع.
3) نفّذ:
   npm install
4) ثم:
   npx wrangler dev

طريقة الرفع على Cloudflare:
1) سجل الدخول إلى Cloudflare/Wrangler.
2) نفّذ:
   npx wrangler deploy
3) ستحصل على رابط HTTPS عام.

مهم جداً:
- لكي تعمل المكالمات من أي شبكة وبأعلى موثوقية، يجب إضافة TURN Server.
- افتح public/config.js وأضف TURN username/password/url.
- النسخة الحالية تستخدم STUN فقط كإعداد افتراضي.
- تسجيل الدخول الحالي هو اسم فقط، وليس نظام حسابات آمن بعد.
  المرحلة التالية يجب أن تضيف Auth حقيقي قبل الاستخدام الفعلي داخل الشركة.

الملفات المهمة:
- src/worker.js       : Signaling + WebSocket
- public/index.html   : الواجهة
- public/app.js       : WebRTC / Chat / Calls
- public/config.js    : إعداد STUN/TURN
- wrangler.toml       : إعداد Cloudflare
