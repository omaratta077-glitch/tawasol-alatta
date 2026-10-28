package com.alatta.nativeapp

import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Rational
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Explicit user-triggered PiP before opening a channel; independent of room ownership and leave hints. */
internal object WatchRoomPip {
    enum class Failure { STARTING, PERMISSION, UNAVAILABLE, ROOM_ENDED }

    @Suppress("DEPRECATION")
    fun enterNow(context: Context): Failure? {
        if (!WatchRoomController.active) return null
        if (!WatchRoomController.ready || WatchRoomController.room == null) return Failure.STARTING
        val activity = context.mediaActivity() ?: return Failure.UNAVAILABLE
        if (activity.isInPictureInPictureMode) return null
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return Failure.UNAVAILABLE
        val mode = runCatching { activity.getSystemService(AppOpsManager::class.java)
            .checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), activity.packageName) }.getOrDefault(AppOpsManager.MODE_DEFAULT)
        if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) return Failure.PERMISSION
        val entered = runCatching {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= 31) params.setAutoEnterEnabled(true)
            activity.enterPictureInPictureMode(params.build())
        }.getOrDefault(false)
        return if (entered) null else Failure.UNAVAILABLE
    }

    suspend fun enterAndWait(context: Context): Failure? = withContext(Dispatchers.Main.immediate) {
        if (!WatchRoomController.active) return@withContext null
        enterNow(context)?.let { return@withContext it }
        val activity = context.mediaActivity() ?: return@withContext Failure.UNAVAILABLE
        val entered = withTimeoutOrNull(2500) {
            while (WatchRoomController.active && !activity.isInPictureInPictureMode && !activity.isDestroyed) delay(16)
            activity.isInPictureInPictureMode
        } == true
        if (!WatchRoomController.active) return@withContext Failure.ROOM_ENDED
        if (!entered) return@withContext Failure.UNAVAILABLE
        // Let the PiP callback replace the app chrome with the participants before handing off the channel.
        delay(60)
        if (!WatchRoomController.active) Failure.ROOM_ENDED
        else if (activity.isDestroyed || !activity.isInPictureInPictureMode) Failure.UNAVAILABLE else null
    }

    fun settingsIntent(context: Context) = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS",
        Uri.parse("package:${context.packageName}"))
}
