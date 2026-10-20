package com.alatta.nativeapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal object AttaPrivacy {
    const val VERSION = "2026-10-20"
    private fun prefs(context: Context) = context.getSharedPreferences("atta_privacy", Context.MODE_PRIVATE)
    fun accepted(context: Context) = prefs(context).getString("accepted_version", "") == VERSION
    fun accept(context: Context) { prefs(context).edit().putString("accepted_version", VERSION)
        .putLong("accepted_at", System.currentTimeMillis()).apply() }
    fun revoke(context: Context) { prefs(context).edit().remove("accepted_version").remove("accepted_at").apply() }
    fun notifications(context: Context) = prefs(context).getBoolean("notifications", true)
    fun setNotifications(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("notifications", enabled).apply() }
}

/** No account, network task or third-party SDK is mounted until this choice. */
@Composable
internal fun AttaPrivacyGate(content: @Composable ((() -> Unit), (() -> Unit)) -> Unit) {
    val context = LocalContext.current
    var accepted by remember { mutableStateOf(AttaPrivacy.accepted(context)) }
    var authenticated by remember { mutableStateOf(!context.getSharedPreferences("alatta_native", Context.MODE_PRIVATE)
        .getString("token", "").isNullOrBlank()) }
    var offlineReader by remember { mutableStateOf(false) }
    var document by remember { mutableStateOf<String?>(null) }
    var agreedPrivacy by remember { mutableStateOf(false) }
    var agreedTerms by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(primary = PremiumGold, background = PremiumBackground)) {
            if (accepted || !authenticated) content({
                AttaPrivacy.revoke(context); accepted = false
                agreedPrivacy = false; agreedTerms = false
            }, {
                authenticated = true
                accepted = AttaPrivacy.accepted(context)
            }) else if (offlineReader) {
                QuranReaderScreen(onExit = { offlineReader = false }, allowOnlineFeatures = false)
            } else Column(Modifier.fillMaxSize().background(PremiumBackground).safeDrawingPadding()
                .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("خصوصيتك في تواصل العطا", color = PremiumGoldLight, style = MaterialTheme.typography.headlineSmall)
                Text("المصحف متاح بدون حساب أو اتصال. الخدمات الاجتماعية تحتاج بيانات الحساب والمنشورات والرسائل لتعمل، وتُحفظ لدى Cloudflare.", color = Color.White)
                Text("الكاميرا والميكروفون عند المكالمة فقط. تسجيل Google والإشعارات ويوتيوب خدمات اختيارية، وتُستخدم عند اختيار وظائفها. يمكنك إيقاف الإشعارات وسحب الموافقة من القائمة.", color = PremiumMuted)
                LegalLinks { document = it }
                Row { Checkbox(agreedPrivacy, { agreedPrivacy = it }); Text("قرأت سياسة الخصوصية وأوافق على معالجة البيانات اللازمة للخدمات التي أختارها.", Modifier.padding(top = 10.dp), color = Color.White) }
                Row { Checkbox(agreedTerms, { agreedTerms = it }); Text("قرأت شروط الاستخدام وألتزم بحقوق الآخرين وقواعد نشر المحتوى.", Modifier.padding(top = 10.dp), color = Color.White) }
                Button(onClick = { AttaPrivacy.accept(context); accepted = true }, enabled = agreedPrivacy && agreedTerms,
                    modifier = Modifier.fillMaxWidth()) { Text("الموافقة والمتابعة") }
                OutlinedButton(onClick = { offlineReader = true }, modifier = Modifier.fillMaxWidth()) { Text("المصحف بدون حساب أو اتصال") }
                TextButton(onClick = { (context as? Activity)?.finish() }) { Text("عدم الموافقة والخروج") }
            }
            document?.let { LegalDocument(it) { document = null } }
        }
    }
}

@Composable
internal fun LegalLinks(open: (String) -> Unit) {
    Column {
        TextButton(onClick = { open("privacy.html") }) { Text("سياسة الخصوصية", color = PremiumGoldLight) }
        TextButton(onClick = { open("terms.html") }) { Text("شروط الاستخدام وقواعد المحتوى", color = PremiumGoldLight) }
        TextButton(onClick = { open("data-rights.html") }) { Text("حقوق البيانات وحذف الحساب", color = PremiumGoldLight) }
    }
}

/** The same published legal documents are bundled for offline review; no remote scripts. */
@Composable
internal fun LegalDocument(name: String, close: () -> Unit) {
    val context = LocalContext.current
    val allowed = setOf("privacy.html", "terms.html", "data-rights.html")
    if (name !in allowed) return
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = close)
        Column(Modifier.fillMaxSize().background(PremiumBackground).safeDrawingPadding()) {
            TextButton(onClick = close) { Text("رجوع", color = PremiumGoldLight) }
            val view = remember(name) { WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url.lastPathSegment
                        if (target in allowed) view.loadUrl("file:///android_asset/legal/$target")
                        else if (request.url.scheme == "mailto") runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, request.url)) }
                        else if (request.hasGesture() && request.url.toString() in setOf(
                            "https://www.cloudflare.com/privacypolicy/", "https://policies.google.com/privacy",
                            "https://firebase.google.com/support/privacy", "https://consumer.huawei.com/en/privacy/privacy-policy/",
                            "https://resend.com/legal/privacy-policy"
                        )) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                }
                loadUrl("file:///android_asset/legal/$name")
            } }
            DisposableEffect(view) { onDispose { view.stopLoading(); view.destroy() } }
            AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}
