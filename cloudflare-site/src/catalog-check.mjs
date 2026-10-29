// Manual owner-triggered catalog check in the main application. No scheduler.
// Secrets: PROBE_KEY (24-256 characters), RESPONSE_MASK (private 16-character format setting).
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
<button type="submit">إرسال طلب واحد</button></form>
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

// Metadata only: never return stream URLs, header values, license values or raw JSON.
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
    if (typeof row.url === "string") { try { const u = new URL(row.url); scheme = ["http:", "https:"].includes(u.protocol) ? u.protocol : "other"; hasQuery = !!u.search; } catch { /* Opaque or transformed URL. */ } }
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

function readCatalog(text, headerTime, responseMask, parse = parseCatalog) {
  const trimmed = text.replace(/^\uFEFF/, "").trim();
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) return parse(trimmed, false);
  const unreadable = (state, message) => ({ state, message, entries: [], responseDecoded: false });
  // Only a trailing file newline is removed; spaces inside the format setting are preserved.
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
  async fetch(request, env, saveCatalog) {
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
    const publish = form.get("publish") === "yes";
    if (channel && (publish || category)) return respond({ message: "فحص التشغيل منفصل: اترك القسم فارغًا وأزل علامة الحفظ." }, 400);
    if (publish && (typeof env.CATALOG_SYNC_KEY !== "string" || env.CATALOG_SYNC_KEY.length < 32 || env.CATALOG_SYNC_KEY.length > 256))
      return respond({ message: "أضف Secret باسم CATALOG_SYNC_KEY بطول من 32 إلى 256 حرفًا في مشروع العطا الأساسي." }, 503);
    let sourceURL = channel ? SOURCE_URL.replace(/categories$/, "channel/") + channel : category ? SOURCE_URL + "/" + category + "/channels" : SOURCE_URL;

    const started = Date.now();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20_000);
    try {
      let discoveredCategory = null;
      if (publish) {
        // Resolve by name every time: never trust a remembered section number.
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
      const response = await fetch(sourceURL, {
        method: "GET",
        headers: { Accept: "application/json", "User-Agent": "okhttp/4.12.0" },
        redirect: "manual",
        signal: controller.signal,
      });
      const body = await readLimited(response.body, MAX_RESPONSE_BYTES);
      const text = new TextDecoder().decode(body.bytes).replace(/^\uFEFF/, "").trim();
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
        } catch { /* Encoded or other content: no decoding key is embedded here. */ }
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
      const catalog = response.ok && !body.truncated ? readCatalog(text, response.headers.get("t"), env.RESPONSE_MASK, channel ? inspectPlayback : parseCatalog) : {
        state: body.truncated ? "TOO_LARGE" : "HTTP_ERROR", message, entries: [], responseDecoded: false,
      };
      let publishResult = null;
      if (publish && catalog.state === "LIST" && /^\d{10}$/.test(time)) {
        publishResult = { ok: false, status: null, stage: "CONNECT", errorCode: null };
        try {
          const saved = await saveCatalog({
            categoryId: discoveredCategory.id, categoryName: discoveredCategory.name, sourceTime: time,
            entries: catalog.entries.map(({ id, name }) => ({ id, name })),
          });
          // Record HTTP metadata before parsing so a non-JSON response stays diagnosable.
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
            try { result = JSON.parse(reportText); } catch { /* Keep status, never echo arbitrary response text. */ }
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

