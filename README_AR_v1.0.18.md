# تواصل العطا — إصلاح يوتيوب v1.0.18

## سبب المشكلة
كان مشغّل يوتيوب في Android يحاول فتح `/native-youtube-player.html` على موقع Cloudflare؛ الصفحة ليست ضمن حزمة ملفات الموقع المصاحبة للإصدار 1.0.17، لذلك قد يظهر مستطيل أسود.

## الإصلاحات
- مشغّل YouTube داخل WebView، بمستند HTML مضمّن في تطبيق Android؛ لا يحتاج نشر صفحة HTML على Cloudflare.
- توفير HTTPS base URL وهوية المصدر (Referer) كما توصي تعليمات YouTube للتضمين داخل WebView.
- طريقة تشغيل ثانية باستخدام رابط YouTube embed المباشر مع ترويسة Referer.
- أزرار إعادة المحاولة وفتح الفيديو في YouTube عند منع التضمين؛ ممنوع تجاوز قيود YouTube أو استخراج بثه إلى ExoPlayer.
- دعم الفيديو بملء الشاشة باستخدام WebChromeClient، ورسالة خطأ واضحة في حال فشل تحميل الصفحة الرئيسية.
- رفع رقم إصدار التطبيق إلى 1.0.18 (versionCode 118).

## الرفع على GitHub
1. اختَر فرع main.
2. ارفع الملفين تحت المسارات الأصلية واستبدلهما:
   `android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt`
   `android-native/app/build.gradle.kts`
3. Commit changes، ثم شغّل GitHub Actions: Build AL-ATTA Official Release APK.
4. بعد نجاح البناء والنشر ثبّت التحديث من داخل التطبيق.
5. جرّب فيديو عامًّا يسمح بالتضمين. إذا ظهر مشغّل فارغ جرّب «تشغيل بديل»، وتأكد من تحديث Android System WebView / Chrome.

## حدود الفحص
تم التحقق من أن ملفات ZIP صحيحة، ومن صحة الاستبدالات والبنية العامة للكود. لم يتم بناء APK أو اختبار تشغيل YouTube على هاتف هنا؛ بعض الفيديوهات لا تسمح بالتضمين مهما كانت إعدادات التطبيق.
