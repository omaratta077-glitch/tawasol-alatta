# تواصل العطا 1.0.52 (Build 152) — توحيد مشغّل يوتيوب

- Android: `YouTubePlayer` يستخدم رابط صفحة الموقع `native-youtube-player.html` داخل WebView، ولا يُكوِّن IFrame محليًا.
- الموقع والصفحة المخصصة للتطبيق يستدعيان الدالة المشتركة `AttaYouTubeEmbed.create` من `youtube-player-shared.js`؛ لا يوجد مُنشئ iframe مكرر.
- البحث والقائمة داخل Android كما هما. تم الاحتفاظ بزر عرض ملء الشاشة ورابط فتح YouTube عند عدم دعم التضمين.
- قبل تجربة التطبيق: يجب نشر ملفات Cloudflare `public` المحدثة، وبخاصة `youtube-player-shared.js`، لضمان عمل الفيديو. قد تظهر إعلانات YouTube وفق سياسات YouTube.
- لم تُعدل إعدادات التوقيع أو Google أو المكالمات.
- يجب بناء APK عبر GitHub Actions وتجربة التشغيل فعليًا على الهاتف؛ فحوص المصدر وحدها لا تثبت أن جميع الفيديوهات تسمح بالتضمين.
