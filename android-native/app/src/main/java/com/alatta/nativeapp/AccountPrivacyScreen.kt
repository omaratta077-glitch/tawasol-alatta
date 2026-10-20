package com.alatta.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun AccountPrivacyScreen(
    username: String, busy: Boolean, notifications: Boolean, provider: String,
    deletionRequest: JSONObject?, onNotifications: (Boolean) -> Unit,
    onRequestDeletion: (String, String) -> Unit, onCancelDeletion: () -> Unit,
    onRefresh: () -> Unit, onWithdraw: () -> Unit, modifier: Modifier = Modifier
) {
    var document by remember { mutableStateOf<String?>(null) }
    var deleteDialog by remember { mutableStateOf(false) }
    var withdrawDialog by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("الخصوصية وحقوق الحساب", color = PremiumGoldLight, style = MaterialTheme.typography.titleLarge)
        LegalLinks { document = it }
        Text("نسخة السياسة: ${AttaPrivacy.VERSION}", color = PremiumMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("إشعارات الرسائل والمكالمات والتفاعلات", Modifier.weight(1f), color = Color.White)
            Switch(notifications, onCheckedChange = { if (!busy) onNotifications(it) }, enabled = !busy)
        }
        Text("المزود على هذا الهاتف: $provider. تُرسل الإشعارات عند وصول نشاط جديد؛ يمكنك إيقافها هنا أو من إعدادات النظام.", color = PremiumMuted)
        HorizontalDivider()
        Text("حذف الحساب والبيانات", color = PremiumGoldLight)
        Text("يمكنك إرسال طلب حذف حسابك والمنشورات والوسائط والرسائل والروابط المرتبطة به. تراجعه الإدارة للتحقق من الملكية وتنفّذ الحذف من الآلية نفسها. إرسال الطلب لا يحذف الحساب فورًا.", color = Color.White)
        if (deletionRequest?.optString("status") == "pending") {
            Text("طلبك قيد المعالجة • ${deletionRequest.optString("id").take(8)}", color = PremiumGoldLight)
            OutlinedButton(onClick = onCancelDeletion, enabled = !busy) { Text("إلغاء طلب الحذف") }
        } else Button(onClick = { confirmation = ""; reason = ""; deleteDialog = true }, enabled = !busy) { Text("طلب حذف حسابي") }
        TextButton(onClick = onRefresh, enabled = !busy) { Text("تحديث حالة الطلب") }
        HorizontalDivider()
        TextButton(onClick = { withdrawDialog = true }, enabled = !busy) { Text("سحب الموافقة وتسجيل الخروج", color = PremiumGoldLight) }
    }
    document?.let { LegalDocument(it) { document = null } }
    if (deleteDialog) AlertDialog(onDismissRequest = { if (!busy) deleteDialog = false },
        title = { Text("تأكيد طلب حذف الحساب") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("الحساب: @$username. بعد تنفيذ الإدارة للحذف لن يمكن استعادة الحساب أو محتواه. يمكنك إلغاء الطلب قبل بدء التنفيذ.")
            OutlinedTextField(confirmation, { confirmation = it }, label = { Text("اكتب اسم المستخدم: $username") }, singleLine = true)
            OutlinedTextField(reason, { reason = it.take(500) }, label = { Text("سبب الطلب — اختياري") })
        } },
        confirmButton = { TextButton(enabled = !busy && confirmation == username, onClick = {
            onRequestDeletion(confirmation, reason); deleteDialog = false
        }) { Text("إرسال طلب الحذف") } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("إلغاء") } })
    if (withdrawDialog) AlertDialog(onDismissRequest = { withdrawDialog = false },
        title = { Text("سحب الموافقة") },
        text = { Text("سنسجّل خروجك ونوقف إشعارات هذا الجهاز ونرجع إلى شاشة اختيار الخصوصية. يظل المصحف متاحًا دون اتصال. هذا لا يحذف بيانات حسابك؛ استخدم طلب الحذف لذلك.") },
        confirmButton = { TextButton(onClick = { withdrawDialog = false; onWithdraw() }) { Text("سحب الموافقة") } },
        dismissButton = { TextButton(onClick = { withdrawDialog = false }) { Text("إلغاء") } })
}
