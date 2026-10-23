package com.alatta.nativeapp

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AdhanReminderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clearSettings() { PrayerStore.prefs(context).edit().clear().commit() }
    @Test fun automaticLocationAndLockScreenChoicesSurviveReloadWithoutChangingVoice() {
        val settings = PrayerSettings(confirmedPlace = true, enabled = true, automaticLocation = true,
            lockScreen = false, offsets = alarmPrayerIds.associateWith { 0 }, voiceId = "chosen", voiceName = "مؤذن", voiceSha256 = "a".repeat(64))
        PrayerStore.save(context, settings)
        assertEquals(settings, PrayerStore.read(context))
        PrayerStore.prefs(context).edit().putString("settings", "{\"enabled\":false}").commit()
        assertFalse(PrayerStore.read(context).automaticLocation)
        assertTrue(PrayerStore.read(context).lockScreen)
    }
    @Test fun prayerAlarmHasPublicLockScreenNotificationAndStopActionButPreviewNeverWakesDisplay() {
        AdhanScheduler.createChannel(context)
        val settings = PrayerSettings(confirmedPlace = true, enabled = true)
        val alarm = adhanNotification(context, settings, "fajr", false)
        assertEquals(Notification.VISIBILITY_PUBLIC, alarm.visibility)
        assertEquals(Notification.CATEGORY_ALARM, alarm.category)
        assertNotNull(alarm.fullScreenIntent)
        assertNotNull(alarm.actions.single().actionIntent)
        assertNull(adhanNotification(context, settings, "", true).fullScreenIntent)
        assertNull(adhanNotification(context, settings.copy(lockScreen = false), "fajr", false).fullScreenIntent)
    }
    @Test fun disabledLegacyAdhanNotificationsAreNeverBypassedDuringChannelMigration() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.deleteNotificationChannel(AdhanScheduler.CHANNEL)
        manager.createNotificationChannel(NotificationChannel("alatta_adhan_v1", "الأذان", NotificationManager.IMPORTANCE_NONE))
        AdhanScheduler.createChannel(context)
        assertFalse(AdhanScheduler.notificationAllowed(context))
        assertNull(manager.getNotificationChannel(AdhanScheduler.CHANNEL))
    }
}
