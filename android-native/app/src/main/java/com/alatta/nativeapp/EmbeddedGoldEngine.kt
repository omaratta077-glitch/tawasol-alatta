package com.alatta.nativeapp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Host-side adapter for an IN-PROCESS gold engine port.
 *
 * The gold implementation must be compiled and merged into the AL-ATTA Media APK,
 * with its Activity registered under Media's applicationId. This must never start
 * the separately-installed com.ytv.pronew app or load an APK at runtime.
 *
 * This adapter is deliberately dormant until that Activity actually exists
 * in the installed host package. Shipping this file alone is NOT engine integration.
 */
internal object EmbeddedGoldEngine {
    internal const val ENTRY_ACTIVITY = "dev.google.ytvplayer.ui.splash.SplashActivity"

    internal enum class Result {
        NOT_PACKAGED,
        INVALID_CHANNEL,
        STARTED,
        NOT_ALLOWED,
        FAILED
    }

    internal fun channelIntent(hostPackage: String, rawChannelId: String): Intent? {
        val channelId = YtvPlayer.channelId(rawChannelId)?.toLongOrNull() ?: return null
        if (hostPackage.isBlank()) return null
        return Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(hostPackage, ENTRY_ACTIVITY))
            .putExtra("id", channelId)
            .putExtra("type", 1)
    }

    /** Only a component registered inside THIS app counts as embedded. */
    @Suppress("DEPRECATION")
    internal fun isPackaged(context: Context): Boolean {
        val component = ComponentName(context.packageName, ENTRY_ACTIVITY)
        return try {
            context.packageManager.getActivityInfo(component, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun launch(context: Context, rawChannelId: String, beforeLaunch: () -> Unit = {}): Result {
        val intent = channelIntent(context.packageName, rawChannelId) ?: return Result.INVALID_CHANNEL
        if (!isPackaged(context)) return Result.NOT_PACKAGED
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            beforeLaunch()
            context.startActivity(intent)
            Result.STARTED
        } catch (_: ActivityNotFoundException) {
            Result.FAILED
        } catch (_: SecurityException) {
            Result.NOT_ALLOWED
        } catch (_: RuntimeException) {
            Result.FAILED
        }
    }
}
