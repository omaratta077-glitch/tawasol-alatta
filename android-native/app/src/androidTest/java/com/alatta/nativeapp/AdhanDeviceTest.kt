package com.alatta.nativeapp

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdhanDeviceTest {
    @Test fun scheduledPrayerBuildsRealAndroidAlarmWithLockScreenAndStopControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val old = PrayerStore.read(context)
        try {
            val settings = old.copy(confirmedPlace = true, enabled = true, automaticLocation = true, lockScreen = true)
            PrayerStore.save(context, settings)
            assertEquals(settings, PrayerStore.read(context))
            AdhanScheduler.createChannel(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(AdhanScheduler.CHANNEL).importance)
            val alarm = adhanNotification(context, settings, "dhuhr", false)
            assertEquals(Notification.VISIBILITY_PUBLIC, alarm.visibility)
            assertNotNull(alarm.fullScreenIntent)
            assertNotNull(alarm.actions.single().actionIntent)
            assertNull(adhanNotification(context, settings, "", true).fullScreenIntent)
            assertNull(adhanNotification(context, settings.copy(lockScreen = false), "dhuhr", false).fullScreenIntent)
        } finally { PrayerStore.save(context, old) }
    }
    @Test fun prayerAlarmWakesLockedDisplayAndClosesWhenAdhanStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val monitor = instrumentation.addMonitor(AdhanAlarmActivity::class.java.name, null, false)
        val settings = PrayerStore.read(context).copy(enabled = true, confirmedPlace = true, lockScreen = true)
        try {
            AdhanScheduler.createChannel(context)
            AdhanPlaybackService.activePrayer.value = "fajr"
            instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
            android.os.SystemClock.sleep(500)
            manager.notify(8399, adhanNotification(context, settings, "fajr", false))
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
            assertNotNull("The scheduled alarm must open its lock-screen activity", activity)
            instrumentation.waitForIdleSync()
            val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            assertTrue("The alarm screen must wake the display", power.isInteractive)
            AdhanPlaybackService.activePrayer.value = ""
            val deadline = android.os.SystemClock.uptimeMillis() + 5000L
            while (activity != null && !activity.isFinishing && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
            assertTrue("The alarm screen closes when playback ends", requireNotNull(activity).isFinishing)
        } finally {
            AdhanPlaybackService.activePrayer.value = ""
            manager.cancel(8399)
            instrumentation.removeMonitor(monitor)
            instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        }
    }

}
