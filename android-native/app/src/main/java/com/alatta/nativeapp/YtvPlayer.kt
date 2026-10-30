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

/** عقد ACTION_VIEW الذي لوحظ في تطبيقي Yacine وYTV PRO المرفقين. */
internal object YtvPlayer {
    const val APP_PAGE = "https://play.google.com/store/apps/details?id=com.ytv.pronew"
    private const val PACKAGE = "com.ytv.pronew"
    private const val SPLASH = "dev.google.ytvplayer.ui.splash.SplashActivity"

    enum class Failure(val explanation: String) {
        INVALID_ID("رقم القناة غير صحيح. حدّث قائمة القنوات من الإدارة."),
        UNAVAILABLE("ثبّت مشغّل القنوات المساعد أو حدّثه، ثم افتح القناة من العطا مرة أخرى."),
        NOT_ALLOWED("لم يسمح الجهاز بفتح مشغّل القنوات. تحقق من قيود التطبيقات ثم أعد المحاولة."),
        ROOM_STARTING("انتظر اكتمال تجهيز الكاميرا وغرفة المشاهدة، ثم شغّل القناة."),
        PIP_PERMISSION("إذن الصورة داخل صورة مقفول للعطا على هذا الهاتف. فعّله لتظهر مكالمة الغرفة فوق المباراة، ثم ارجع وشغّل القناة."),
        PIP_UNAVAILABLE("تعذر فتح نافذة المكالمة المصغّرة على هذا الهاتف. ارجع للعطا وحاول مجددًا."),
        ROOM_ENDED("انتهت غرفة المشاهدة قبل فتح القناة. يمكنك اختيار القناة مجددًا.")
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
        return launch(context, request, beforeLaunch)
    }

    private fun launch(context: Context, request: Intent, beforeLaunch: () -> Unit): Failure? {
        if (context !is Activity) request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        beforeLaunch()
        return try {
            context.startActivity(request)
            null
        } catch (_: ActivityNotFoundException) { Failure.UNAVAILABLE }
        catch (_: SecurityException) { Failure.NOT_ALLOWED }
    }

    suspend fun openWithWatchRoom(context: Context, value: String, beforeLaunch: () -> Unit = {}): Failure? {
        val request = channelIntent(value) ?: return Failure.INVALID_ID
        if (WatchRoomController.active) {
            // تجنب الدخول إلى وضع PiP لمجرد اكتشاف عدم وجود مشغّل مثبت يعالج القناة.
            if (request.resolveActivity(context.packageManager) == null) return Failure.UNAVAILABLE
            WatchRoomPip.enterAndWait(context)?.let { return it.playerFailure() }
        }
        return launch(context, if (WatchRoomController.active) WatchRoomPip.playerIntent(request) else request, beforeLaunch)
    }
}

internal fun WatchRoomPip.Failure.playerFailure(): YtvPlayer.Failure = when (this) {
    WatchRoomPip.Failure.STARTING -> YtvPlayer.Failure.ROOM_STARTING
    WatchRoomPip.Failure.PERMISSION -> YtvPlayer.Failure.PIP_PERMISSION
    WatchRoomPip.Failure.UNAVAILABLE -> YtvPlayer.Failure.PIP_UNAVAILABLE
    WatchRoomPip.Failure.ROOM_ENDED -> YtvPlayer.Failure.ROOM_ENDED
}

@Composable
internal fun YtvPlayerErrorDialog(failure: YtvPlayer.Failure, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (failure in listOf(YtvPlayer.Failure.PIP_PERMISSION,
        YtvPlayer.Failure.PIP_UNAVAILABLE, YtvPlayer.Failure.ROOM_STARTING, YtvPlayer.Failure.ROOM_ENDED)) "نافذة مكالمة الغرفة" else "تعذر تشغيل القناة") },
        text = { Text(failure.explanation) },
        confirmButton = {
            if (failure == YtvPlayer.Failure.PIP_PERMISSION) TextButton(onClick = {
                onDismiss()
                try { context.startActivity(WatchRoomPip.settingsIntent(context)) }
                catch (_: RuntimeException) { Toast.makeText(context, "افتح إعدادات العطا ثم فعّل الصورة داخل صورة", Toast.LENGTH_LONG).show() }
            }) { Text("فتح الإعدادات") }
            else TextButton(onClick = onDismiss) { Text("رجوع") }
        },
        dismissButton = {
            if (failure == YtvPlayer.Failure.PIP_PERMISSION) TextButton(onClick = onDismiss) { Text("رجوع") }
            else if (failure == YtvPlayer.Failure.UNAVAILABLE || failure == YtvPlayer.Failure.NOT_ALLOWED) TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YtvPlayer.APP_PAGE))) }
                catch (_: RuntimeException) { Toast.makeText(context, "تعذر فتح صفحة تثبيت المشغّل", Toast.LENGTH_LONG).show() }
            }) { Text("تثبيت أو تحديث المشغّل") }
        })
}
