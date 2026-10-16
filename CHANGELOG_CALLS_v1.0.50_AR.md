# تقرير تعديلات v1.0.50

التعديلات فوق الإصدار الأساسي الرسمي v1.0.49 المصحح:

- `android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt`: إصلاح تصميم مكالمة واردة (أسود/ذهبي) وربط أحداث نقر إشعارات FCM مع صفحة المكالمات.
- `android-native/app/src/main/java/com/alatta/nativeapp/NativeCallScreen.kt`: إزالة فرض طبقة hardware الخاص بـ WebView وتسمية زر العرض البديل.
- `android-native/app/src/main/assets/atta-call.html`: احتساب إطار فيديو فعلي وتشخيص الإطارات الواردة وعرض Canvas بديل عند الضغط.
- `android-native/app/src/main/java/com/alatta/nativeapp/AttaPush.kt`: Firebase initialization والتسجيل وإشعارات المكالمات بالخلفية ورفض المكالمات وحالة foreground.
- `android-native/app/src/main/AndroidManifest.xml`: POST_NOTIFICATIONS والمكونات اللازمة.
- `android-native/app/build.gradle.kts`: إصدار 1.0.50 code 150 واعتماد Firebase Messaging وتهيئة المتغيرات العامة.
- `cloudflare-site/src/worker.js`: تسجيل رموز FCM حسب الجلسة، وإرسال Push مصادق من Cloudflare باستخدام سر حساب خدمة Firebase.
- `.github/workflows/*.yml`: تمرير متغيرات Firebase العامة والتحذير عند عدم الإعداد وتشغيل الاختبارات.
- `scripts/check_core.py` و`scripts/test_call_push.mjs`: تحديث فحص الإصدار واختبارات إشعارات المستخدمين.

لم تُعدَّل مفاتيح التوقيع ولا `GoogleNativeSignIn.kt` ولا إعدادات OAuth.

**التحقق:** فحوص Python للمصدر وNode للخادم والدعوات والرنين والصلاحيات نجحت. لم يتم بناء APK محليًا لعدم وجود Android SDK/Gradle في بيئة التحقق، ولم يتم اختبار الرنين أو فيديو WebRTC على هاتف حقيقي. إعداد Firebase ورفع Worker لازمان لتفعيل الإشعارات في الخلفية.
