package com.alatta.nativeapp

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Composable
internal fun AdhanVoiceSelector(settings: PrayerSettings, onSelect: (AdhanVoice) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf(emptyList<AdhanVoice>()) }
    var downloading by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun refresh() { scope.launch {
        loading = true; error = ""
        try { voices = AdhanVoices.refresh(context) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "تعذر تحديث تسجيلات الإدارة. الأصوات المحفوظة والقائمة الأساسية متاحة." }
        finally { loading = false }
    } }
    TextButton(onClick = { open = true }) { Text("اختيار المؤذن • الحرمين وكبار القراء", color = PremiumGoldLight) }
    if (open) {
        LaunchedEffect(Unit) {
            voices = withContext(Dispatchers.IO) { AdhanVoices.catalog(context) }
            refresh()
        }
        AlertDialog(onDismissRequest = { if (downloading.isBlank()) open = false },
            title = { Text("أصوات المؤذنين") }, text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("اختر تسجيلًا لتنزيله مرة واحدة، ثم يعمل الأذان به دون إنترنت. يمكنك تجربة الصوت بعد الاختيار.", fontSize = 13.sp)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error.isNotBlank()) Text(error, color = Color(0xFFD3A846), fontSize = 12.sp)
                    voices.forEach { voice ->
                        val selected = settings.soundUri.isBlank() && settings.voiceId == voice.id
                        val cached = AdhanVoices.downloaded(context, voice)
                        HorizontalDivider()
                        Text(voice.name, color = if (selected) PremiumGold else Color.Unspecified)
                        Text(listOf(voice.mosque, voice.source).filter { it.isNotBlank() }.joinToString(" • "), fontSize = 11.sp)
                        Text(if (cached) "محفوظ على الهاتف" else "حجم التنزيل: ${"%.1f".format(voice.size / 1048576f)} ميجابايت", fontSize = 11.sp)
                        if (voice.sourceUrl.isNotBlank()) TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(voice.sourceUrl))) }
                        }) { Text("مصدر التسجيل", fontSize = 11.sp) }
                        Button(enabled = downloading.isBlank(), onClick = { scope.launch {
                            downloading = voice.id; error = ""
                            try {
                                AdhanVoices.download(context, voice)
                                context.stopService(Intent(context, AdhanPlaybackService::class.java))
                                onSelect(voice); onMessage("تم اختيار ${voice.name} • التسجيل محفوظ دون إنترنت"); open = false
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "تعذر تنزيل التسجيل؛ لم يتغير المؤذن المختار" }
                            finally { downloading = "" }
                        } }) { Text(when { downloading == voice.id -> "جارٍ تنزيل التسجيل…"; selected -> "المؤذن المختار"; cached -> "اختيار هذا المؤذن"; else -> "تنزيل واختيار" }) }
                    }
                }
            }, confirmButton = { TextButton(enabled = !loading && downloading.isBlank(), onClick = { refresh() }) { Text("تحديث القائمة") } },
            dismissButton = { TextButton(enabled = downloading.isBlank(), onClick = { open = false }) { Text("رجوع") } })
    }
}

private suspend fun uploadAdminAdhan(context: android.content.Context, token: String, uri: Uri): JSONObject = withContext(Dispatchers.IO) {
    val temporary = File.createTempFile("adhan-upload-", ".audio", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output ->
            var size = 0
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; size += count
                require(size <= ADHAN_MAX_BYTES) { "الحد الأقصى للتسجيل 20 ميجابايت" }; output.write(buffer, 0, count) }
        } } ?: error("تعذر قراءة التسجيل")
        AdhanVoices.validateAudio(temporary.path)
        val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
        val path = "/api/media-upload?token=${URLEncoder.encode(token, "UTF-8")}&kind=adhan&name=adhan.audio"
        val conn = (URL(ATTA_API_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 120_000
            setRequestProperty("Content-Type", mime); setFixedLengthStreamingMode(temporary.length())
        }
        try {
            conn.outputStream.use { out -> temporary.inputStream().use { it.copyTo(out) } }
            val code = conn.responseCode
            val result = JSONObject((if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() })
            require(code in 200..299) { result.optString("error", "تعذر رفع التسجيل") }; result
        } finally { conn.disconnect() }
    } finally { temporary.delete() }
}

/** تستخدم واجهة المالك الحالية والمصادقة؛ لا تستطيع الحسابات العادية إضافة عناصر إلى القائمة. */
@Composable
internal fun AdminAdhanVoicesPanel(token: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var mosque by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Uri?>(null) }
    var uploaded by remember { mutableStateOf<JSONObject?>(null) }
    var voices by remember { mutableStateOf(emptyList<AdhanVoice>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<AdhanVoice?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { picked = uri; uploaded = null; message = "تم اختيار التسجيل" }
    }
    suspend fun load() { voices = AdhanVoices.refresh(context).filter { it.id.startsWith("admin-") } }
    OutlinedButton(onClick = { open = true }) { Text("إدارة أصوات المؤذنين", color = PremiumGoldLight) }
    if (open) {
        LaunchedEffect(Unit) { try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "تعذر تحميل القائمة؛ أعد فتحها عند عودة الاتصال" } }
        AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text("إدارة أصوات المؤذنين") }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("أضف اسم المؤذن وتسجيل الأذان ليظهر الاختيار لكل مستخدمي التطبيق. التسجيل حتى 20 ميجابايت و10 دقائق.", fontSize = 13.sp)
                OutlinedTextField(name, onValueChange = { name = it.take(80) }, label = { Text("اسم المؤذن") }, singleLine = true, enabled = !busy)
                OutlinedTextField(mosque, onValueChange = { mosque = it.take(120) }, label = { Text("المسجد أو وصف التسجيل (اختياري)") }, singleLine = true, enabled = !busy)
                OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("audio/*")) }) { Text(if (picked == null) "اختيار ملف الأذان" else "تغيير التسجيل المختار") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, fontSize = 12.sp)
                Button(enabled = !busy && name.trim().length >= 2 && picked != null, onClick = { scope.launch {
                    busy = true; message = "جارٍ رفع وحفظ التسجيل…"
                    try {
                        val audio = uploaded ?: uploadAdminAdhan(context, token, requireNotNull(picked)).also { uploaded = it }
                        withContext(Dispatchers.IO) { attaApi("/api/admin/adhan-voices", JSONObject().put("token", token)
                            .put("name", name.trim()).put("mosque", mosque.trim()).put("url", audio.getString("url"))) }
                        name = ""; mosque = ""; picked = null; uploaded = null
                        message = "تمت إضافة المؤذن للقائمة"; load()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "تعذر حفظ التسجيل؛ أعد المحاولة" }
                    finally { busy = false }
                } }) { Text("إضافة المؤذن للتطبيق") }
                voices.forEach { voice ->
                    HorizontalDivider(); Text(voice.name)
                    if (voice.mosque.isNotBlank()) Text(voice.mosque, fontSize = 12.sp)
                    TextButton(enabled = !busy, onClick = { remove = voice }) { Text("إزالة من القائمة") }
                }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("تم") } })
    }
    remove?.let { voice -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("إزالة ${voice.name}؟") },
        text = { Text("يتوقف ظهوره للاختيارات الجديدة. التسجيلات التي حفظها المستخدمون تظل تعمل على أجهزتهم.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { attaApi("/api/admin/adhan-voices/delete", JSONObject().put("token", token).put("id", voice.id)) }; remove = null; load() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "تعذرت إزالة التسجيل"; remove = null }
            finally { busy = false }
        } }) { Text("إزالة") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("رجوع") } }) }
}
