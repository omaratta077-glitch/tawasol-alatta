# تواصل العطا — الإصدار 1.0.66 (Build 166)

## النسخة المرجعية

الملفات الأساسية مستمدة من **المشروع الكامل v1.0.65**. لم تتغير شاشات مكالمات الفيديو ولا مشغّل YouTube الذي يعمل عندك ولا Google Native Sign-In ولا كود Firebase.

## سبب وإصلاح خطأ تسجيل Google (Cloudflare HTTP 500)

سجل Cloudflare قدّم السبب الفعلي:

`Pbkdf2 failed: iteration counts above 100000 are not supported (requested 180000)`

عدّلنا `cloudflare-site/src/worker.js` لتستخدم كلمات المرور الجديدة `KDF_ITERATIONS=100000` عند اشتقاق PBKDF2. بقي تنسيق تخزين الهاش كما هو (`pbkdf2:<rounds>:<salt>:<digest>`)، بدون الكتابة فوق كلمات المرور الموجودة في قاعدة البيانات. أضفنا `scripts/test_google_signup_pbkdf_cap.mjs` لاختبار نجاح إنشاء حساب Google وتغيير/إعادة تعيين كلمات المرور في محاكاة حد Cloudflare.

**تنبيه التوافق:** أي هاش موجود مسبقًا بـ180000 دورة لا يمكن ضمان التحقق منه داخل Cloudflare الذي يرفضها. لا تُخفّض عدّاد الهاش القديم من غير إعادة اشتقاق آمنة لكلمة المرور. حسابات Google الحالية التي لا تحتاج تحقق كلمة مرور محلية لا تتأثر بهذا التحقق عادة.

## طريقة الرفع بدون تعارض

1. احتفظ بالنسخة الاحتياطية المحلية `v1.0.65` وبالفرع `main` كما هما. لا تستخدم Force Push ولا Reset.
2. افتح GitHub Desktop وتأكد أنك على الفرع `stable-v1.0.65-4-`، وليس `main`. افحص تاريخ الفرع وأي تغييرات حديثة قبل استبدال ملفات.
3. فك الضغط وانسخ **محتويات** مجلد `tawasol-alatta-main` فوق المجلد المحلي لذلك الفرع (لا تنسخ مجلد `.git` ولا تستبدل المشروع بجذر جديد).
4. راجع `Changes` في GitHub Desktop؛ لا ترفع ملفات بها `<<<<<<<` أو `>>>>>>>`، ولا تحذف أي ملف مفيد من المستودع بدون مراجعة. ثم `Commit` و `Push origin`.
5. للتأكد من بناء APK: افتح Actions → `Build AL-ATTA Official Release APK` → `Run workflow` واختر الفرع `stable-v1.0.65-4-` (**تشغيل يدوي**). فحص التوقيع الرسمي يبقى كما هو.
6. **نشر Cloudflare منفصل عن رفع GitHub وبناء APK**: من جذر المشروع `cd cloudflare-site` ثم `npx wrangler deploy` بعد التأكد من صلاحية حساب Cloudflare، أو نفّذ آلية النشر المعتمدة عندك. لا تنشر تحديث الموقع الذي لم يراجع على الخادم المباشر.
7. بعد النشر، جرّب إنشاء حساب Google جديد مرة واحدة وتحقق من اختفاء خطأ 180000 في سجلات Cloudflare.

مفاتيح Firebase يجب أن تبقى في GitHub Variables وCloudflare Secret؛ لا تضع ملفات JSON الخاصة بالخادم في المشروع.

## اختبارات محلية

- `python3 scripts/check_core.py`
- `node scripts/test_google_signup_pbkdf_cap.mjs`
- `node scripts/test_backend.mjs`
- فحوص JavaScript والمكالمات ويوتيوب المعتادة.

**هذه الفحوص لا تعني أن APK بُني أو أن التغييرات نُشرت على Cloudflare.**
