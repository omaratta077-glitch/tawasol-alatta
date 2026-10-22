/**
 * Tawasol Alatta — free OTP email bridge, deployed UNDER YOUR OWN Gmail account.
 * Standalone Google Apps Script, Deploy > Web app:
 * Execute as: Me; Who has access: Anyone.
 * Keep ATTA_GMAIL_SHARED_SECRET (random 32+ chars) in Script Properties only.
 * This endpoint cannot change accounts or verify codes. Cloudflare owns OTP
 * creation, expiration, retry limits, and verification.
 */
const ATTA_GMAIL_SUBJECTS = Object.freeze({
  signup: { subject: 'كود تأكيد حساب تواصل العطا', intro: 'لتأكيد إنشاء حسابك' },
  admin: { subject: 'حماية إدارة تواصل العطا', intro: 'لتأكيد حماية الإدارة' },
  reset: { subject: 'استعادة كلمة المرور — تواصل العطا', intro: 'لاستعادة كلمة المرور' }
});

function attaMailResult_(result) {
  return ContentService.createTextOutput(JSON.stringify(result))
    .setMimeType(ContentService.MimeType.JSON);
}

// Constant-time comparison of SHA-256 digests; do not compare secret strings
// directly or echo secrets in logs.
function attaMailSecretMatches_(candidate, secret) {
  if (!candidate || !secret) return false;
  const a = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, candidate);
  const b = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, secret);
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= (a[i] ^ b[i]);
  return difference === 0;
}

function doPost(e) {
  try {
    const request = JSON.parse(String(e && e.postData && e.postData.contents || '{}'));
    const configured = String(PropertiesService.getScriptProperties()
      .getProperty('ATTA_GMAIL_SHARED_SECRET') || '');
    if (configured.length < 32 ||
        !attaMailSecretMatches_(String(request.secret || ''), configured)) {
      return attaMailResult_({ok:false,reason:'unauthorized'});
    }
    const to = String(request.email || '').trim().toLowerCase();
    const code = String(request.code || '');
    const purpose = String(request.purpose || '');
    const subject = ATTA_GMAIL_SUBJECTS[purpose];
    if (!/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(to) ||
        !/^\\d{6}$/.test(code) || !subject) {
      return attaMailResult_({ok:false,reason:'invalid_request'});
    }
    if (MailApp.getRemainingDailyQuota() < 1) {
      return attaMailResult_({ok:false,reason:'daily_quota'});
    }
    const title = 'تواصل العطا';
    const message = 'كود ' + subject.intro + ' هو: ' + code +
      '. صالح لمدة 10 دقائق. لا تشارك الكود مع أحد.';
    const html = '<div dir="rtl" style="font-family:Arial,sans-serif;text-align:center;' +
      'background:#0b0e10;color:#fff;padding:24px;border-radius:16px">' +
      '<h2 style="color:#f1c75b">' + title + '</h2>' +
      '<p>استخدم الرمز التالي ' + subject.intro + ':</p>' +
      '<div style="font-size:32px;letter-spacing:6px;color:#ffe28a;font-weight:800">' +
      code + '</div><p>صالح لمدة 10 دقائق. إذا لم تطلبه فتجاهل الرسالة.</p></div>';
    MailApp.sendEmail({to,subject:subject.subject,body:message,htmlBody:html,name:title});
    return attaMailResult_({ok:true});
  } catch (_err) {
    // Avoid leaking Gmail data or recipient email to public callers.
    return attaMailResult_({ok:false,reason:'delivery_failed'});
  }
}
