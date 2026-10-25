package com.alatta.nativeapp

import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class RecordingCallMessagingService : AttaFirebaseMessagingService() {
    val received = mutableListOf<RemoteMessage>()
    override fun onMessageReceived(message: RemoteMessage) { received += message }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], application = Application::class)
class FirebaseCallRoutingTest {
    private fun invitation(prefix: String = "gcm.n.") =
        Intent("com.google.android.c2dm.intent.RECEIVE")
            .putExtra("type", "call-request")
            .putExtra("sender", "caller").putExtra("to", "receiver")
            .putExtra("callId", "background-logo-165").putExtra("callType", "audio")
            .putExtra("fromName", "عمر عطا")
            .putExtra("createdAt", System.currentTimeMillis().toString())
            .putExtra("google.message_id", "logo-165-${Build.VERSION.SDK_INT}-${System.nanoTime()}")
            .putExtra("google.original_priority", "high")
            .putExtra("google.ttl", 45)
            .putExtra(prefix + "e", "1")
            .putExtra(prefix + "title", "عمر عطا")
            .putExtra(prefix + "icon", "atta_notification_icon")

    @Test fun lockedBackgroundNotificationAndDataReachExistingMessageCallbackExactlyOnce() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsKeyguardLocked(true)
        val controller = Robolectric.buildService(RecordingCallMessagingService::class.java).create()
        try {
            val service = controller.get()
            val original = invitation()
            service.handleIntent(original)
            assertEquals("SDK auto-display must not swallow a background call", 1, service.received.size)
            assertEquals("عمر عطا", service.received.single().data["fromName"])
            assertEquals("background-logo-165", service.received.single().data["callId"])
            assertEquals("caller", service.received.single().data["sender"])
            assertEquals("receiver", service.received.single().data["to"])
            // Firebase's own message-ID deduplication must still run.
            service.handleIntent(original)
            assertEquals(1, service.received.size)
            assertEquals("1", original.getStringExtra("gcm.n.e"))
        } finally { controller.destroy() }
    }

    @Test fun legacyNotificationPrefixPreservesDeliveryDataPriorityAndExpiry() {
        val original = invitation("gcm.notification.")
        val routed = brandedIncomingCallIntent(original)
        assertNotSame(original, routed)
        assertFalse(routed.hasExtra("gcm.notification.e"))
        assertFalse(routed.hasExtra("gcm.notification.icon"))
        assertEquals("1", original.getStringExtra("gcm.notification.e"))
        assertEquals(original.getStringExtra("google.message_id"), routed.getStringExtra("google.message_id"))
        assertEquals("high", routed.getStringExtra("google.original_priority"))
        assertEquals(45, routed.getIntExtra("google.ttl", 0))
        assertEquals(original.getStringExtra("createdAt"), routed.getStringExtra("createdAt"))
        assertEquals("audio", routed.getStringExtra("callType"))
    }

    @Test fun dataOnlyCallsOtherMessagesAndTokenRefreshAreUntouched() {
        val dataOnly = Intent("com.google.android.c2dm.intent.RECEIVE").putExtra("type", "call-request")
        assertSame(dataOnly, brandedIncomingCallIntent(dataOnly))
        for (type in listOf("chat", "hangup", "call-reject")) {
            val message = invitation().putExtra("type", type)
            assertSame(message, brandedIncomingCallIntent(message))
            assertEquals("1", message.getStringExtra("gcm.n.e"))
        }
        val token = Intent("com.google.firebase.messaging.NEW_TOKEN").putExtra("token", "test-token")
        assertSame(token, brandedIncomingCallIntent(token))
    }
}
