package com.alatta.nativeapp

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallWindowDeviceTest {
    @Test fun optedInCardStaysAboveHomeAndClosesOnCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        fun shell(command: String) { ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() } }
        fun contains(node: AccessibilityNodeInfo?, value: String): Boolean {
            if (node == null) return false
            if (node.text?.toString() == value || node.contentDescription?.toString() == value) return true
            return (0 until node.childCount).any { contains(node.getChild(it), value) }
        }
        val previousInfo = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        val invitation = PushCall("testcaller", "testreceiver", "device-call-window-162", "audio", System.currentTimeMillis(), "view", "متصل الاختبار")
        try {
            shell("wm dismiss-keyguard")
            shell("input keyevent KEYCODE_HOME")
            context.getSharedPreferences("alatta_native", 0).edit().putString("username", invitation.to).putString("token", "test-session").commit()
            AttaPrivacy.accept(context)
            instrumentation.runOnMainSync { AttaPush.rememberIncoming(context, invitation); AttaPush.createChannel(context); assertTrue(CallWindow.enabled(context)); assertTrue(CallWindow.start(context, invitation)) }
            fun visible(value: String) = automation.windows.any { contains(it.root, value) }
            val deadline = SystemClock.uptimeMillis() + 7000
            while (!visible("متصل الاختبار") && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertTrue("Overlay caller name above Home", visible("متصل الاختبار"))
            assertTrue("Approved full logo", visible("AL-ATTA MEDIA"))
            assertTrue("Answer action", visible("رد")); assertTrue("Reject action", visible("رفض"))
            // Longer than an ordinary transient heads-up notification.
            SystemClock.sleep(6000)
            assertTrue("Card remains while ringing", visible("متصل الاختبار"))
            instrumentation.runOnMainSync { AttaPush.dismiss(context, invitation.callId) }
            val end = SystemClock.uptimeMillis() + 3000
            while (visible("متصل الاختبار") && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
            assertFalse("Cancellation removes overlay", visible("متصل الاختبار"))
        } finally {
            instrumentation.runOnMainSync { AttaPush.dismiss(context, invitation.callId) }
            context.getSharedPreferences("alatta_native", 0).edit().clear().commit()
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            automation.serviceInfo = previousInfo
        }
    }
}
