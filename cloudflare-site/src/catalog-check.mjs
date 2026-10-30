// فحص يدوي للفهرس يبدأه المالك من التطبيق الرئيسي. بلا جدولة.
// الأسرار: PROBE_KEY (من 24 إلى 256 محرفًا)، وRESPONSE_MASK (إعداد تنسيق خاص من 16 محرفًا).
const SOURCE_URL = "https://def001.ycnapi.com/api/categories";
const MAX_RESPONSE_BYTES = 512 * 1024;
const MAX_FORM_BYTES = 4096;

const PAGE = `<!doctype html>
<html lang="ar" dir="rtl"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>اختبار جلب القائمة</title>
<style>
body{background:#080b0d;color:#f3d57d;font:17px system-ui;max-width:540px;margin:50px auto;padding:20px}
section{border:1px solid #7b6737;border-radius:20px;padding:24px}
p{color:#d1d5d8;line-height:1.8}input,button{box-sizing:border-box;width:100%;padding:15px;margin-top:15px;border-radius:12px;font:inherit}
input{background:#12171a;color:white;border:1px solid #7b6737}button{background:#e8bd52;color:#171307;border:0;cursor:pointer}
small{display:block;color:#aeb5ba;line-height:1.7;margin-top:20px}
</style>
<section><h1>اختبار جلب القائمة</h1>
<p>اضغط لبدء تجربة واحدة من Cloudflare. عند اختيار الحفظ يُكتشف القسم باسمه ثم تُجلب قنواته. مفتاح التجربة هو القيمة التي أضفتها باسم PROBE_KEY.</p>
<form method="post" action="/api/internal/catalog-check">
<label for="category">رقم القسم — اتركه فارغًا لجلب الأقسام</label>
<input id="category" name="category_id" inputmode="numeric" pattern="[1-9][0-9]{0,18}" maxlength="19" placeholder="اختياري">
<label><input name="publish" type="checkbox" value="yes" style="width:auto"> اكتشاف قسم beIN SPORTS (1080P) وحفظ أرقامه في العطا</label>
<label for="channel">رقم قناة لفحص بيانات التشغيل فقط — اترك القسم فارغًا والحفظ غير محدد</label>
<input id="channel" name="channel_id" inputmode="numeric" pattern="[1-9][0-9]{0,18}" maxlength="19" placeholder="اختياري لفحص التشغيل">
<label for="key">مفتاح التجربة</label>
<input id="key" name="probe_key" type="password" autocomplete="off" minlength="24" maxlength="256" required>
<button type="submit">إرسال طلب واحد</button>
<button type="submit" name="playback_video" value="yes">تجربة تشغيل HTTPS للقناة</button></form>
<small>الحفظ يعمل عند اختيار خانته فقط، ويحتاج Secret باسم CATALOG_SYNC_KEY في مشروع العطا الأساسي. لا يبدأ تحديث دوري.</small>
</section></html>`;

function respond(value, status = 200) {
  return new Response(JSON.stringify(value, null, 2), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'; base-uri 'none'",
      "Referrer-Policy": "no-referrer",
    },
  });
}

function sameKey(a, b) {
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return difference === 0;
}

function validId(value) {
  const text = typeof value === "string" ? value :
    typeof value === "number" && Number.isSafeInteger(value) ? String(value) : "";
  return /^[1-9][0-9]{0,18}$/.test(text) && BigInt(text) <= 9223372036854775807n ? text : null;
}

function parseCatalog(text, decoded) {
  const invalid = message => ({ state: "INVALID", message, entries: [], responseDecoded: false });
  let root;
  try { root = JSON.parse(text); }
  catch { return invalid("لم ينتج JSON كامل وصالح من قراءة الرد."); }
  if (root && typeof root === "object" && !Array.isArray(root) &&
    (root.success === false || (root.error !== undefined && root.error !== null && root.error !== false && root.error !== ""))) {
    return { ...invalid("الرد يعلن فشل الطلب؛ لم تُستخرج قائمة."), declaresFailure: true };
  }
  const rows = Array.isArray(root) ? root : root && Array.isArray(root.data) ? root.data : null;
  if (!rows) return invalid("الرد لا يحتوي قائمة data المتوقعة.");
  if (rows.length > 500) return invalid("القائمة تتجاوز حد الاختبار: 500 سجل.");
  const seen = new Set();
  const entries = [];
  for (const row of rows) {
    if (!row || typeof row !== "object" || Array.isArray(row)) return invalid("القائمة تحتوي سجلًا غير صالح.");
    const id = validId(row.id);
    const name = typeof row.name === "string" ? row.name.replace(/[\u0000-\u001f\u007f-\u009f]/g, "").trim() : "";
    if (!id || !name || seen.has(id)) return invalid("القائمة تحتوي معرّفًا غير صالح أو مكررًا، أو اسمًا مفقودًا. الأرقام الكبيرة يلزم ورودها كنص.");
    seen.add(id);
    const entry = { id, name: name.slice(0, 120) };
    if (Number.isSafeInteger(row.child_count) && row.child_count >= 0) entry.childCount = row.child_count;
    entries.push(entry);
  }
  return {
    state: entries.length ? "LIST" : "EMPTY",
    message: entries.length ? "وصلت قائمة قابلة للقراءة. الأرقام للمعاينة فقط؛ لم تُحفظ تلقائيًا." : "وصلت قائمة فارغة؛ لم يتغير أي رقم محفوظ.",
    entries, responseDecoded: decoded,
  };
}

