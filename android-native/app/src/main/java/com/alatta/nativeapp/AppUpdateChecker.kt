package com.alatta.nativeapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val LATEST_RELEASE_API = "https://api.github.com/repos/omaratta077-glitch/tawasol-alatta/releases/latest"
private const val OFFICIAL_RELEASES = "https://github.com/omaratta077-glitch/tawasol-alatta/releases"

internal data class AvailableAppUpdate(val version: String, val pageUrl: String, val apkUrl: String?)

private fun versionParts(tag: String): List<Int>? {
    val clean = tag.trim().removePrefix("v").removePrefix("V")
    if (!Regex("[0-9]+(\\.[0-9]+){1,3}").matches(clean)) return null
    return clean.split('.').map { it.toIntOrNull() ?: return null }
}
private fun newer(remote: String, local: String): Boolean {
    val a = versionParts(remote) ?: return false
    val b = versionParts(local) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
private fun installedVersion(context: Context): String {
    @Suppress("DEPRECATION")
    return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
}

internal suspend fun findAppUpdate(context: Context): AvailableAppUpdate? = withContext(Dispatchers.IO) {
    val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
        connectTimeout = 9000
        readTimeout = 9000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "AL-ATTA-Android-Update-Checker")
    }
    try {
        if (conn.responseCode != 200) return@withContext null // No release yet, offline, or rate-limited.
        val release = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@withContext null
        val tag = release.optString("tag_name")
        if (!newer(tag, installedVersion(context))) return@withContext null
        val page = release.optString("html_url")
        if (!page.startsWith(OFFICIAL_RELEASES)) return@withContext null
        val assets = release.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val item = assets.optJSONObject(i) ?: continue
            if (item.optString("name").endsWith(".apk", ignoreCase = true)) {
                apk = item.optString("browser_download_url").takeIf {
                    it.startsWith("https://github.com/omaratta077-glitch/tawasol-alatta/releases/download/")
                }
                if (apk != null) break
            }
        }
        // Never advertise an update which cannot be installed.
        if (apk == null) return@withContext null
        AvailableAppUpdate(tag, page, apk)
    } catch (_: Exception) {
        null // Network failure must not block the app.
    } finally {
        conn.disconnect()
    }
}

@Composable
internal fun AppUpdatePrompt() {
    val context = LocalContext.current
    var update by remember { mutableStateOf<AvailableAppUpdate?>(null) }
    LaunchedEffect(Unit) { update = findAppUpdate(context) }
    update?.let { release ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("يتوفر تحديث جديد لتواصل العطا") },
            text = { Text("الإصدار ${release.version} متاح الآن. يمكنك تنزيله من صفحة الإصدارات، ثم الموافقة على التثبيت بنفسك. لن يتم التثبيت تلقائيًا.") },
            confirmButton = {
                TextButton(onClick = {
                    val url = release.apkUrl ?: release.pageUrl
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                    update = null
                }) { Text("تحديث الآن", color = Color(0xFFE6B94E)) }
            },
            dismissButton = { TextButton(onClick = { update = null }) { Text("لاحقًا") } }
        )
    }
}
