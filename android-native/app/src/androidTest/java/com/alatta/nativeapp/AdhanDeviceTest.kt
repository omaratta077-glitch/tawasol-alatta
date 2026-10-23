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
}
