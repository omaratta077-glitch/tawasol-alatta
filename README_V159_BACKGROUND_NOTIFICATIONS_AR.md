# تواصل العطا 1.0.59 — الإشعارات ورنين الخلفية وإضاءة الشاشة

هذه النسخة مبنية على **1.0.58** التي أكد المستخدم نجاح تشغيل YouTube فيها. لا تغيّر `YouTubePlayer` أو `atta-site-youtube-bridge.js` أو `youtube-section.js` أو مصنع الإطار.

## ما أضيف

- إرسال FCM بعد تخزين الرسائل من HTTP أو WebSocket، ولا يُرسل تنبيه رسالة عن إشارة مكالمة (`call-signal`).
- إرسال إشعارات طلب الصداقة، قبولها، الإعجاب، التفاعل، التعليقات، الردود، المشاركة، والمتابعة. تُرسل الإشعارات فقط من المسارات الموثّقة على الخادم، ومن دون نص الرسالة أو بيانات الجلسة داخل Push.
- إشعارات أندرويد بقناتين منفصلتين للرسائل والتفاعلات، ورنين المكالمات الواردة في قناة المكالمات القائمة، وأزرار رد/رفض.
- الضغط على تنبيه رسالة يفتح الرسائل، وعلى طلب الصداقة يفتح الأصدقاء، وعلى التفاعل يفتح الإشعارات.
- إصلاح تمرير جلسة تسجيل الخروج القديمة إلى `/api/push/unregister` قبل تفريغها.
- منع استمرار استقبال إشعارات حساب سابق على الجهاز بعد تسجيل دخول آخر (ملكية رمز FCM على الخادم).
- إبقاء شاشة أندرويد مضاءة عندما يكون مشغّل YouTube الذي اختاره المستخدم ظاهرًا، وإزالة ذلك عند ترك صفحة يوتيوب؛ وإضافة ملف مستقل `youtube-screen-awake.js` لموقع الويب يستخدم Screen Wake Lock عند استقبال حدث تشغيل حقيقي مع بقاء الصفحة ظاهرة.

## شرط تشغيل إشعارات الخلفية

المشروع **لا يحتوي على مفاتيح Firebase الحقيقية**. راجع `README_V150_VIDEO_CALLS_BACKGROUND_AR.md` لضبط:

1. Firebase Console: سجّل تطبيق Android بالحزمة `com.alatta.nativeapp`؛ فعّل FCM API v1.
2. GitHub → Settings → Secrets and variables → Actions → **Variables**: `ALATTA_FCM_APP_ID` و`ALATTA_FCM_SENDER_ID` و`ALATTA_FCM_PROJECT_ID` و`ALATTA_FCM_API_KEY`، من بيانات إعداد تطبيق Firebase العلنية.
3. Cloudflare Worker → Settings → Variables and Secrets: سرّ خادم واحد `FIREBASE_SERVICE_ACCOUNT_JSON`، يحتوي JSON لحساب خدمة مخوّل بإرسال FCM. **لا ترسل المفتاح السري في المحادثة أو GitHub ولا تضعه في ZIP**.
4. انشر `cloudflare-site` على Cloudflare، ثم ابنِ APK الرسمي بتوقيعه الحالي في GitHub Actions.
5. على HONOR فعّل إذن الإشعارات، اسمح للتطبيق بالعمل في الخلفية، وأوقف تقييد البطارية عنه وفق الخيارات المتوفرة في جهازك. اختبر مكالمة ورسالة وطلب صداقة وإعجاب **بعد الخروج إلى الشاشة الرئيسية دون Force stop**.

تنبيه: Android قد يؤخر التنبيهات عند ضعف الاتصال أو وضع توفير الطاقة؛ **Force stop** يمنع وصول FCM حتى يعاد فتح التطبيق. وجود الكود لا يعني وصولًا مؤكدًا قبل التجربة على هاتفين/حسابين.

## YouTube

أبقينا مسار تشغيل YouTube الناجح بدون تغيير. إبقاء الشاشة مضاءة عند مشاهدة الفيديو وهو ظاهر يدعم Android `FLAG_KEEP_SCREEN_ON`، والموقع يستخدم Screen Wake Lock عندما يوفّره المتصفح. **تشغيل YouTube المضمّن بعد قفل الشاشة أو تصغير التطبيق ليس مدعومًا** لمراعاة سياسة YouTube. 

## الفحوص

نفّذ من جذر المشروع:

```sh
python3 scripts/check_core.py
node scripts/test_backend.mjs
node scripts/test_call_push.mjs
node scripts/test_social_push.mjs
node scripts/test_youtube_single_origin.mjs
node scripts/verify_sources.mjs
```

فحوص المصدر لا تعادل تجربة APK مبني على هاتف حقيقي، ولا تثبت جاهزية مفاتيح Firebase في حسابك.
