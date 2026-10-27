package com.alatta.nativeapp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** The ACTION_VIEW contract observed in the supplied Yacine and YTV PRO APKs. */
internal object YtvPlayer {
    const val APP_PAGE = "https://play.google.com/store/apps/details?id=com.ytv.pronew"
    private const val PACKAGE = "com.ytv.pronew"
    private const val SPLASH = "dev.google.ytvplayer.ui.splash.SplashActivity"

    enum class Failure(val explanation: String) {
        INVALID_ID("رقم القناة غير صحيح. حدّث قائمة القنوات من الإدارة."),
        UNAVAILABLE("ثبّت مشغّل القنوات المساعد أو حدّثه، ثم افتح القناة من العطا مرة أخرى."),
        NOT_ALLOWED("لم يسمح الجهاز بفتح مشغّل القنوات. تحقق من قيود التطبيقات ثم أعد المحاولة.")
    }

    fun channelId(value: String): String? {
        val raw = value.trim()
        if (raw.length !in 1..19) return null
        val digits = buildString {
            for (char in raw) {
                val digit = Character.digit(char, 10)
                if (digit < 0) return null
                append(digit.digitToChar())
            }
        }
        return digits.toLongOrNull()?.takeIf { it > 0 }?.toString()
    }

    fun channelIntent(value: String): Intent? {
        val id = channelId(value)?.toLong() ?: return null
        return Intent(Intent.ACTION_VIEW).setComponent(ComponentName(PACKAGE, SPLASH))
            .putExtra("id", id).putExtra("type", 1)
    }

    fun open(context: Context, value: String, beforeLaunch: () -> Unit = {}): Failure? {
        val request = channelIntent(value) ?: return Failure.INVALID_ID
        if (context !is Activity) request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        beforeLaunch()
        return try {
            context.startActivity(request)
            null
        } catch (_: ActivityNotFoundException) { Failure.UNAVAILABLE }
        catch (_: SecurityException) { Failure.NOT_ALLOWED }
    }
}

@Composable
internal fun YtvPlayerErrorDialog(failure: YtvPlayer.Failure, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("تعذر تشغيل القناة") },
        text = { Text(failure.explanation) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("رجوع") } },
        dismissButton = {
            if (failure != YtvPlayer.Failure.INVALID_ID) TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YtvPlayer.APP_PAGE))) }
                catch (_: RuntimeException) { Toast.makeText(context, "تعذر فتح صفحة تثبيت المشغّل", Toast.LENGTH_LONG).show() }
            }) { Text("تثبيت أو تحديث المشغّل") }
        })
}
