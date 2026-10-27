package com.alatta.nativeapp

import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun YacineCatalogProbeDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<YacineCatalogProbe.Result?>(null) }
    var kind by remember { mutableStateOf(YacineCatalogProbe.Kind.CATEGORIES) }
    var section by remember { mutableStateOf("") }
    fun load(nextKind: YacineCatalogProbe.Kind, entry: YacineCatalogProbe.Entry? = null) {
        if (busy) return
        kind = nextKind
        section = entry?.name.orEmpty()
        result = null
        busy = true
        scope.launch {
            try { result = YacineCatalogProbe.fetch(nextKind, entry?.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                result = YacineCatalogProbe.Result(YacineCatalogProbe.State.INVALID, "",
                    "تعذر تجهيز تجربة القائمة.")
            } finally { busy = false }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = PremiumBackground) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تجربة قائمة ياسين من الهاتف", color = PremiumGoldLight, fontSize = 19.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss) { Text("إغلاق") }
                    TextButton(enabled = result != null && !busy, onClick = {
                        result?.let {
                            clipboard.setText(AnnotatedString(it.report()))
                            Toast.makeText(context, "تم نسخ نتيجة التجربة", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("نسخ النتيجة") }
                }
                Text("قراءة تجريبية من إنترنت هذا الهاتف. لا تحفظ أرقامًا ولا تعدّل القنوات المسجّلة.",
                    color = PremiumMuted, fontSize = 13.sp)
                Text("هذه التجربة تقرأ JSON فقط؛ معالجة ردود ياسين المرمّزة غير مدمجة بعد.",
                    color = PremiumMuted, fontSize = 12.sp)
                Text("المصدر: deft.yacinelive.com • HTTPS", color = PremiumMuted, fontSize = 12.sp)
                Button(enabled = !busy, onClick = { load(YacineCatalogProbe.Kind.CATEGORIES) }) {
                    Text("جلب الأقسام")
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("جارٍ طلب القائمة…", color = PremiumMuted, fontSize = 13.sp)
                }
                if (section.isNotBlank()) Text(section, color = PremiumGoldLight, fontSize = 15.sp)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result?.let { current ->
                        item {
                            FootballPanel {
                                Text(current.message, color = if (current.state in setOf(YacineCatalogProbe.State.LIST,
                                    YacineCatalogProbe.State.EMPTY)) PremiumGoldLight else Color(0xFFFFB4A9), fontSize = 14.sp)
                                Text("GET /api/" + current.path, color = PremiumMuted, fontSize = 12.sp)
                                current.code?.let { Text("HTTP " + it, color = Color.White, fontSize = 13.sp) }
                                if (current.serverMessage.isNotBlank()) Text("رسالة الجهة المجيبة: " + current.serverMessage,
                                    color = Color.White, fontSize = 13.sp)
                                Text("نوع الرد: " + current.contentType.ifBlank { "غير محدد" } +
                                    " • المقروء: " + current.bytes + " بايت • " + current.elapsedMs + " ms",
                                    color = PremiumMuted, fontSize = 12.sp)
                                Text("عدد السجلات المقروءة: " + current.entries.size, color = PremiumMuted, fontSize = 12.sp)
                            }
                        }
                        items(current.entries, key = { it.id }) { entry ->
                            FootballPanel {
                                Text(entry.name, color = Color.White, fontSize = 15.sp)
                                Text("المعرّف: " + entry.id, color = PremiumGoldLight, fontSize = 13.sp)
                                if (kind == YacineCatalogProbe.Kind.CATEGORIES) {
                                    TextButton(enabled = !busy, onClick = { load(YacineCatalogProbe.Kind.CHANNELS, entry) }) {
                                        Text("جلب قنوات القسم")
                                    }
                                    TextButton(enabled = !busy, onClick = { load(YacineCatalogProbe.Kind.CATEGORIES, entry) }) {
                                        Text("عرض الأقسام الفرعية")
                                    }
                                } else {
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
}
