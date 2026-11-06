package com.alatta.nativeapp

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android test of the Gold-first bridge's host UI, without proprietary
 * Gold DEX. Does not claim to test Gold playback, native libs, or Media push.
 */
@RunWith(AndroidJUnit4::class)
class GoldFirstHubDeviceTest {
    @Test fun hubOpensOnAndroidAndBothTargetsUseTheHostPackage() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val context = instr.targetContext
        val monitor = instr.addMonitor(GoldFirstHubActivity::class.java.name, null, false)
        try {
            context.startActivity(
                Intent(context, GoldFirstHubActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val activity = instr.waitForMonitorWithTimeout(monitor, 15000)
            assertNotNull("Gold-first hub did not start on an Android emulator", activity)
            instr.waitForIdleSync()
            instr.runOnMainSync {
                val root = activity!!.findViewById<android.view.ViewGroup>(android.R.id.content)
                assertNotNull("Gold-first content container missing", root)
                assertTrue("Gold-first content never rendered", root.childCount > 0)
                assertEquals(context.packageName,
                    GoldFirstTargets.goldPlayerIntent(context.packageName).component!!.packageName)
                assertEquals(context.packageName,
                    GoldFirstTargets.mediaIntent(context.packageName).component!!.packageName)
                activity.finish()
            }
        } finally {
            instr.removeMonitor(monitor)
        }
    }
}
