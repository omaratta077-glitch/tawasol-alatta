# تحديث Tawasol Al-Atta v1.0.33 — الملفات المطلوبة فقط

هذه الحزمة تجمع ملفات Android السابقة v1.0.32 و v1.0.33 لأنك لم ترفع التحديثين بعد، وتضيف ملف إصدار Gradle.

**ارفع الملفات إلى مساراتها داخل مستودع GitHub مع استبدال القديم، دون رفع باقي المشروع:**

- `android-native/app/build.gradle.kts`
- `android-native/app/src/main/assets/atta-call.html`
- `android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt`
- `android-native/app/src/main/java/com/alatta/nativeapp/MessengerChat.kt`
- `android-native/app/src/main/java/com/alatta/nativeapp/NativeCallScreen.kt`
- `android-native/app/src/main/java/com/alatta/nativeapp/UserProfileScreen.kt`
- `cloudflare-site/src/worker.js`

## مهم
- ملف `cloudflare-site/src/worker.js` مبني على كود الخادم الذي أرسلته في ملف النص، وليس على نسخة Worker القديمة داخل حزمة v1.0.33 السابقة. تمت إضافة أرشفة/استعادة المنشورات وتصفية المنشورات المؤرشفة، مع الحفاظ على محتويات الملف المرسل بما فيها زر تنزيل التطبيق.
- ملف `android-native/app/build.gradle.kts` يضبط `versionName` إلى `1.0.33` و `versionCode` إلى `133`؛ لا تُرجِع versionCode للخلف لو كان إصدار منشور أعلى منه.
- تغيير رقم الإصدار لا يغيّر اسم ملف GitHub Actions تلقائيًا إذا كان اسم الـ Artifact ثابتًا داخل workflow. تحديث اسم الـ Artifact إلى v1.0.33 يتطلب تعديل إعداد GitHub Actions إن ظل الاسم قديمًا.
- لم يتم بناء APK أو تشغيل اختبارات هاتف/خادم فعلية. اختبر النشر على بيئة تجريبية قبل تبديل خادم الإنتاج.
