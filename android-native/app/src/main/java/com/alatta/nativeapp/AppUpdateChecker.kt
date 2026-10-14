package com.alatta.nativeapp

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val RELEASE_API = "https://api.github.com/repos/omaratta077-glitch/tawasol-alatta/releases/latest"
private const val RELEASES_URL = "https://github.com/omaratta077-glitch/tawasol-alatta/releases"
private const val UPDATE_PREFS = "alatta_apk_updater"
private const val SAVED_DOWNLOAD_ID = "id"
private const val SAVED_DOWNLOAD_VERSION = "version"
private val UpdateGold = Color(0xFFE6B94E)
private val UpdateGoldLight = Color(0xFFF6D776)
private val UpdatePanel = Color(0xFF0D1012)
private val UpdateTrack = Color(0xFF423923)
private val UpdateSeparator = Color(0xFF4F4127)
private val UpdateWhite = Color(0xFFF3F4F6)
private val UpdateError = Color(0xFFFFB4AB)

internal data class AvailableAppUpdate(val version: String, val apkUrl: String)
private enum class UpdateStage { OFFER, DOWNLOADING, READY, PERMISSION, FAILED }

private fun versionParts(tag: String): List<Int>? {
    val clean = tag.trim().removePrefix("v").removePrefix("V")
    if (!Regex("[0-9]+(\\.[0-9]+){1,3}").matches(clean)) return null
    return clean.split('.').map { it.toIntOrNull() ?: return null }
}

private fun newer(remote: String, local: String): Boolean {
    val a = versionParts(remote) ?: return false
    val b = versionParts(local) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun installedVersion(context: Context): String {
    @Suppress("DEPRECATION")
    return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
}

/** Human-readable result for manual checking, not just silent "no updates". */
private data class ReleaseInspection(val available: AvailableAppUpdate?, val status: String)

private suspend fun inspectRelease(context: Context): ReleaseInspection = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val activeConnection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 9000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AL-ATTA-Android-Updater")
        }
        connection = activeConnection
        val code = activeConnection.responseCode
        if (code != 200) return@withContext ReleaseInspection(null,
            "تعذر فحص GitHub Releases (رمز $code). تأكد من نشر Release رسمي ووجود الإنترنت.")
        val json = JSONObject(activeConnection.inputStream.bufferedReader().use { it.readText() })
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
            return@withContext ReleaseInspection(null, "الإصدار الموجود مسودة أو تجريبي، ولن يظهر كتحديث رسمي.")
        }
        val tag = json.optString("tag_name")
        val current = installedVersion(context)
        if (!newer(tag, current)) {
            return@withContext ReleaseInspection(null,
                "نسختك المثبّتة $current. آخر إصدار رسمي على GitHub هو $tag، ولا يوجد إصدار أحدث.")
        }
        if (!json.optString("html_url").startsWith("$RELEASES_URL/tag/")) {
            return@withContext ReleaseInspection(null, "تعذر التحقق من رابط الإصدار الرسمي على GitHub.")
        }
        val expectedAsset = "tawasol-alatta-$tag.apk"
        val assets = json.optJSONArray("assets")
            ?: return@withContext ReleaseInspection(null, "الإصدار $tag ليس به ملفات APK منشورة.")
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") != expectedAsset) continue
            val download = asset.optString("browser_download_url")
            if (download.startsWith("$RELEASES_URL/download/$tag/") &&
                download.endsWith("/$expectedAsset")) {
                return@withContext ReleaseInspection(AvailableAppUpdate(tag, download),
                    "يتوفر تحديث رسمي جديد $tag")
            }
        }
        ReleaseInspection(null, "الإصدار $tag منشور لكن ملف $expectedAsset غير موجود في Assets.")
    } catch (e: Exception) {
        ReleaseInspection(null,
            "تعذر الاتصال بـGitHub للتحقق من التحديثات. افحص الإنترنت وحاول مجددًا.")
    } finally {
        connection?.disconnect()
    }
}

private fun apkFile(context: Context, version: String): File? {
    val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
    return File(folder, "tawasol-alatta-$version.apk")
}

private data class DownloadSnapshot(val state: Int, val percent: Int?, val reason: Int)

private fun queryDownload(context: Context, id: Long): DownloadSnapshot? {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    return manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
        if (!c.moveToFirst()) return@use null
        val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val current = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        val percent = if (total > 0L) ((current * 100L) / total).toInt().coerceIn(0, 100) else null
        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        DownloadSnapshot(state, percent, reason)
    }
}

