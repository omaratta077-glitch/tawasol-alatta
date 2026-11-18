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
    @Test fun mediaEntryCanStartItsRealActivityOnAndroid() {
        // This checks Media's real native MainActivity startup on an Android
        // emulator. In a Gold-first private merged APK the process is :media;
        // public CI does not contain the Gold binary or altered Manifest.
        val instr = InstrumentationRegistry.getInstrumentation()
        val context = instr.targetContext
        val target = GoldFirstTargets.mediaIntent(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = context.packageManager.resolveActivity(target, 0)
        assertNotNull("Media MainActivity is not registered in the target package", resolved)
        val monitor = instr.addMonitor(MainActivity::class.java.name, null, false)
        try {
            context.startActivity(target)
            val activity = instr.waitForMonitorWithTimeout(monitor, 20000)
            assertNotNull("Media MainActivity could not start", activity)
            instr.waitForIdleSync()
            instr.runOnMainSync {
                val content = activity!!.findViewById<android.view.ViewGroup>(android.R.id.content)
                assertNotNull("Media MainActivity root is missing", content)
                assertTrue("Media MainActivity has no content", content.childCount > 0)
                activity.finish()
            }
        } finally {
            instr.removeMonitor(monitor)
        }
    }

}