// بيانات وصفية فقط: لا تُعد روابط البث أو قيم الترويسات أو التراخيص أو JSON الخام.
function inspectPlayback(text, decoded) {
  const invalid = message => ({ state: "INVALID", message, entries: [], responseDecoded: decoded });
  let root;
  try { root = JSON.parse(text); } catch { return invalid("رد التشغيل ليس JSON صالحًا."); }
  if (root && !Array.isArray(root) && (root.success === false || (root.error != null && root.error !== false && root.error !== "")))
    return invalid("الخادم أعلن فشل طلب التشغيل.");
  const rows = Array.isArray(root) ? root : root && Array.isArray(root.data) ? root.data : null;
  if (!rows || rows.length > 100) return invalid("شكل بيانات التشغيل غير متوقع؛ لم تُعرض قيم الرد.");
  const streams = [];
  for (const row of rows) {
    if (!row || typeof row !== "object" || Array.isArray(row)) return invalid("سجل تشغيل غير صالح.");
    const knownFields = ["name", "url", "urlType", "userAgent", "headers", "drm"].filter(k => Object.hasOwn(row, k));
    let scheme = "unknown", hasQuery = false;
    if (typeof row.url === "string") { try { const u = new URL(row.url); scheme = ["http:", "https:"].includes(u.protocol) ? u.protocol : "other"; hasQuery = !!u.search; } catch { /* رابط مبهم أو محوّل. */ } }
    const headers = row.headers && typeof row.headers === "object" && !Array.isArray(row.headers) ? row.headers : {};
    const drm = row.drm && typeof row.drm === "object" && !Array.isArray(row.drm) ? row.drm : null;
    streams.push({ knownFields, urlPresent: typeof row.url === "string" && row.url.length > 0,
      urlScheme: scheme, hasQuery, urlTypePresent: Object.hasOwn(row,"urlType"),
      userAgentPresent: typeof row.userAgent === "string" && row.userAgent.length > 0,
      headerCount: Object.keys(headers).length,
      commonHeadersPresent: ["User-Agent", "Referer", "Origin", "Authorization", "Cookie"].filter(k => Object.keys(headers).some(h => h.toLowerCase() === k.toLowerCase())),
      drmPresent: !!drm, drmLicensePresent: !!drm && typeof drm.license === "string" && !!drm.license,
      drmPropertiesPresent: !!drm && Object.hasOwn(drm,"requestProperties") });
  }
  return { state: streams.length ? "PLAYBACK_METADATA" : "EMPTY", message: streams.length ? "وصلت بيانات التشغيل. المعروض وصف الحقول فقط؛ لم يُختبر تشغيل الفيديو ولم تُعرض الروابط أو القيم السرية." : "وصلت قائمة تشغيل فارغة.",
    responseDecoded: decoded, entries: [], streams, streamCount: streams.length };
}

// يُستخدم فقط في اختبار المالك الأصلي بعد المصادقة، ولا يستخدمه نموذج الفحص العام.
function nativeSources(rows) {
  const sources = [];
  for (const [index, row] of rows.entries()) {
    if (sources.length === 10) break;
    if (!row || typeof row.url !== 'string' || row.url.length > 8192 || /[\x00-\x20\x7f]/.test(row.url) || row.drm != null ||
        (row.urlType != null && row.urlType !== 0)) continue;
    let u;
    try { u = new URL(row.url); } catch { continue; }
    const host = u.hostname.toLowerCase();
    if (!['https:', 'http:'].includes(u.protocol) || u.username || u.password || u.port ||
        !host.includes('.') || /^[\d.]+$/.test(host) || host.includes(':') ||
        /(^|\.)(localhost|local|internal|invalid|test)$/.test(host)) continue;
    // يرفض Android في الإصدار 1.0.201 كل اتصالات HTTP غير المشفّرة.
    if (u.protocol === 'http:' && !['new-redirect.online', 'cdnarvo.buzz', 'cdnravon.buzz', 'streamalvo.buzz', 'vidorise.buzz', 'vidunex.buzz', 'castavion.buzz'].some(d => host === d || host.endsWith('.' + d))) continue;
    if (row.headers != null && (typeof row.headers !== 'object' || Array.isArray(row.headers))) continue;
    const headers = {};
    let supported = true;
    for (const [name, value] of Object.entries(row.headers || {})) {
      const canonical = {'user-agent': 'User-Agent', referer: 'Referer', origin: 'Origin'}[name.toLowerCase()];
      if (typeof canonical !== 'string' || typeof value !== 'string' || !/^[\x20-\x7e]{1,2048}$/.test(value) || Object.hasOwn(headers, canonical)) { supported = false; break; }
      headers[canonical] = value;
    }
    if (!supported) continue;
    if (row.userAgent != null && row.userAgent !== '') {
      if (typeof row.userAgent !== 'string' || !/^[\x20-\x7e]{1,2048}$/.test(row.userAgent) ||
          (headers['User-Agent'] && headers['User-Agent'] !== row.userAgent)) continue;
      headers['User-Agent'] = row.userAgent;
    }
    const mimeType = /\.m3u8$/i.test(u.pathname) ? 'application/x-mpegURL' : /\.mpd$/i.test(u.pathname) ? 'application/dash+xml' : null;
    sources.push({ index, url: row.url, headers, mimeType });
  }
  return sources;
}

