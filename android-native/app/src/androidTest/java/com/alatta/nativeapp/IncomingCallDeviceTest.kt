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
        repeat(2) {
            val intent = Intent(instrumentation.targetContext, CallDialogTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity = instrumentation.startActivitySync(intent)
            try {
                instrumentation.waitForIdleSync()
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
                val root = instrumentation.uiAutomation.rootInActiveWindow
                assertNotNull(root)
                assertTrue(root.findAccessibilityNodeInfosByText("عمر عطا").isNotEmpty())
                assertTrue(root.findAccessibilityNodeInfosByText("رد").isNotEmpty())
                assertTrue(root.findAccessibilityNodeInfosByText("رفض").isNotEmpty())
            } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
        }
    }
}
