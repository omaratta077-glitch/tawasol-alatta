package com.alatta.nativeapp

import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun YacineCatalogProbeDialog(channels: List<FootballChannel>, owner: String, token: String,
    onCatalog: (FootballCatalog) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<YacineCatalogProbe.Result?>(null) }
    var kind by remember { mutableStateOf(YacineCatalogProbe.Kind.CATEGORIES) }
    var section by remember { mutableStateOf("") }
    var sectionId by remember { mutableStateOf("") }
    var bindEntry by remember { mutableStateOf<YacineCatalogProbe.Entry?>(null) }
    var bindBusy by remember { mutableStateOf(false) }
    var bindMessage by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(CatalogFormatSettings.configured(context)) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsBusy by remember { mutableStateOf(false) }
    var maskInput by remember { mutableStateOf("") }
    var settingsMessage by remember { mutableStateOf("") }
    fun load(nextKind: YacineCatalogProbe.Kind, entry: YacineCatalogProbe.Entry? = null) {
        if (busy) return
        kind = nextKind
        section = entry?.name.orEmpty()
        sectionId = entry?.id.orEmpty()
        bindMessage = ""
        result = null
        busy = true
        scope.launch {
            try {
                val mask = withContext(Dispatchers.IO) { CatalogFormatSettings.read(context) }
                result = YacineCatalogProbe.fetch(nextKind, entry?.id, mask?.toByteArray(Charsets.US_ASCII))
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                result = YacineCatalogProbe.Result(YacineCatalogProbe.State.INVALID, "",
                    "تعذر تجهيز تجربة القائمة.")
            } finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!bindBusy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = PremiumBackground) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تجربة قائمة ياسين من الهاتف", color = PremiumGoldLight, fontSize = 19.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !bindBusy, onClick = onDismiss) { Text("إغلاق") }
                    TextButton(enabled = result != null && !busy, onClick = {
                        result?.let {
                            clipboard.setText(AnnotatedString(it.report()))
                            Toast.makeText(context, "تم نسخ نتيجة التجربة", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("نسخ النتيجة") }
                }
                Text("معاينة القائمة لا تغيّر قنواتك. اختر ربط القناة المحفوظة لتفعيل تحديث رقمها تلقائيًا.",
                    color = PremiumMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && !settingsBusy, onClick = { load(YacineCatalogProbe.Kind.CATEGORIES) }) {
                        Text("جلب الأقسام")
                    }
                    OutlinedButton(enabled = !busy && !settingsBusy, onClick = {
                        maskInput = ""; settingsMessage = ""; settingsOpen = true
                    }) { Text(if (configured) "إعداد القراءة" else "ضبط قراءة الرد") }
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("جارٍ طلب القائمة…", color = PremiumMuted, fontSize = 13.sp)
                }
                if (section.isNotBlank()) Text(section, color = PremiumGoldLight, fontSize = 15.sp)
                if (bindMessage.isNotBlank()) Text(bindMessage, color = PremiumGoldLight, fontSize = 13.sp)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("الردود المرمّزة تستخدم إعداد القراءة المحلي. نسخ النتيجة يضم الرد وتوقيته حتى 16 ألف حرف، دون الإعداد.",
                            color = PremiumMuted, fontSize = 12.sp)
                        Text("المصدر: " + YacineCatalogProbe.BASE.removePrefix("https://").substringBefore('/') + " • HTTPS",
                            color = PremiumMuted, fontSize = 12.sp)
                    }
                    result?.let { current ->
                        item {
                            FootballPanel {
                                Text(if (kind == YacineCatalogProbe.Kind.CHANNELS && current.state == YacineCatalogProbe.State.LIST)
                                    "وصلت قائمة قابلة للقراءة. يمكنك نسخ الرقم أو ربط قناة محفوظة." else current.message,
                                    color = if (current.state in setOf(YacineCatalogProbe.State.LIST,
                                    YacineCatalogProbe.State.EMPTY)) PremiumGoldLight else Color(0xFFFFB4A9), fontSize = 14.sp)
                                Text("GET /api/" + current.path, color = PremiumMuted, fontSize = 12.sp)
                                current.code?.let { Text("HTTP " + it, color = Color.White, fontSize = 13.sp) }
                                if (current.responseDecoded) Text("تم فك ترميز الرد إلى JSON.",
                                    color = PremiumGoldLight, fontSize = 12.sp)
                                if (current.serverMessage.isNotBlank()) Text("رسالة الجهة المجيبة: " + current.serverMessage,
                                    color = Color.White, fontSize = 13.sp)
                                Text("نوع الرد: " + current.contentType.ifBlank { "غير محدد" } +
                                    " • المقروء: " + current.bytes + " بايت • " + current.elapsedMs + " ms",
                                    color = PremiumMuted, fontSize = 12.sp)
                                Text("عدد السجلات المقروءة: " + current.entries.size, color = PremiumMuted, fontSize = 12.sp)
                                Text("ترويسة t: " + current.timeHeader.ifBlank {
                                    if (current.hasTimeHeader) "موجودة بصيغة غير رقمية؛ لم تُنسخ" else "غير موجودة"
                                }, color = PremiumMuted, fontSize = 12.sp)
                                if (current.deviceTimeUsed.isNotEmpty()) Text("توقيت الجهاز البديل: " + current.deviceTimeUsed,
                                    color = PremiumMuted, fontSize = 12.sp)
                                if (current.responseText.isNotEmpty()) Text(
                                    if (current.responseTextTruncated) "التقرير يضم مقتطفًا غير مكتمل من الرد."
                                    else "نص الرد كامل مرفق عند الضغط على نسخ النتيجة.",
                                    color = PremiumMuted, fontSize = 12.sp)
                            }
                        }
                        items(current.entries, key = { it.id }) { entry ->
                            FootballPanel {
                                Text(entry.name, color = Color.White, fontSize = 15.sp)
                                Text((if (kind == YacineCatalogProbe.Kind.CATEGORIES) "رقم القسم: " else "رقم القناة: ") + entry.id,
                                    color = PremiumGoldLight, fontSize = 13.sp)
                                if (kind == YacineCatalogProbe.Kind.CATEGORIES) {
                                    TextButton(enabled = !busy, onClick = { load(YacineCatalogProbe.Kind.CHANNELS, entry) }) {
                                        Text("جلب قنوات القسم")
                                    }
                                    if (entry.childCount == null || entry.childCount > 0) TextButton(enabled = !busy,
                                        onClick = { load(YacineCatalogProbe.Kind.CATEGORIES, entry) }) {
                                        Text("عرض الأقسام الفرعية")
                                    }
                                } else {
                                    if (channels.any { it.format == "ytv" }) OutlinedButton(enabled = !busy && !bindBusy, onClick = {
                                        bindEntry = entry; bindMessage = ""
                                    }) { Text("ربط قناة محفوظة") }
                                    TextButton(onClick = {
                                        clipboard.setText(AnnotatedString(entry.id))
                                        Toast.makeText(context, "تم نسخ رقم القناة", Toast.LENGTH_SHORT).show()
                                    }) { Text("نسخ الرقم") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    bindEntry?.let { entry -> AlertDialog(onDismissRequest = { if (!bindBusy) bindEntry = null },
        title = { Text("ربط ${entry.name}") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اختر قناتك المحفوظة. يتغير رقمها فقط، ويُتابع الاسم نفسه في القسم $sectionId. لا تُضاف قنوات أخرى.", fontSize = 13.sp)
                channels.filter { it.format == "ytv" }.forEach { channel ->
                    OutlinedButton(enabled = !bindBusy, onClick = {
                        bindBusy = true
                        scope.launch {
                            try {
                                val outcome = CatalogAutoUpdater.bind(context, token, owner, channel.id, sectionId, entry)
                                outcome.catalog?.let(onCatalog)
                                bindMessage = outcome.message
                                if (outcome.linked) bindEntry = null
                            } finally { bindBusy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("ربط وتحديث: ${channel.name}") }
                }
                if (bindMessage.isNotBlank()) Text(bindMessage, fontSize = 12.sp)
                if (bindBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = {}, dismissButton = {
            TextButton(enabled = !bindBusy, onClick = { bindEntry = null }) { Text("رجوع") }
        }) }
    if (settingsOpen) AlertDialog(onDismissRequest = {
        if (!settingsBusy) { maskInput = ""; settingsOpen = false }
    }, title = { Text("إعداد قراءة القائمة") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (configured) "الإعداد محفوظ على هذا الهاتف. يمكنك استبداله أو حذفه."
                else "الصق إعداد القراءة الخاص بك مرة واحدة على هذا الهاتف.", fontSize = 13.sp)
            OutlinedTextField(value = maskInput, onValueChange = { maskInput = it.take(128); settingsMessage = "" },
                enabled = !settingsBusy, singleLine = true, label = { Text("إعداد القراءة") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text("يُحفظ على هذا الهاتف، ولا يظهر ضمن نسخ النتيجة.", fontSize = 12.sp)
            if (settingsMessage.isNotBlank()) Text(settingsMessage, fontSize = 12.sp)
            if (settingsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (configured) TextButton(enabled = !settingsBusy, onClick = {
                settingsBusy = true
                scope.launch {
                    val removed = withContext(Dispatchers.IO) { CatalogFormatSettings.clear(context) }
                    if (removed) { configured = false; maskInput = ""; result = null; settingsMessage = "تم حذف الإعداد" }
                    else settingsMessage = "تعذر حذف الإعداد"
                    settingsBusy = false
                }
            }) { Text("حذف الإعداد من الهاتف") }
        }
    }, confirmButton = { TextButton(enabled = !settingsBusy, onClick = {
        val value = maskInput.trim()
        if (!CatalogFormatSettings.valid(value)) settingsMessage = "قيمة الإعداد غير صحيحة؛ الصقها كاملة."
        else {
            settingsBusy = true
            scope.launch {
                val saved = withContext(Dispatchers.IO) { CatalogFormatSettings.save(context, value) }
                if (saved) { configured = true; maskInput = ""; settingsOpen = false; result = null }
                else settingsMessage = "تعذر حفظ الإعداد على الهاتف"
                settingsBusy = false
            }
        }
    }) { Text("حفظ") } }, dismissButton = { TextButton(enabled = !settingsBusy, onClick = {
        maskInput = ""; settingsOpen = false
    }) { Text("رجوع") } })
}