function chooseDirectHttps(rows) {
  for (const row of rows) {
    if (!row || typeof row.url !== 'string' || row.url.length > 8192 || row.drm != null ||
        (row.userAgent != null && row.userAgent !== '') ||
        (row.headers != null && (typeof row.headers !== 'object' || Array.isArray(row.headers) || Object.keys(row.headers).length))) continue;
    try {
      const u = new URL(row.url), host = u.hostname.toLowerCase();
      if (u.protocol !== 'https:' || u.username || u.password || u.port || !host.includes('.') ||
          /^[\d.]+$/.test(host) || host.includes(':') || /(^|\.)(localhost|local|internal|invalid|test)$/.test(host)) continue;
      return u.href;
    } catch { /* الروابط المبهمة أو غير الصالحة تحتاج معالجة مستقلة؛ لا تخمّن. */ }
  }
  return null;
}

function renderPlaybackTest(url, channelId) {
  const nonce = crypto.randomUUID().replaceAll('-', '');
  const config = JSON.stringify({url,channelId}).replaceAll('<', '\\u003c').replaceAll('>', '\\u003e').replaceAll('&', '\\u0026');
  const html = `<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>تجربة تشغيل القناة</title>
<style nonce="${nonce}">body{background:#080b0d;color:#f3d57d;font:17px system-ui;max-width:900px;margin:30px auto;padding:18px}video{width:100%;max-height:65vh;background:#000;border-radius:18px}button{font:inherit;background:#e8bd52;color:#171307;border:0;border-radius:12px;padding:14px;margin:12px 0}p{line-height:1.8}textarea{width:100%;box-sizing:border-box;min-height:220px;background:#101719;color:#eee;border:1px solid #7b6737;border-radius:12px;padding:12px;direction:ltr;font:14px monospace}</style>
<h1>تجربة تشغيل القناة</h1><p>اضغط تشغيل لبدء تحميل خيار HTTPS من هاتفك. النتيجة تخص مشغل المتصفح؛ اختبار أندرويد مستقل.</p><video id="video" controls playsinline preload="none" referrerpolicy="no-referrer"></video>
<button id="play">تشغيل HTTPS</button><button id="stop">إيقاف</button><p id="status" role="status">جاهز. لم يبدأ تحميل الفيديو.</p><p>لو فشل التشغيل اضغط نسخ تقرير المشغّل وأرسله. التقرير لا يحتوي رابط البث.</p><button id="copy">نسخ تقرير المشغّل</button><textarea id="report" readonly aria-label="تقرير المشغّل"></textarea>
<script nonce="${nonce}">
'use strict';
const settings=${config};
const video=document.getElementById('video'),statusText=document.getElementById('status'),output=document.getElementById('report');
const report={test:'browser-https-1',channelId:settings.channelId,engine:'HTMLVideo',hlsCapability:video.canPlayType('application/vnd.apple.mpegurl'),events:[],playing:false,mediaErrorCode:null};
let active=false,startedAt=0,watchdog;
function update(event){report.events.push({event,elapsedMs:startedAt?Date.now()-startedAt:0});report.events=report.events.slice(-30);report.readyState=video.readyState;report.networkState=video.networkState;report.currentTime=Number.isFinite(video.currentTime)?Math.floor(video.currentTime):0;report.width=video.videoWidth;report.height=video.videoHeight;output.value=JSON.stringify(report,null,2);}
for(const event of ['loadstart','loadedmetadata','canplay','playing','waiting','stalled','ended'])video.addEventListener(event,()=>{if(!active)return;if(event==='playing'){report.playing=true;clearTimeout(watchdog);statusText.textContent='الفيديو بدأ التشغيل في المتصفح.';}update(event);});
video.addEventListener('error',()=>{if(!active)return;clearTimeout(watchdog);report.mediaErrorCode=video.error?video.error.code:null;report.playing=false;statusText.textContent=report.mediaErrorCode===4?'المصدر أو الصيغة غير مناسبين لمشغل المتصفح. هذا لا يحدد وحده سبب المشكلة أو نتيجة مشغل أندرويد.':'تعذر تحميل أو تشغيل الفيديو. انسخ تقرير المشغّل.';update('error');});
document.getElementById('play').onclick=async()=>{clearTimeout(watchdog);active=true;startedAt=Date.now();report.playing=false;report.mediaErrorCode=null;report.playRequestError=null;report.events=[];video.src=settings.url;video.load();statusText.textContent='جارٍ تحميل الفيديو…';update('requested');watchdog=setTimeout(()=>{if(active&&!report.playing){statusText.textContent='لم يبدأ التشغيل خلال 20 ثانية. انسخ التقرير.';update('timeout');}},20000);try{await video.play();}catch(error){if(!active)return;report.playRequestError=['NotAllowedError','NotSupportedError','AbortError'].includes(error.name)?error.name:'PLAY_REQUEST_FAILED';statusText.textContent='لم يبدأ التشغيل. اضغط زر الفيديو أو انسخ التقرير.';update('playRejected');}};
document.getElementById('stop').onclick=()=>{active=false;clearTimeout(watchdog);video.pause();video.removeAttribute('src');video.load();report.playing=false;update('stopped');statusText.textContent='تم إيقاف الفيديو.';};
document.getElementById('copy').onclick=async()=>{try{await navigator.clipboard.writeText(output.value);statusText.textContent='تم نسخ تقرير المشغّل.';}catch{output.focus();output.select();statusText.textContent='التقرير محدد؛ انسخه من مربع النص.';}};
window.addEventListener('pagehide',()=>{active=false;clearTimeout(watchdog);video.pause();video.removeAttribute('src');video.load();});
update('ready');
</script></html>`;
  return new Response(html,{headers:{'Content-Type':'text/html;charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer',
    'Content-Security-Policy':"default-src 'none'; script-src 'nonce-"+nonce+"'; style-src 'nonce-"+nonce+"'; media-src https: blob:; connect-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"}});
}

