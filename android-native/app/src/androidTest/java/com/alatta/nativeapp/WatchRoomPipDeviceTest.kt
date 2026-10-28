package com.alatta.nativeapp

import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchRoomPipDeviceTest {
    @Test fun creatorShowsParticipantsOverAnExternalPlayerWithoutALeaveHint() = exercise(creator = true)
    @Test fun guestShowsParticipantsOverAnExternalPlayerWithoutALeaveHint() = exercise(creator = false)
    @Test fun creatorCanShowTheWindowBeforeTheFirstGuestArrives() = exercise(creator = true, alone = true)
    @Test fun deniedPipPermissionKeepsTheRoomAndDoesNotOpenThePlayer() = exercise(creator = true, denied = true)

    private fun exercise(creator: Boolean, alone: Boolean = false, denied: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        fun shell(command: String) { ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
        shell("wm dismiss-keyguard")
        shell("appops set ${context.packageName} PICTURE_IN_PICTURE ${if (denied) "ignore" else "allow"}")
        val activity = instrumentation.startActivitySync(Intent(context, WatchRoomPipTestActivity::class.java)
            .putExtra("creator", creator).putExtra("alone", alone).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WatchRoomPipTestActivity
        try {
            instrumentation.waitForIdleSync()
            var focused = false
            val focusDeadline = SystemClock.uptimeMillis() + 5000L
            while (!focused && SystemClock.uptimeMillis() < focusDeadline) {
                instrumentation.runOnMainSync { focused = activity.hasWindowFocus() && activity.window.decorView.width > 0 }
                if (!focused) SystemClock.sleep(50)
            }
            assertTrue("The user-visible room must be ready before pressing Play", focused)
            instrumentation.runOnMainSync { activity.launchFixturePlayer() }
            val deadline = SystemClock.uptimeMillis() + 8000L
            while (SystemClock.uptimeMillis() < deadline && (!activity.launchFinished ||
                (!denied && (!WatchRoomExternalTestActivity.visible || activity.pipWidth == 0)))) SystemClock.sleep(50)
            instrumentation.runOnMainSync {
                assertTrue("PiP preparation must finish", activity.launchFinished)
                assertTrue("The call remains active", WatchRoomController.active)
                if (denied) {
                    assertEquals(WatchRoomPip.Failure.PERMISSION, activity.lastFailure)
                    assertFalse(activity.playerLaunched)
                    assertFalse(activity.isInPictureInPictureMode)
                } else {
                    assertNull("Both creator and guest must enter PiP", activity.lastFailure)
                    assertTrue(activity.playerLaunched)
                    assertTrue("The participant window remains in actual Android PiP", activity.isInPictureInPictureMode)
                    assertTrue("The separate player task is visible", WatchRoomExternalTestActivity.visible)
                    assertTrue("Production participant grid has visible bounds", activity.pipWidth > 0 && activity.pipHeight > 0)
                    assertEquals(if (creator) "fixture-owner" else "fixture-guest", WatchRoomController.room?.myPeerId)
                }
            }
        } finally {
            instrumentation.runOnMainSync { WatchRoomExternalTestActivity.current?.get()?.finish(); activity.finish() }
            instrumentation.waitForIdleSync()
            shell("appops set ${context.packageName} PICTURE_IN_PICTURE default")
        }
    }
}
