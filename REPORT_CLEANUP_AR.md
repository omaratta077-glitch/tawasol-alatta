# تقرير فحص وتنظيف مشروع تواصل العطا الأساسي

تاريخ الفحص: 16 أكتوبر 2026

## النتيجة

- عدد ملفات المصدر قبل التنظيف: **265**.
- عدد ملفات المصدر بعد التنظيف: **120** (يشمل ملف الفحص والتوثيق وWorkflow الفحص الجديد).
- الملفات القديمة والمكررة التي خرجت من النسخة المنظّمة: **148**، وكلها محفوظة في أرشيف احتياطي منفصل.
- **110 ملفًا نشطًا لم يتغير فيه بايت واحد**، بما فيها كود أندرويد الفعلي، Worker، ملفات الموقع المنشورة وWorkflow الإصدار الرسمي.
- لم يتم تعديل `versionCode` أو `versionName`، ولا أسرار GitHub أو بصمة التوقيع.

## المسارات الرسمية

- التطبيق: `android-native/` فقط (الحزمة `com.alatta.nativeapp`، الإصدار الموجود بالمصدر **1.0.43 / 143**).
- موقع Cloudflare: `cloudflare-site/public/`.
- Worker: `cloudflare-site/src/worker.js`.
- النشر الرسمي: `.github/workflows/build-native-release.yml`.
- فحوصات المستودع الجديدة: `scripts/check_core.py` و `.github/workflows/verify-source.yml`.

## تصنيفات الملفات المزالة من الحزمة الرئيسية

- لقطات ملفات أندرويد منسوخة في جذر المشروع: **81** ملف.
- مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي: **21** ملف.
- تعليمات إصدارات تاريخية: **17** ملف.
- سكربتات ترقيع واختبارات تاريخية: **10** ملف.
- تحديثات تاريخية آلية قد تعيد تطبيق كود قديم: **7** ملف.
- سكربتات وترقيعات منفصلة غير مستخدمة: **3** ملف.
- نسخ صفحات خارج مسار Cloudflare المنشور public/: **3** ملف.
- مسارات بناء مكررة / قديمة: **2** ملف.
- نسخ مرجعية غير مطلوبة للبناء: **2** ملف.
- تعليمات رفع تاريخية: **1** ملف.
- تعليمات مخصصة لتغيير سابق: **1** ملف.

## الاختبارات والقيود

1. **نجح** فحص جميع ملفات JavaScript الموجودة في المشروع الأصلي باستخدام `node --check`، وكذلك فحص صياغة ملفات Python.
2. **فشل في الأصل** اختبار `scripts/test_google_bridge.mjs` لأن علامة الجسر القديم `ALATTA_ANDROID_GOOGLE_BRIDGE_V104` غير موجودة. التسجيل الحالي يستخدم `GoogleNativeSignIn`، فلم نُرجع الجسر القديم.
3. **فشل في الأصل** اختبار `scripts/test_auth_backend.mjs` من أول مسار `password-reset-request` بحالة HTTP 404؛ الاختبار يستهدف مسارات قديمة غير موجودة حاليًا. أُرشف مع أدوات الترقية القديمة وليس جزءًا من الفحص الجديد.
4. **نجح** فحص مصدر المشروع المنظّم `python3 scripts/check_core.py` (تحقق مسارات التوقيع، Google، الرسائل، المكالمات، Cloudflare، XML وJavaScript).
5. **لم يُبْنَ APK ولم يُختبر على هاتف**. لا تتوفر هنا بيئة Android SDK ومفتاح التوقيع الرسمي. لا يمكن ادعاء نجاح 100% وظيفيًا دون تشغيل `assembleRelease` على GitHub Actions وتجربة Google والاتصالات والتحديث على أجهزة حقيقية.
6. لم ندمج قواعد CSS المتكررة داخل `cloudflare-site/public/style.css` لأن تعديل ترتيبها أو أولوية `!important` قد يغيّر تصميم الموقع. كذلك لم نحذف دوال Kotlin المتشابهة داخل واجهات مختلفة؛ ليست تكرارًا آمنًا للحذف الآلي.
7. إصدار المصدر الأساسي **1.0.43**؛ قبل نشر تحديث جديد، تحقق من آخر `versionCode` منشور فعليًا وارفعه برقم أكبر. لا تعِد استخدام وسم GitHub Release موجود.

## قبل تطبيق النسخة

- لا ترفع ZIP كاملًا إلى الفرع الرئيسي مباشرةً دون مراجعة الفرق؛ طبّق التغييرات في فرع تنظيف مستقل.
- احتفظ بالأرشيف القديم، ولا تُعد تشغيل workflows الترقيع التاريخية.
- شغّل فحص المصدر ثم `Build AL-ATTA Official Release APK` وتحقق من البصمة قبل النشر.
- اختبر تسجيل Google، الاتصال الصوتي والمرئي، الرنين والإشعارات، YouTube، صفحة الأصدقاء، وتحديث APK على جهازين.

## قائمة الملفات المؤرشفة

