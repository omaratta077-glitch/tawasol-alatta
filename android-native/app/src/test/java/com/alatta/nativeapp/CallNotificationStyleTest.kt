package com.alatta.nativeapp
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], application = Application::class)
class CallNotificationStyleTest {
    @Test fun incomingCallHasSmallLogoRealNameAndTwoBoundedColoredControlsWithoutBigPicture() {
        val context = RuntimeEnvironment.getApplication()
        val accept = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val reject = PendingIntent.getBroadcast(context, 2, Intent("test.reject").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val call = PushCall("caller", "receiver", "test-call-155", "audio", System.currentTimeMillis(), "", "عمر عطا")
        val answerIntent = AttaPush.intentFor(context, call, AttaPush.ACTION_ACCEPT)
        val viewIntent = AttaPush.intentFor(context, call, AttaPush.ACTION_VIEW)
        assertNotEquals(answerIntent.data, viewIntent.data)
        assertEquals(AttaPush.ACTION_ACCEPT, PushCall.fromIntent(answerIntent)?.action)
        assertEquals(call.displayName, PushCall.fromIntent(answerIntent)?.displayName)
        val notification = incomingCallNotification(context, call, accept, accept, reject, false)
        assertEquals("عمر عطا", notification.extras.getCharSequence("android.title").toString())
        assertFalse(notification.extras.containsKey("android.picture"))
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertNotNull(notification.fullScreenIntent)
        assertEquals(android.app.Notification.CATEGORY_CALL, notification.category)
        assertEquals(R.drawable.atta_notification_icon, notification.smallIcon.resId)
        assertNotNull("Incoming calls must use the existing gold app logo as a thumbnail", notification.getLargeIcon())
        assertNotNull(notification.getLargeIcon().loadDrawable(context))
        assertEquals(0xFFD8B451.toInt(), notification.color)
        val compact = notification.contentView.apply(context, FrameLayout(context))
        val headsUp = notification.headsUpContentView.apply(context, FrameLayout(context))
        assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, context.resources.displayMetrics).toInt(), compact.layoutParams.height)
        assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 84f, context.resources.displayMetrics).toInt(), headsUp.layoutParams.height)
        assertNotNull(headsUp.findViewById<ImageView>(R.id.call_logo).drawable)
        val expanded = notification.bigContentView.apply(context, FrameLayout(context))
        assertEquals("عمر عطا", expanded.findViewById<TextView>(R.id.call_name).text.toString())
        assertNotNull(expanded.findViewById<ImageView>(R.id.call_logo).drawable)
        assertEquals("رد", expanded.findViewById<TextView>(R.id.call_answer).text.toString())
        assertEquals("رفض", expanded.findViewById<TextView>(R.id.call_reject).text.toString())
        assertNotNull(expanded.findViewById<TextView>(R.id.call_answer).compoundDrawablesRelative[0])
        assertNotNull(expanded.findViewById<TextView>(R.id.call_reject).compoundDrawablesRelative[0])
        assertTrue(expanded.findViewById<TextView>(R.id.call_answer).isClickable)
        assertTrue(expanded.findViewById<TextView>(R.id.call_reject).isClickable)
    }
    @Test fun pendingCallSurvivesActivityEntryButEndsOnRejectExpiryOrAccountSwitch() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("alatta_native", 0)
        prefs.edit().clear().putString("username", "receiver").putString("token", "local-test-session").commit()
        val call = PushCall("caller", "receiver", "call-pending-155", "audio", System.currentTimeMillis(), AttaPush.ACTION_VIEW, "عمر")
        AttaPush.rememberIncoming(context, call)
        assertEquals(call, AttaPush.pendingIncoming(context))
        AttaPush.dismiss(context, call.callId, ended = false)
        assertEquals(call, AttaPush.pendingIncoming(context))
        AttaPush.dismiss(context, call.callId)
        assertNull(AttaPush.pendingIncoming(context))
        prefs.edit().remove("ended_call_alert_id").commit()
        AttaPush.rememberIncoming(context, call)
        prefs.edit().putString("username", "another-account").commit()
        assertNull(AttaPush.pendingIncoming(context))
        prefs.edit().putString("username", "receiver").commit()
        AttaPush.rememberIncoming(context, call.copy(createdAt = System.currentTimeMillis() - 46000))
        assertNull(AttaPush.pendingIncoming(context))
    }
    @Test fun openingADelayedInvitationNeverRestartsItsFortyFiveSecondLifetime() {
        assertEquals(45_000L, remainingIncomingCallMillis(100_000L, 100_000L))
        assertEquals(5_000L, remainingIncomingCallMillis(100_000L, 140_000L))
        assertEquals(0L, remainingIncomingCallMillis(100_000L, 145_001L))
    }

}
