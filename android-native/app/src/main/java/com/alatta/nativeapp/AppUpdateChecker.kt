package com.alatta.nativeapp

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** Only accept signed, non-prerelease APK assets from our exact GitHub repository release. */
internal suspend fun findAppUpdate(context: Context): AvailableAppUpdate? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val activeConnection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 9000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AL-ATTA-Android-Updater")
        }
        connection = activeConnection
        if (activeConnection.responseCode != 200) return@withContext null
        val json = JSONObject(activeConnection.inputStream.bufferedReader().use { it.readText() })
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext null
        val tag = json.optString("tag_name")
        if (!newer(tag, installedVersion(context))) return@withContext null
        if (!json.optString("html_url").startsWith("$RELEASES_URL/tag/")) return@withContext null
        val expectedAsset = "tawasol-alatta-$tag.apk"
        val assets = json.optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") != expectedAsset) continue
            val download = asset.optString("browser_download_url")
            if (download.startsWith("$RELEASES_URL/download/$tag/") && download.endsWith("/$expectedAsset")) {
                return@withContext AvailableAppUpdate(tag, download)
            }
        }
        null
    } catch (_: Exception) {
        null // Network problems never prevent the user from opening the app.
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
internal fun AppUpdatePrompt() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE) }
    var update by remember { mutableStateOf<AvailableAppUpdate?>(null) }
    var stage by remember { mutableStateOf(UpdateStage.OFFER) }
    var downloadId by remember { mutableLongStateOf(-1L) }
    var percent by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val available = findAppUpdate(context) ?: return@LaunchedEffect
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

    update?.let { release ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("تحديث جديد لتواصل العطا") },
            text = {
                Column {
                    Text("الإصدار ${release.version} متاح الآن.")
                    Spacer(Modifier.height(8.dp))
                    when (stage) {
                        UpdateStage.OFFER -> Text("يمكن تنزيله من داخل التطبيق، ثم الموافقة على تثبيته دون الدخول إلى GitHub.")
                        UpdateStage.DOWNLOADING -> {
                            Text("جارٍ تنزيل التحديث${percent?.let { " — $it%" } ?: "..."}")
                            Spacer(Modifier.height(8.dp))
                            if (percent != null) LinearProgressIndicator(progress = { (percent ?: 0) / 100f })
                            else LinearProgressIndicator()
                        }
                        UpdateStage.READY -> Text("اكتمل التنزيل. اضغط تثبيت لفتح نافذة التثبيت الرسمية من أندرويد.")
                        UpdateStage.PERMISSION -> Text("اسمح لتواصل العطا بتثبيت التطبيقات من إعدادات أندرويد، ثم ارجع واضغط تثبيت.")
                        UpdateStage.FAILED -> Text(error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = stage != UpdateStage.DOWNLOADING,
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
                    Text(
                        when (stage) {
                            UpdateStage.OFFER -> "تنزيل التحديث"
                            UpdateStage.DOWNLOADING -> "جارٍ التنزيل"
                            UpdateStage.READY, UpdateStage.PERMISSION -> "تثبيت التحديث"
                            UpdateStage.FAILED -> "إعادة المحاولة"
                        }, color = UpdateGold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { update = null }) { Text("لاحقًا") }
            }
        )
    }
}
