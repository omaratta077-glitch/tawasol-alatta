package com.alatta.nativeapp
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], application = Application::class)
class CallNotificationStyleTest {
    @Test fun incomingCallUsesRealNameBrandIconAndTwoWorkingSystemActions() {
        val context = RuntimeEnvironment.getApplication()
        val accept = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val reject = PendingIntent.getBroadcast(context, 2, Intent(context, AttaCallRejectReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val call = PushCall("caller", "receiver", "test-call-154", "audio", System.currentTimeMillis(), "", "عمر عطا")
        val notification = NotificationCompat.Builder(context, "test-calls").setSmallIcon(R.drawable.atta_notification_icon)
            .setContentTitle(call.displayName).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(incomingCallStyle(context, call, reject, accept)).build()
        assertEquals("عمر عطا", notification.extras.getCharSequence("android.title").toString())
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(setOf(reject, accept), notification.actions.map { it.actionIntent }.toSet())
        assertEquals(2, notification.actions.size)
    }
}