function readCatalog(text, headerTime, responseMask, parse = parseCatalog) {
  const trimmed = text.replace(/^\uFEFF/, "").trim();
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) return parse(trimmed, false);
  const unreadable = (state, message) => ({ state, message, entries: [], responseDecoded: false });
  // أزل فقط محرف السطر الجديد الختامي؛ واحتفظ بالمسافات داخل إعداد التنسيق.
  const prefix = typeof responseMask === "string" ? responseMask.replace(/\r?\n$/, "") : "";
  if (!prefix) return unreadable("CONFIG_MISSING", "وصل رد مرمّز. أضف Secret باسم RESPONSE_MASK بقيمة إعداد القراءة المحفوظ عندك.");
  if (!/^[\x20-\x7e]{16}$/.test(prefix)) return unreadable("CONFIG_INVALID", "إعداد RESPONSE_MASK يجب أن يكون 16 حرف ASCII قابلًا للطباعة، من ملف إعداد القراءة نفسه.");
  const time = (headerTime || "").trim();
  if (!/^\d{10,13}$/.test(time)) return unreadable("TIME_INVALID", "ترويسة t غائبة أو غير صالحة؛ لم يُستخدم توقيت تخميني.");
  const envelope = trimmed.replace(/[\r\n]/g, "");
  if (!envelope || envelope.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(envelope))
    return unreadable("UNREADABLE", "الرد ليس Base64 مكتملًا ولا JSON مباشرًا.");
  try {
    const binary = atob(envelope);
    const mask = new TextEncoder().encode(prefix + time);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i) ^ mask[i % mask.length];
    const plain = new TextDecoder("utf-8", { fatal: true }).decode(bytes).trim();
    if (!plain.startsWith("{") && !plain.startsWith("["))
      return unreadable("DECODE_FAILED", "لم ينتج JSON من إعداد القراءة والتوقيت الواردين؛ لم تُستخرج أرقام.");
    return parse(plain, true);
  } catch {
    return unreadable("DECODE_FAILED", "تعذرت قراءة الرد بإعداد القراءة والتوقيت الواردين؛ لم تُستخرج أرقام.");
  }
}

async function readLimited(stream, limit) {
  if (!stream) return { bytes: new Uint8Array(), truncated: false };
  const reader = stream.getReader();
  const chunks = [];
  let length = 0;
  let truncated = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      const available = limit - length;
      if (value.byteLength > available) {
        chunks.push(value.subarray(0, available));
        length += available;
        truncated = true;
        await reader.cancel().catch(() => {});
        break;
      }
      chunks.push(value);
      length += value.byteLength;
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return { bytes, truncated };
}

