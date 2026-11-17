# القنوات المرخصة لتطبيق AL-ATTA TV

هدف المشروع: إتاحة القنوات للمستخدمين عبر الإنترنت **بإذن صاحب الحقوق**. هذا الكتالوج منفصل عن قناة YTV ومشغّلها وعن كتالوج AL-ATTA Media الرياضي.

## الحالة الحالية
- `cloudflare-site/public/tv-authorized-channels.json`: قائمة القنوات المصرح بإعادة بثها حاليًا (فارغة حتى وصول تصريح).
- `alatta-tv/rights/distribution-candidates.json`: جهات رسمية محتملة للحصول على الاتفاقيات. **ليست قنوات قابلة للتشغيل**.
- لا يجوز استخدام رابط الموقع الرسمي أو m3u8 متاح علنًا دليلًا على امتلاك حقوق البث لجميع مستخدمي التطبيق.

## قبل تشغيل أي قناة
1. الحصول على تصريح مكتوب باسم الجهة المشغّلة لـ AL-ATTA TV. يحدد Android/OTT، دول التوزيع، مدة السماح، الإعلانات، إعادة البث عبر CDN، عدد المستخدمين إن وُجد، شعار القناة، وهل الحقوق تخص **البث الخطي المباشر** كله أو برامج محددة.
2. الحصول على رابط إدخال رسمي مصرح به (HLS/DASH) أو تصريح تضمين عبر مشغّل الناشر فقط. لا يُنسخ DRM أو رموز وصول أو ترويسات التحايل.
3. التحقق من صلاحية المصدر وتجربة تشغيل الصوت والصورة وقيود البلدان على جهاز حقيقي.
4. يراجع مسؤول حقوق النشر التصريح ويضع مرجع عقد داخلي غير سري في كتالوج القنوات ويحدد تاريخ الانتهاء.
5. عند انتهاء التصريح، أو سحبه، أو فشل المصدر تُعطّل القناة. **لا تُضاف روابط غير معتمدة كبديل**.

## قنوات التواصل الرسمية
- VOA / USAGM: https://www.usagm.gov/work-with-us/become-an-affiliate/ و https://www.usagm.gov/work-with-us/content-requests/voa/
- الحرة (MBN): communications@alhurra.com (دليل USAGM للطلبات)
- France 24: distribution@france24.com (دليل استقبال France 24)
- DW: https://b2b.dw.com/page/dw-terms-conditions (التسجيل وحده ليس ترخيصًا)

### نص مختصر لطلب الترخيص
Subject: Request for licensed OTT/mobile carriage – AL-ATTA TV

Hello Distribution Team,

We operate AL-ATTA TV, an Android mobile application for viewers across the Middle East and other permitted territories. We would like to discuss a formal license to distribute your live channel(s) in our app via an approved HLS/DASH feed or your authorized embedded player.

Please advise on channel availability, coverage territories, permitted platforms, CDN retransmission, advertising, branding, fees, contract duration and a technical point of contact. We will not activate any channel before your written approval.

Thank you,
AL-ATTA TV Team

## الاختبار
شغّل `node scripts/test_tv_authorized_catalog.mjs` للتحقق من التنسيق وعدم تسرب قنوات مرشحة إلى القائمة التشغيلية.

**تنبيه:** وجود AL-ATTA TV كنسخة APK منفصلة عن العطا ميديا يعني أن ربط هذا الملف بالمشغل الذهبي يتطلب مصدر التطبيق أو نسخة قابلة للتعديل والاختبار. إنشاء الكتالوج لا يعدل APK القائم.