- `android-app/settings.gradle` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/build.gradle` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/README_AR.txt` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/gradle.properties` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/app/build.gradle` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/app/src/main/AndroidManifest.xml` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/app/src/main/res/values/strings.xml` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/app/src/main/res/values/styles.xml` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/app/src/main/res/values/colors.xml` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/app/src/main/res/mipmap-xxhdpi/ic_launcher.png` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/app/src/main/res/mipmap-mdpi/ic_launcher.png` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/app/src/main/res/mipmap-hdpi/ic_launcher.png` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/app/src/main/res/mipmap-xhdpi/ic_launcher.png` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-app/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-studio/settings.gradle` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-studio/build.gradle` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-studio/README_AR.txt` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-studio/gradle.properties` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-studio/app/build.gradle` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-studio/app/src/main/AndroidManifest.xml` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `android-studio/app/src/main/java/com/alatta/tawasol/MainActivity.java` — مشاريع أندرويد قديمة مختلفة عن التطبيق الرسمي
- `.github/workflows/build-android-apk.yml` — مسارات بناء مكررة / قديمة
- `.github/workflows/build-native-apk.yml` — مسارات بناء مكررة / قديمة
- `.github/workflows/apply-google-signup-v122.yml` — تحديثات تاريخية آلية قد تعيد تطبيق كود قديم
- `.github/workflows/apply-auth-v110.yml` — تحديثات تاريخية آلية قد تعيد تطبيق كود قديم
- `.github/workflows/apply-profile-reactions-v121.yml` — تحديثات تاريخية آلية قد تعيد تطبيق كود قديم
- `.github/workflows/apply-google-signup-v123.yml` — تحديثات تاريخية آلية قد تعيد تطبيق كود قديم
- `.github/workflows/apply-birthdate-v114.yml` — تحديثات تاريخية آلية قد تعيد تطبيق كود قديم
- `.github/workflows/install-native-auth-backend.yml` — تحديثات تاريخية آلية قد تعيد تطبيق كود قديم
- `.github/workflows/install-google-web-bridge.yml` — تحديثات تاريخية آلية قد تعيد تطبيق كود قديم
- `scripts/patch_google_bridge.py` — سكربتات ترقيع واختبارات تاريخية
- `scripts/patch_google_signup_v122.py` — سكربتات ترقيع واختبارات تاريخية
- `scripts/test_auth_backend.mjs` — سكربتات ترقيع واختبارات تاريخية
- `scripts/patch_auth_v110.py` — سكربتات ترقيع واختبارات تاريخية
- `scripts/patch_worker_auth.py` — سكربتات ترقيع واختبارات تاريخية
- `scripts/fix_cloudflare_google_duplicate_v127.py` — سكربتات ترقيع واختبارات تاريخية
- `scripts/patch_birthdate_v114.py` — سكربتات ترقيع واختبارات تاريخية
- `scripts/test_google_bridge.mjs` — سكربتات ترقيع واختبارات تاريخية
- `scripts/patch_google_signup_v123.py` — سكربتات ترقيع واختبارات تاريخية
- `scripts/patch_profile_reactions_v121.py` — سكربتات ترقيع واختبارات تاريخية
- `apply_v117.py` — سكربتات وترقيعات منفصلة غير مستخدمة
- `scripts_patch_auth_v110.py` — سكربتات وترقيعات منفصلة غير مستخدمة
- `OPTIONAL_changes_only.patch` — سكربتات وترقيعات منفصلة غير مستخدمة
- `PremiumAuth.kt` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `PremiumChrome.kt` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `MainActivity.kt` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `AppUpdateChecker.kt` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `GoogleNativeSignIn.kt` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_mic_off.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_square.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_heart.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_phone.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_camera_off.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_user_plus.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_plus.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_globe.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_image.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_ellipsis.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_volume.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_youtube.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_play.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_search.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_message_circle.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_check.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_home.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_chart.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_mic.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_bookmark.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_mail.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_user.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_monitor.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_camera.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_share.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_bell.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_chevron_down.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_send.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_repeat.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `launcher_colors.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_log_out.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_paperclip.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `google_g_mark.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_upload.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_chevron_left.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_video.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_round.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_arrow_left.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `AndroidManifest.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_smile.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_x.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_users.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_phone_off.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `atta_ic_flame.xml` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher (7).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_foreground (10).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_foreground (11).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_round (8).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_foreground (9).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_round (5).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher (4).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_foreground.png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_round (6).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_round (10).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_foreground (6).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher (11).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_round (13).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher (12).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_foreground (1).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_foreground (4).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_foreground (2).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher (2).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_round (12).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `logo_premium.png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher (9).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher (5).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_round.png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_round (3).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher.png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `ic_launcher_foreground (3).png` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `build.gradle.kts` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `build.gradle (13).kts` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `settings.gradle.kts` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `build.gradle (1).kts` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `gradle.properties` — لقطات ملفات أندرويد منسوخة في جذر المشروع
- `cloudflare-site/index.html` — نسخ صفحات خارج مسار Cloudflare المنشور public/
- `cloudflare-site/style.css` — نسخ صفحات خارج مسار Cloudflare المنشور public/
- `cloudflare-site/download/index.html` — نسخ صفحات خارج مسار Cloudflare المنشور public/
- `.github/workflows/design-reference/THEME_COLORS.txt` — نسخ مرجعية غير مطلوبة للبناء
- `.github/workflows/design-reference/style-reference.css` — نسخ مرجعية غير مطلوبة للبناء
- `README_AR_v1.0.30.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.13.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.16.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.19.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.18.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.27.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.24.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.17.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.29.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.20.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.22.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.23.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.28.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.15.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.21.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.12.md` — تعليمات إصدارات تاريخية
- `README_AR_v1.0.14.md` — تعليمات إصدارات تاريخية
- `README_#U0627#U0631#U0641#U0639_#U0647#U0630#U0647_#U0627#U0644#U0645#U0644#U0641#U0627#U062a_#U0641#U0642#U0637.md` — تعليمات رفع تاريخية
- `README_AR.txt` — تعليمات مخصصة لتغيير سابق
