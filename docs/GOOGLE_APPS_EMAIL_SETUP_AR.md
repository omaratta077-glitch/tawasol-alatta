# إرسال أكواد تواصل العطا باستخدام Google Apps Script

1. رابط Google Apps Script العام في `cloudflare-site/wrangler.toml` تحت `GAS_EMAIL_WEBAPP_URL`.
2. في Cloudflare > Workers & Pages > tawasol-alatta > Settings > Variables and Secrets أضف Secret باسم `GAS_EMAIL_API_SECRET`.
3. اجعل قيمة السر مطابقة تمامًا لقيمة `EMAIL_API_SECRET` في Google Apps Script > Project Settings > Script Properties.
4. لا تشارك المفتاح أو تضفه إلى GitHub أو APK. بعد نشر Worker افحص `/api/diagnostics/env`: `activeEmailProvider` = `GoogleAppsScript`.
5. اختبر إرسال كود إلى بريد تملكه من شاشة إنشاء حساب، ثم جرّب استعادة كلمة المرور.

لا تغييرات في Android. الأكواد تولّد وتتحقق منها Cloudflare فقط. Resend يظل بديلًا حتى إعداد مفتاح Gmail.
حدود Gmail المضافة: 10 محاولات/دقيقة و80 محاولة/يوم لجميع المستخدمين؛ خدمة Gmail الشخصية ليست مناسبة للإرسال الجماعي.
نوع `admin` يعمل بالكود الحالي لكنه يستخدم عنوان رسالة التأكيد الافتراضي؛ لتخصيص عنوان الإدارة يجب تحديث Google Apps Script وإعادة نشره.
