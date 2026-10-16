# تواصل العطا — الإصدار الرسمي المنظّم v1.0.48

هذه **شيفرة المشروع الكاملة**، وليست APK ولا باتشًا جزئيًا.

- Android الرسمي الوحيد: `android-native/`، الحزمة `com.alatta.nativeapp`، الكود `148` والإصدار `1.0.48`.
- نظام توقيع Release الأصلي لم يُستبدل: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` في GitHub Secrets بالمستودع الأساسي.
- الموقع: `cloudflare-site/public/`، وخادم Cloudflare: `cloudflare-site/src/worker.js`.
- اختبار المصدر: `python3 scripts/check_core.py`، واختبارات Node داخل `scripts/`.
- استعدنا من كود الاختبار المنفصل رنين وصول المكالمة، وبانر الاتصال بعرض التطبيق، وإعدادات الصداقة وتفاصيل الملف، والحماية الموجودة في الخادم.
- مشغّل YouTube أصلي واحد في Android، وتنسيق CSS واحد لنافذة YouTube على الموقع مع زيادة ارتفاع المشغّل.

⚠️ لا ترفعها على `main` من دون أخذ نسخة احتياطية ومراجعة الفروق؛ بناء APK الرسمي تلقائي بعد Push تغييرات `android-native`. إصلاح عرض فيديو مكالمات Android **لم يُتحقق منه عمليًا**، وكذلك صلاحيات المدير تحتاج تأكيد إعداد `SUPER_ADMIN_USERNAME` على Cloudflare.

احتفظ بصفحة `public/android-google-auth.html` مؤقتًا للتوافق مع النسخ القديمة؛ ملف Kotlin القديم `GoogleWebBridge.kt` غير مستخدم وتم حذفه.

انظر الملف `CHANGELOG_CLEANUP_v1.0.48_AR.md` لتفاصيل الحذف والمخاطر والتثبيت.