export default {
  async fetch(request, env, saveCatalog, nativePlayback = false, channelSelector = null) {
    if (new URL(request.url).pathname !== "/api/internal/catalog-check") return respond({ message: "المسار غير موجود." }, 404);
    if (request.method === "GET") {
      return new Response(PAGE, {
        headers: {
          "Content-Type": "text/html; charset=utf-8",
          "Cache-Control": "no-store",
          "X-Content-Type-Options": "nosniff",
          "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
          "Referrer-Policy": "no-referrer",
        },
      });
    }
    if (request.method !== "POST") return respond({ message: "استخدم زر إرسال الطلب." }, 405);
    const secret = env.PROBE_KEY;
    if (typeof secret !== "string" || secret.length < 24 || secret.length > 256) {
      return respond({ message: "أضف Secret باسم PROBE_KEY بطول من 24 إلى 256 حرفًا، ثم اضغط Deploy." }, 503);
    }
    if (!(request.headers.get("Content-Type") || "").startsWith("application/x-www-form-urlencoded")) {
      return respond({ message: "أرسل الطلب من نموذج صفحة الاختبار." }, 415);
    }
    const formBody = await readLimited(request.body, MAX_FORM_BYTES);
    if (formBody.truncated) return respond({ message: "حجم النموذج أكبر من المطلوب." }, 413);
    const form = new URLSearchParams(new TextDecoder().decode(formBody.bytes));
    const key = form.get("probe_key") || "";
    if (!sameKey(key, secret)) return respond({ message: "مفتاح التجربة غير صحيح." }, 401);
    const category = (form.get("category_id") || "").trim();
    if (category && !validId(category)) return respond({ message: "رقم القسم غير صالح." }, 400);
    const channel = (form.get("channel_id") || "").trim();
    if (channel && !validId(channel)) return respond({ message: "رقم القناة غير صالح." }, 400);
    const videoTest = form.get("playback_video") === "yes";
    if (nativePlayback && (!channel || videoTest || form.get('publish') === 'yes' || category))
      return respond({ ok: false, error: 'اختبار تشغيل غير صالح.' }, 400);
    if (videoTest && !channel) return respond({ message: "اكتب رقم القناة لتجربة تشغيلها." }, 400);
    const publish = form.get("publish") === "yes";
    if (channel && (publish || category)) return respond({ message: "فحص التشغيل منفصل: اترك القسم فارغًا وأزل علامة الحفظ." }, 400);
    if (publish && (typeof env.CATALOG_SYNC_KEY !== "string" || env.CATALOG_SYNC_KEY.length < 32 || env.CATALOG_SYNC_KEY.length > 256))
      return respond({ message: "أضف Secret باسم CATALOG_SYNC_KEY بطول من 32 إلى 256 حرفًا في مشروع العطا الأساسي." }, 503);
    let sourceURL = channel ? SOURCE_URL.replace(/categories$/, "channel/") + channel : category ? SOURCE_URL + "/" + category + "/channels" : SOURCE_URL;

    const started = Date.now();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20_000);
    let playbackHTTP = null;
    let playbackStage = 'PLAYBACK_API_FETCH';
    try {
      let discoveredCategory = null;
      if (publish) {
        // ابحث بالاسم في كل مرة؛ ولا تثق برقم قسم محفوظ سابقًا.
        const sections = await fetch(SOURCE_URL, {
          method: "GET", headers: { Accept: "application/json", "User-Agent": "okhttp/4.12.0" },
          redirect: "manual", signal: controller.signal,
        });
        const sectionBody = await readLimited(sections.body, MAX_RESPONSE_BYTES);
        const decoded = sections.ok && !sectionBody.truncated
          ? readCatalog(new TextDecoder().decode(sectionBody.bytes), sections.headers.get("t"), env.RESPONSE_MASK) : null;
        const matches = decoded?.state === "LIST" ? decoded.entries.filter(entry =>
          entry.name.replace(/\s+/g, "").toLowerCase() === "beinsports(1080p)") : [];
        if (matches.length !== 1) return respond({
          state: "CATEGORY_UNRESOLVED", sourceHTTP: sections.status,
          message: "لم يُحدد قسم beIN SPORTS (1080P) بشكل فريد؛ لم يُحفظ أي تحديث.",
          published: false, publishResult: null,
        });
        discoveredCategory = matches[0];
        sourceURL = SOURCE_URL + "/" + discoveredCategory.id + "/channels";
      }
      let response = await fetch(sourceURL, {
        method: "GET",
        headers: { Accept: "application/json", "User-Agent": "okhttp/4.12.0" },
        redirect: "manual",
        signal: controller.signal,
      });
      playbackHTTP = response.status;
      playbackStage = 'PLAYBACK_API_BODY';
      let body = await readLimited(response.body, MAX_RESPONSE_BYTES);
      let text = new TextDecoder().decode(body.bytes).replace(/^\uFEFF/, "").trim();
      let effectiveChannel = channel;
      let idRefreshed = false;
      // قد يعني خطأ 404 أن معرّف المصدر انتهت صلاحيته. حدّث مرة واحدة باستخدام تسميات الفهرس المطابقة تمامًا؛
      // لا تخمّن المعرّفات أو تغيّر نقاط النهاية أو تعاود طلب المعرّف نفسه.
      if (nativePlayback && response.status === 404 &&
          !/(?:error\s*code\s*:?|error\s+)\s*(10\d{2}|11\d{2})\b/i.test(text) &&
          typeof channelSelector?.name === 'string' && channelSelector.name.length <= 120 &&
          typeof channelSelector?.category === 'string' && channelSelector.category.length <= 120) {
        const canonical = value => value.replace(/\s+/g, '').toLowerCase();
        // قد تحتوي تسميات عرض الأقسام على أقواس معكوسة أو علامات اتجاه النص.
        // احتفظ بالحروف والأرقام والجودة؛ وتجاهل محارف العرض فقط.
        const categoryKey = value => value.normalize('NFKC').replace(/[\s\p{Cf}()[\]{}]/gu, '').toLowerCase();
        const categoryName = categoryKey(channelSelector.category), channelName = canonical(channelSelector.name);
        if (categoryName && channelName) {
          const readList = async (url, stage) => {
            playbackStage = stage;
            const result = await fetch(url, { method: 'GET',
              headers: { Accept: 'application/json', 'User-Agent': 'okhttp/4.12.0' },
              redirect: 'manual', signal: controller.signal });
            playbackHTTP = result.status;
            const bytes = await readLimited(result.body, MAX_RESPONSE_BYTES);
            const list = result.ok && !bytes.truncated
              ? readCatalog(new TextDecoder().decode(bytes.bytes), result.headers.get('t'), env.RESPONSE_MASK) : null;
            return { http: result.status, entries: list?.state === 'LIST' ? list.entries : null };
          };
          const refreshFailure = (stage, code, http) => respond({ ok: false, stage, code,
            sourceHTTP: http, initialSourceHTTP: 404,
            error: `تعذر تحديث رقم القناة من المصدر (HTTP ${http}؛ ${stage}؛ ${code}).` }, 502);
          const sections = await readList(SOURCE_URL, 'CHANNEL_REFRESH_CATEGORIES');
          if (!sections.entries) return refreshFailure('CHANNEL_REFRESH_CATEGORIES', 'REFRESH_LIST_UNAVAILABLE', sections.http);
          const boundCategory = validId(channelSelector.categoryId);
          const byId = boundCategory ? sections.entries.filter(entry => entry.id === boundCategory) : [];
          const section = byId.length === 1 ? byId : sections.entries.filter(entry => categoryKey(entry.name) === categoryName);
          if (section.length !== 1) return refreshFailure('CHANNEL_REFRESH_CATEGORY', section.length ? 'CATEGORY_AMBIGUOUS' : 'CATEGORY_NOT_FOUND', sections.http);
          const channels = await readList(SOURCE_URL + '/' + section[0].id + '/channels', 'CHANNEL_REFRESH_CHANNELS');
          if (!channels.entries) return refreshFailure('CHANNEL_REFRESH_CHANNELS', 'REFRESH_LIST_UNAVAILABLE', channels.http);
          const matches = channels.entries.filter(entry => canonical(entry.name) === channelName);
          if (matches.length !== 1) return refreshFailure('CHANNEL_REFRESH_MATCH', matches.length ? 'CHANNEL_AMBIGUOUS' : 'CHANNEL_NOT_FOUND', channels.http);
          if (matches[0].id === channel) return respond({ ok: false, code: 'FRESH_ID_UNCHANGED',
            stage: 'PLAYBACK_API_HTTP', sourceHTTP: 404, idRefreshed: false,
            error: 'المصدر أعاد HTTP 404، ورقم القناة في قائمته الحالية لم يتغير (FRESH_ID_UNCHANGED).' }, 502);
          effectiveChannel = matches[0].id;
          sourceURL = SOURCE_URL.replace(/categories$/, 'channel/') + effectiveChannel;
          playbackStage = 'PLAYBACK_API_RETRY'; playbackHTTP = null;
          response = await fetch(sourceURL, { method: 'GET',
            headers: { Accept: 'application/json', 'User-Agent': 'okhttp/4.12.0' },
            redirect: 'manual', signal: controller.signal });
          playbackHTTP = response.status; playbackStage = 'PLAYBACK_API_BODY';
          body = await readLimited(response.body, MAX_RESPONSE_BYTES);
          text = new TextDecoder().decode(body.bytes).replace(/^\uFEFF/, '').trim();
          idRefreshed = true;
        }
      }
      let isJSON = false;
      let listRows = null;
      let declaresFailure = false;
      if (!body.truncated) {
        try {
          const parsed = JSON.parse(text);
          isJSON = true;
          const rows = Array.isArray(parsed) ? parsed : parsed && Array.isArray(parsed.data) ? parsed.data : null;
          if (rows) listRows = rows.length;
          declaresFailure = Boolean(parsed && !Array.isArray(parsed) &&
            (parsed.success === false || (parsed.error !== undefined && parsed.error !== null && parsed.error !== false && parsed.error !== "")));
        } catch { /* محتوى مشفّر أو من نوع آخر: لا يوجد هنا مفتاح لفك الترميز. */ }
      }
      let message;
      if (response.status >= 300 && response.status < 400) {
        message = "وصل تحويل HTTP. هذا الاختبار لا يتبع التحويل تلقائيًا.";
      } else if (!response.ok) {
        message = "وصل رد HTTP غير ناجح. كود الحالة وحده لا يحدد سبب الرفض.";
      } else if (body.truncated) {
        message = "وصل رد، وتجاوز حد القراءة 512 كيلوبايت. لم تُفحص القائمة.";
      } else if (isJSON && listRows !== null && !declaresFailure) {
        message = "وصل رد HTTP ناجح ومصفوفة JSON. صلاحية أرقام السجلات لم تُفحص ولم تُحفظ.";
      } else {
        message = "وصل رد HTTP ناجح؛ لم تُثبت قائمة صالحة بعد. قد يحتاج الرد معالجة صيغته.";
      }
      const time = (response.headers.get("t") || "").trim();
      let candidate = null;
      let resolvedSources = [];
      const playbackParser = (plain, decoded) => {
        const metadata = inspectPlayback(plain, decoded);
        if (nativePlayback && metadata.state === 'PLAYBACK_METADATA') {
          const root = JSON.parse(plain);
          resolvedSources = nativeSources(Array.isArray(root) ? root : root.data);
        }
        if (videoTest && metadata.state === "PLAYBACK_METADATA") {
          const root = JSON.parse(plain), rows = Array.isArray(root) ? root : root.data;
          candidate = chooseDirectHttps(rows);
        }
        return metadata;
      };
      const catalog = response.ok && !body.truncated ? readCatalog(text, response.headers.get("t"), env.RESPONSE_MASK, channel ? playbackParser : parseCatalog) : {
        state: body.truncated ? "TOO_LARGE" : "HTTP_ERROR", message, entries: [], responseDecoded: false,
      };
      if (nativePlayback) {
        if (catalog.state !== 'PLAYBACK_METADATA') {
          const stage = !response.ok ? 'PLAYBACK_API_HTTP' : body.truncated ? 'PLAYBACK_API_BODY' : 'PLAYBACK_API_DECODE';
          const platform = !response.ok && text.match(/(?:error\s*code\s*:?|error\s+)\s*(10\d{2}|11\d{2})\b/i);
          const platformCode = platform ? 'CLOUDFLARE_' + platform[1] : null;
          const code = !response.ok ? 'SOURCE_HTTP_ERROR' : catalog.state;
          // يعرض الإصدار 192 الخطأ بالفعل؛ أضف إليه الحالة والمرحلة الآمنتين أيضًا.
          // هذا إخفاق في حل المصدر قبل تحميل الهاتف أي فيديو.
          return respond({ ok: false,
            error: `تعذر جلب بيانات تشغيل القناة (HTTP ${response.status}؛ ${stage}${platformCode ? '؛ ' + platformCode : ''}${idRefreshed ? '؛ ID_REFRESHED' : ''}؛ ${code}).`,
            code, stage, platformCode, sourceHTTP: response.status, idRefreshed }, 502);
        }
        if (!resolvedSources.length) return respond({ ok: false, code: 'NO_SUPPORTED_NATIVE_SOURCE',
          error: 'لم يصل رابط مباشر بإعدادات يدعمها اختبار العطا. قد يحتاج الرابط معالجة إضافية.', sourceHTTP: response.status }, 422);
        return respond({ ok: true, channelId: effectiveChannel, sourceHTTP: response.status, idRefreshed,
          sources: resolvedSources, skipped: catalog.streamCount - resolvedSources.length });
      }
      if (videoTest && candidate) return renderPlaybackTest(candidate, channel);
      if (videoTest && catalog.state === "PLAYBACK_METADATA") return respond({
        state: "NO_DIRECT_HTTPS", sourceHTTP: response.status,
        message: "لم يوجد خيار HTTPS مباشر بلا إعدادات إضافية. لم تُرسل روابط HTTP أو بيانات ترخيص للمشغّل.",
        channelId: channel, streams: catalog.streams, published: false,
      });
      let publishResult = null;
      if (publish && catalog.state === "LIST" && /^\d{10}$/.test(time)) {
        publishResult = { ok: false, status: null, stage: "CONNECT", errorCode: null };
        try {
          const saved = await saveCatalog({
            categoryId: discoveredCategory.id, categoryName: discoveredCategory.name, sourceTime: time,
            entries: catalog.entries.map(({ id, name }) => ({ id, name })),
          });
          // سجّل بيانات HTTP الوصفية قبل التحليل كي يظل الرد غير الصالح بصيغة JSON قابلًا للتشخيص.
          publishResult.status = saved.status;
          publishResult.stage = "READ_RESPONSE";
          const rawType = (saved.headers.get("Content-Type") || "").split(";")[0].trim().toLowerCase();
          publishResult.contentType = ["application/json", "text/plain", "text/html"].includes(rawType) ? rawType : "other";
          const report = await readLimited(saved.body, 4096);
          publishResult.bytesRead = report.bytes.length;
          publishResult.truncated = report.truncated;
          const reportText = new TextDecoder().decode(report.bytes);
          let result = null;
          if (!report.truncated) {
            try { result = JSON.parse(reportText); } catch { /* احتفظ بالحالة، ولا تُظهر نصًا عشوائيًا من الرد. */ }
          }
          const validReport = result !== null && typeof result === "object" && !Array.isArray(result);
          const allowed = new Set(["SYNC_NOT_CONFIGURED", "UNAUTHORIZED", "METHOD_NOT_ALLOWED", "JSON_REQUIRED",
            "INVALID_BODY", "BODY_TOO_LARGE", "INVALID_SOURCE_METADATA", "INVALID_LIST", "INVALID_ENTRY",
            "DUPLICATE_ENTRY", "CATALOG_UNAVAILABLE", "NO_MATCHING_CHANNELS", "DUPLICATE_CHANNEL_ID", "SAVE_FAILED"]);
          publishResult.stage = "RESPONSE_RECEIVED";
          publishResult.ok = saved.ok && validReport && result.ok === true;
          if (validReport) {
            if (result.ignored === true) publishResult.ignored = true;
            for (const field of ["changed", "matched", "skipped", "revision"])
              if (Number.isSafeInteger(result[field]) && result[field] >= 0) publishResult[field] = result[field];
          }
          if (!publishResult.ok) {
            const platform = reportText.match(/(?:error\s*code\s*:?|error\s+)\s*(10\d{2}|11\d{2})\b/i);
            publishResult.errorCode = report.truncated ? "RESPONSE_TOO_LARGE" :
              validReport && allowed.has(result.error) ? result.error :
              platform ? "CLOUDFLARE_" + platform[1] : validReport ? "UNEXPECTED_JSON" : "NON_JSON_RESPONSE";
            publishResult.error = "لم يتأكد حفظ القائمة؛ راجع status وerrorCode لمعرفة نتيجة الاتصال بالعطا.";
          }
        } catch {
          publishResult.errorCode = controller.signal.aborted ? "TIMEOUT" :
            publishResult.status === null ? "NETWORK_ERROR" : "RESPONSE_READ_FAILED";
          publishResult.error = "تعذر تأكيد الحفظ. تفاصيل المرحلة وحالة HTTP ظاهرة دون عرض المفاتيح.";
        }
      } else if (publish) {
        publishResult = { ok: false, error: "لم تُرسل قائمة؛ لم تصل قنوات صالحة مع توقيت المصدر المطلوب." };
      }
      return respond({
        message: publishResult?.ignored ? "تجاهل العطا القائمة لأنها أقدم من آخر تحديث محفوظ." : publishResult?.ok ? "قبل العطا حفظ تحديث الأرقام. راجع changed لعدد الأرقام التي تغيرت." : publishResult ? publishResult.error : catalog.message,
        diagnosticVersion: channel ? "playback-metadata-1" : "main-catalog-check-1",
        sourceURL,
        categoryId: discoveredCategory?.id || category || null,
        sourceHTTP: response.status,
        contentType: (response.headers.get("Content-Type") || "").split(";")[0].slice(0, 80),
        bytesRead: body.bytes.length,
        truncated: body.truncated,
        elapsedMs: Date.now() - started,
        hasTimeHeader: response.headers.has("t"),
        t: /^\d{10,13}$/.test(time) ? time : null,
        isDirectJSON: isJSON,
        arrayRows: catalog.state === "LIST" || catalog.state === "EMPTY" ? catalog.entries.length : null,
        declaresFailure: Boolean(declaresFailure || catalog.declaresFailure),
        state: catalog.state,
        responseDecoded: catalog.responseDecoded,
        entries: catalog.entries,
        ...(channel ? { channelId: channel, streamCount: catalog.streamCount ?? null, streams: catalog.streams || [] } : {}),
        published: publishResult?.ok === true && publishResult?.ignored !== true,
        publishResult,
      });
    } catch (error) {
      if (nativePlayback) {
        const code = controller.signal.aborted ? 'SOURCE_TIMEOUT' : 'SOURCE_NETWORK_ERROR';
        return respond({ ok: false,
          error: `تعذر استلام بيانات تشغيل القناة (${playbackHTTP === null ? 'لم يصل رد HTTP' : 'HTTP ' + playbackHTTP}؛ ${playbackStage}؛ ${code}).`,
          code, stage: playbackStage, sourceHTTP: playbackHTTP, elapsedMs: Date.now() - started }, 502);
      }
      return respond({
        message: controller.signal.aborted
          ? "انتهت مهلة الاختبار بعد 20 ثانية؛ لم تُثبت نتيجة HTTP من المصدر."
          : "حدث خطأ اتصال أثناء الاختبار؛ لم تُثبت نتيجة HTTP من المصدر.",
        sourceHTTP: null,
        errorType: controller.signal.aborted ? "TIMEOUT" : "NETWORK_ERROR",
        elapsedMs: Date.now() - started,
      }, 502);
    } finally {
      clearTimeout(timer);
    }
  },
};

