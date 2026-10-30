package com.alatta.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** يجري الخادم كل عمليات التحقق من كلمة المرور والصلاحيات؛ ولا يُحفظ شيء محليًا. */
@Composable
internal fun AdminProtectionPanel(
    configured: Boolean, busy: Boolean,
    runTask: (suspend () -> Unit) -> Unit,
    request: suspend (String, JSONObject) -> JSONObject,
    protect: ((String) -> Unit) -> Unit,
    onRefresh: () -> Unit
) {
    var showSetup by remember { mutableStateOf(false) }
    var currentPassword by remember { mutableStateOf("") }
    var useEmail by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<JSONObject?>(null) }
    var phrase by remember { mutableStateOf("") }
    val gold = Color(0xFFF6D776)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("كلمة مرور حذف الحسابات", color = gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(if (configured) "🔒 حذف الحسابات فقط يتطلب كتابة كلمة المرور كل مرة" else "🔒 اضبط كلمة مرور حذف الحسابات أولًا", color = gold, fontSize = 13.sp)
        Text("حساب المدير الرئيسي محمي دائمًا. المشرفون لا يملكون صلاحية الحذف.", color = Color.LightGray, fontSize = 12.sp)
        TextButton(onClick = { showSetup = !showSetup; password = ""; confirmation = "" }) {
            SiteIcon("shield", size = 20.dp)
            Text(if (configured) " تغيير كلمة الحماية" else " إعداد كلمة الحماية", color = gold)
        }
        if (showSetup) {
            Text("تُحفظ كلمة الحماية في حساب المدير على الخادم. اختر كلمة مختلفة عن كلمة الدخول (10 أحرف على الأقل).", color = Color.LightGray, fontSize = 12.sp)
            if (configured) {
                PremiumAuthTextField(currentPassword, { currentPassword = it.take(128) }, "كلمة حماية الحسابات الحالية", password = true)
                TextButton(onClick = { useEmail = !useEmail; code = "" }) { Text("نسيت كلمة الحماية؟ تأكيد بالبريد", color = gold) }
            }
            if (!configured || useEmail) {
            Button(enabled = !busy, onClick = { runTask {
                val result = request("/api/admin/security/code", JSONObject())
                status = "أُرسل الكود إلى ${result.optString("email")}"
            } }) { Text("إرسال كود إلى بريد المدير") }
            PremiumAuthTextField(code, { code = it.take(6) }, "كود البريد")
            }
            PremiumAuthTextField(password, { password = it.take(128) }, "كلمة حماية جديدة", password = true)
            PremiumAuthTextField(confirmation, { confirmation = it.take(128) }, "تأكيد كلمة الحماية", password = true)
            Button(enabled = !busy && (if (configured && !useEmail) currentPassword.isNotBlank() else code.length == 6) && password.length >= 10 && password == confirmation, onClick = {
                val next = password; val again = confirmation; val current = if (configured && !useEmail) currentPassword else ""
                password = ""; confirmation = ""; currentPassword = ""
                runTask {
                    request("/api/admin/security/password", JSONObject().put("code", code).put("currentPassword", current).put("newPassword", next).put("confirmPassword", again))
                    code = ""; showSetup = false; status = "تم حفظ كلمة مرور حذف الحسابات في حساب المدير"; onRefresh()
                }
            }) { Text("حفظ كلمة الحماية") }
        }
        HorizontalDivider()
        Text("الحذف الجماعي", color = gold, fontWeight = FontWeight.Bold)
        Text("حذف الحسابات يتطلب كلمة الحماية. حذف المنشورات يحتاج تأكيدًا فقط.", color = Color.LightGray, fontSize = 12.sp)
        listOf("accounts" to "حذف الحسابات عدا المدير", "posts" to "حذف كل المنشورات").forEach { (kind, label) ->
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { runTask {
                plan = request("/api/admin/reset/plan", JSONObject().put("scope", kind)); phrase = ""
                val scope = plan?.optJSONObject("scope") ?: JSONObject()
                status = if (plan?.optBoolean("resuming") == true) "توجد عملية حذف قيد الاستكمال" else
                    "حسابات: ${scope.optInt("accountsToDelete")} • منشورات: ${scope.optInt("postsToDelete")} • حساب المدير محفوظ"
            } }) { Text(label, color = gold) }
        }
        plan?.let { current ->
            Text("اكتب: ${current.optString("confirmation")}", color = gold, fontSize = 12.sp)
            PremiumAuthTextField(phrase, { phrase = it }, "عبارة التأكيد")
            Button(enabled = !busy && phrase == current.optString("confirmation"), onClick = {
                val perform: (String) -> Unit = { secret -> runTask {
                var done = false
                while (!done) {
                    val result = request("/api/admin/reset/execute", JSONObject().put("planId", current.optString("planId"))
                        .put("confirmation", phrase).put("adminPassword", secret))
                    done = result.optBoolean("done"); status = "تمت معالجة ${result.optInt("completed")}"
                }
                plan = null; phrase = ""; status = "اكتمل الحذف؛ حساب المدير محفوظ"; onRefresh()
            } }
                if (current.optJSONObject("scope")?.optString("kind") == "accounts") protect(perform) else perform("")
            }) { Text("تنفيذ الحذف") }
        }
        if (status.isNotBlank()) Text(status, color = gold, fontSize = 12.sp)
    }
}
