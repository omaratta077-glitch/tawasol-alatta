package com.alatta.nativeapp
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class IncomingCallDeviceTest {
    @Test fun incomingDialogOpensAndRelaunchesWithoutClosingTheApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        fun contains(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean {
            if (node == null) return false
            if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true) return true
            return (0 until node.childCount).any { contains(node.getChild(it), text) }
        }
        repeat(2) {
            val intent = Intent(instrumentation.targetContext, CallDialogTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity = instrumentation.startActivitySync(intent)
            try {
                instrumentation.waitForIdleSync()
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
                val deadline = android.os.SystemClock.uptimeMillis() + 5000L
                while (!contains(instrumentation.uiAutomation.rootInActiveWindow, "عمر عطا") && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
                val root = instrumentation.uiAutomation.rootInActiveWindow
                assertNotNull(root)
                assertTrue("Caller name must be visible in the production dialog", contains(root, "عمر عطا"))
                assertTrue("Answer control must be visible", contains(root, "رد"))
                assertTrue("Decline control must be visible", contains(root, "رفض"))
            } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
        }
    }
}
