package com.alatta.nativeapp

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

/** A small brand icon and bounded call controls, never a full-width picture. */
internal fun incomingCallNotification(context: Context, call: PushCall, view: PendingIntent,
    accept: PendingIntent, reject: PendingIntent, silent: Boolean): Notification {
    fun layout(resource: Int) = RemoteViews(context.packageName, resource).apply {
        setTextViewText(R.id.call_name, call.displayName.ifBlank { "مستخدم" })
        setTextViewText(R.id.call_kind, if (call.type == "video") "مكالمة فيديو واردة" else "مكالمة صوتية واردة")
        setOnClickPendingIntent(R.id.call_logo, view)
        setOnClickPendingIntent(R.id.call_name, view)
        if (resource == R.layout.atta_call_expanded) {
            setOnClickPendingIntent(R.id.call_answer, accept)
            setOnClickPendingIntent(R.id.call_reject, reject)
        }
    }
    return NotificationCompat.Builder(context, AttaPush.CHANNEL)
        .setSmallIcon(R.drawable.atta_notification_icon)
        .setColor(0xFFD8B451.toInt())
        .setContentTitle(call.displayName.ifBlank { "مستخدم" })
        .setContentText(if (call.type == "video") "مكالمة فيديو واردة" else "مكالمة صوتية واردة")
        .setCustomContentView(layout(R.layout.atta_call_compact))
        .setCustomBigContentView(layout(R.layout.atta_call_expanded))
        .setCustomHeadsUpContentView(layout(R.layout.atta_call_expanded))
        .setFullScreenIntent(view, true)
        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .setSilent(silent).setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(true)
        .setContentIntent(view).setTimeoutAfter(remainingIncomingCallMillis(call.createdAt).coerceAtLeast(1L)).build()
}

internal fun remainingIncomingCallMillis(createdAt: Long, now: Long = System.currentTimeMillis()): Long =
    (45_000L - (now - createdAt).coerceAtLeast(0L)).coerceIn(0L, 45_000L)
