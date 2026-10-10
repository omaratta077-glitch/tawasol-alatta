تواصل العطا — Android Signed APK via GitHub Secrets

Package: com.alatta.tawasol
الموقع: https://tawasol-alatta.omaratta077-4fd.workers.dev/

هذا المشروع لا يحتوي أي ملف keystore أو كلمة مرور.
التوقيع يتم داخل GitHub Actions فقط عبر Repository Secrets.

مكان المشروع في المستودع:
android-app/

أسماء GitHub Secrets المطلوبة بالضبط:
KEYSTORE_BASE64
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD

بعد إضافة الأسرار:
GitHub > Actions > Build Signed Tawasol APK > Run workflow
ثم نزّل Artifact باسم Tawasol-Alatta-Signed-APK.

مهم: احتفظ بمادة التوقيع خارج GitHub. التحديثات المستقبلية يجب أن تستخدم نفس المفتاح.
