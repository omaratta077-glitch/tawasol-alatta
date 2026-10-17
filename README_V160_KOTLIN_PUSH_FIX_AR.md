# تواصل العطا 1.0.60 — إصلاح بناء إشعارات Firebase

## سبب الفشل

في `AttaPush.kt` داخل `notifyActivity(context, data: Map<String, String>)` كانت العبارة `data = Uri.parse(...)` تشير إلى المعامل `data` من النوع `Map` بدلاً من خاصية `Intent.data`، ففشل `compileReleaseKotlin` برسالتي `val cannot be reassigned` و`Assignment type mismatch`.

## التعديل الوحيد على منطق التطبيق

استبدال `data = Uri.parse(...)` بـ `this.data = Uri.parse(...)` في `Intent(...).apply` الخاص بفتح إشعارات الرسائل والتفاعلات. لم تُغيَّر أي دالة في مشغل يوتيوب أو WebView أو Cloudflare أو إعدادات توقيع APK.

## الإصدار

`versionName = "1.0.60"` و `versionCode = 160`؛ اسم APK يتبع إعدادات مسار إصدار GitHub Actions القائم.

## الفحص المطلوب

`python3 scripts/check_core.py` ثم بناء `assembleRelease` بواسطة GitHub Actions. فحوص المصدر لا تغني عن ترجمة Kotlin أو تجربة إشعارات FCM على جهاز حقيقي.

## نشر التحديث

بعد تحديث المستودع `Fetch/Pull`، استبدل الملفات الثلاثة المتغيرة أو انسخ المشروع من الأرشيف الكامل، ثم `Commit` و`Push` وشغّل Build AL-ATTA Official Release APK. إعدادات Firebase وCloudflare التي أضيفت سابقاً لا تحتاج لإعادة إدخال.
