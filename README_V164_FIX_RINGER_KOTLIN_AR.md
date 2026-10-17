# تواصل العطا — الإصدار 1.0.64 (Build 164)

## سبب فشل البناء السابق

ظهرت أخطاء Kotlin لأن GitHub احتوى على ملفات غير متزامنة:
- MainActivity.kt كان يستدعي IncomingCallRingtone() بدون callId.
- NativeCallScreen.kt يتوقع IncomingCallRingtone(callId: String).
- AttaPush.kt الموجود على GitHub لا يعرّف foregroundRingCallId المستخدم في NativeCallScreen.kt.

الإصلاح: رفع الملفات الثلاثة معًا من نفس نسخة المصدر، بالإضافة إلى ملف رقم الإصدار وفحص check_core.py.

## الملفات المعدلة/المطلوب استبدالها معًا

1. android-native/app/src/main/java/com/alatta/nativeapp/MainActivity.kt
2. android-native/app/src/main/java/com/alatta/nativeapp/NativeCallScreen.kt
3. android-native/app/src/main/java/com/alatta/nativeapp/AttaPush.kt
4. android-native/app/build.gradle.kts
5. scripts/check_core.py

قبل النسخ: GitHub Desktop → Fetch origin ثم Pull origin. ثم انسخ محتويات ملف الإصلاح إلى مجلد المستودع مع الاحتفاظ بمساراتها، ولا تنسخ مجلدًا كاملًا فوق مجلد آخر باسم مكرر. ثم Commit وPush origin.

تمت المحافظة على موقع Cloudflare وملفات YouTube وتسجيل Google وإعدادات Firebase والتوقيع دون تعديل.

ملاحظة: فحوص المصدر ليست بناء APK؛ نجاح البناء يُتحقق منه في GitHub Actions.