private fun beginDownload(context: Context, update: AvailableAppUpdate): Long {
    val destination = apkFile(context, update.version)
        ?: throw IllegalStateException("مساحة تنزيل التطبيق غير متاحة")
    if (destination.exists()) destination.delete()
    val request = DownloadManager.Request(Uri.parse(update.apkUrl))
        .setTitle("تحديث تواصل العطا ${update.version}")
        .setDescription("تنزيل نسخة APK الرسمية والموقعة")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(false)
        .setDestinationInExternalFilesDir(
            context, Environment.DIRECTORY_DOWNLOADS, destination.name
        )
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    return manager.enqueue(request)
}

private fun openInstaller(context: Context, update: AvailableAppUpdate): Boolean {
    val destination = apkFile(context, update.version)
    if (destination == null || !destination.isFile || destination.length() < 1024L) {
        throw IllegalStateException("لم يكتمل تنزيل ملف التحديث")
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"))
        )
        return false
    }
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.updatefileprovider", destination
    )
    val install = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(install) // Android requires user confirmation; never silent-install.
    return true
}

/** In-app APK download; Android's official package installer performs user-approved installation. */
@Composable
internal fun AppUpdatePrompt(manualCheckRequest: Int = 0) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE) }
    var update by remember { mutableStateOf<AvailableAppUpdate?>(null) }
    var stage by remember { mutableStateOf(UpdateStage.OFFER) }
    var downloadId by remember { mutableLongStateOf(-1L) }
    var percent by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf("") }
    var checkStatus by remember { mutableStateOf("") }

    LaunchedEffect(manualCheckRequest) {
        val result = inspectRelease(context)
        val available = result.available
        if (available == null) {
            if (manualCheckRequest > 0) checkStatus = result.status
            return@LaunchedEffect
        }
        checkStatus = ""
        update = available
        val previousId = prefs.getLong(SAVED_DOWNLOAD_ID, -1L)
        if (previousId > 0L && prefs.getString(SAVED_DOWNLOAD_VERSION, "") == available.version) {
            stage = UpdateStage.DOWNLOADING
            downloadId = previousId
        }
    }

    LaunchedEffect(downloadId) {
        if (downloadId <= 0L) return@LaunchedEffect
        while (true) {
            val result = try {
                withContext(Dispatchers.IO) { queryDownload(context, downloadId) }
            } catch (_: Exception) {
                null
            }
            if (result == null) {
                stage = UpdateStage.FAILED
                error = "تعذر متابعة تنزيل التحديث. جرّب مرة أخرى."
                prefs.edit().remove(SAVED_DOWNLOAD_ID).remove(SAVED_DOWNLOAD_VERSION).apply()
                break
            }
            percent = result.percent
            when (result.state) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val savedVersion = prefs.getString(SAVED_DOWNLOAD_VERSION, "") ?: ""
                    val file = if (savedVersion.isNotBlank()) apkFile(context, savedVersion) else null
                    if (file != null && file.isFile && file.length() >= 1024L) {
                        stage = UpdateStage.READY
                    } else {
                        stage = UpdateStage.FAILED
                        error = "اكتمل التنزيل لكن ملف APK غير موجود. جرّب مرة أخرى."
                        prefs.edit().remove(SAVED_DOWNLOAD_ID).remove(SAVED_DOWNLOAD_VERSION).apply()
                    }
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    stage = UpdateStage.FAILED
                    error = "فشل التنزيل (رمز ${result.reason}). تأكد من الإنترنت والمساحة المتاحة."
                    prefs.edit().remove(SAVED_DOWNLOAD_ID).remove(SAVED_DOWNLOAD_VERSION).apply()
                    break
                }
            }
            delay(1200L)
        }
    }

    if (checkStatus.isNotBlank()) {
        Dialog(onDismissRequest = { checkStatus = "" }) {
            Surface(
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(21.dp),
                color = UpdatePanel, border = BorderStroke(1.dp, UpdateGold)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("فحص تحديث تواصل العطا", color = UpdateGoldLight,
                        fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(checkStatus, color = UpdateWhite, fontSize = 14.sp)
                    TextButton(onClick = { checkStatus = "" }) {
                        Text("حسنًا", color = UpdateGoldLight)
                    }
                }
            }
        }
    }

    update?.let { release ->
        val progress = when (stage) {
            UpdateStage.READY, UpdateStage.PERMISSION -> 100
            UpdateStage.DOWNLOADING -> percent
            else -> 0
        }
        val primaryLabel = when (stage) {
            UpdateStage.OFFER -> "تنزيل التحديث"
            UpdateStage.DOWNLOADING -> "جارٍ التنزيل"
            UpdateStage.READY -> "تثبيت الآن"
            UpdateStage.PERMISSION -> "متابعة التثبيت"
            UpdateStage.FAILED -> "إعادة المحاولة"
        }

        // Custom dark/gold modal: Material 3 default AlertDialog uses the system color scheme.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Dialog(onDismissRequest = { update = null }) {
                Surface(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = UpdatePanel,
                    border = BorderStroke(1.dp, UpdateGold.copy(alpha = 0.88f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 18.dp
                ) {
                    Column(
                        modifier = Modifier
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF18191A), UpdatePanel, Color(0xFF08090B))
                                )
                            )
                            .padding(top = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Image(
                                painter = painterResource(R.drawable.logo_premium),
                                contentDescription = "شعار تواصل العطا",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, UpdateGold, CircleShape)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "تحديث جديد لتواصل العطا",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    lineHeight = 28.sp
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    text = "الإصدار ${release.version} متاح الآن",
                                    color = UpdateGoldLight,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        val message = when (stage) {
                            UpdateStage.OFFER -> "نزّل الإصدار الرسمي مباشرة من داخل التطبيق. سيطلب أندرويد موافقتك قبل التثبيت."
                            UpdateStage.DOWNLOADING -> "جارٍ تنزيل التحديث${progress?.let { " — $it%" } ?: "..."}"
                            UpdateStage.READY -> "اكتمل التنزيل. اضغط تثبيت لفتح نافذة أندرويد الرسمية."
                            UpdateStage.PERMISSION -> "اسمح للتطبيق بتثبيت التحديث من إعدادات أندرويد، ثم اضغط متابعة التثبيت."
                            UpdateStage.FAILED -> error.ifBlank { "تعذر تنزيل التحديث، حاول مرة أخرى." }
                        }
                        Text(
                            text = message,
                            color = if (stage == UpdateStage.FAILED) UpdateError else UpdateWhite,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)
                        )
                        Spacer(Modifier.height(18.dp))
                        if (stage == UpdateStage.DOWNLOADING && progress == null) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)
                                    .height(6.dp).clip(RoundedCornerShape(6.dp)),
                                color = UpdateGold,
                                trackColor = UpdateTrack
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { (progress ?: 0).coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)
                                    .height(6.dp).clip(RoundedCornerShape(6.dp)),
                                color = UpdateGold,
                                trackColor = UpdateTrack
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Spacer(Modifier.fillMaxWidth().height(1.dp).background(UpdateSeparator))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                                enabled = stage != UpdateStage.DOWNLOADING,
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = UpdateGold,
                                    contentColor = Color(0xFF12100C),
                                    disabledContainerColor = Color(0xFF514528),
                                    disabledContentColor = Color(0xFFCEC2A9)
                                ),
                                onClick = {
                                    when (stage) {
                                        UpdateStage.OFFER, UpdateStage.FAILED -> {
                                            try {
                                                val id = beginDownload(context, release)
                                                prefs.edit()
                                                    .putLong(SAVED_DOWNLOAD_ID, id)
                                                    .putString(SAVED_DOWNLOAD_VERSION, release.version)
                                                    .apply()
                                                error = ""
                                                percent = null
                                                stage = UpdateStage.DOWNLOADING
                                                downloadId = id
                                            } catch (t: Exception) {
                                                stage = UpdateStage.FAILED
                                                error = "تعذر بدء التنزيل: ${t.message ?: "خطأ غير معروف"}"
                                            }
                                        }
                                        UpdateStage.READY, UpdateStage.PERMISSION -> {
                                            try {
                                                if (openInstaller(context, release)) {
                                                    update = null
                                                } else {
                                                    stage = UpdateStage.PERMISSION
                                                }
                                            } catch (t: Exception) {
                                                stage = UpdateStage.FAILED
                                                error = "تعذر فتح مثبت أندرويد: ${t.message ?: "خطأ غير معروف"}"
                                            }
                                        }
                                        UpdateStage.DOWNLOADING -> Unit
                                    }
                                }
                            ) {
                                Text(primaryLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(
                                onClick = { update = null },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("لاحقًا", color = UpdateGoldLight, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
