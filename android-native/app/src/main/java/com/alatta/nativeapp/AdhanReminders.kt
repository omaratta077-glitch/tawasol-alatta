package com.alatta.nativeapp

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.UUID

internal object PrayerStore {
    fun prefs(context: Context) = context.getSharedPreferences("alatta_prayer", Context.MODE_PRIVATE)
    fun read(context: Context): PrayerSettings = runCatching {
        val value = JSONObject(prefs(context).getString("settings", "{}").orEmpty())
        val place = value.optJSONObject("place")?.let {
            PrayerPlace(it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), it.getString("zone"), it.optString("method", "UMM_AL_QURA")).validate()
        } ?: prayerPlaces[2]
        val offsets = value.optJSONObject("offsets")
        val selected = value.optJSONArray("prayers")
        PrayerSettings(place, value.optBoolean("confirmed"), value.optBoolean("enabled"),
            value.optString("method", place.method).takeIf { it in prayerMethods } ?: place.method,
            value.optBoolean("hanafi"),
            selected?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it in alarmPrayerIds }.toSet() } ?: alarmPrayerIds,
            alarmPrayerIds.associateWith { offsets?.optInt(it)?.coerceIn(-30, 30) ?: 0 },
            value.optString("soundUri").takeIf { it.isEmpty() || Uri.parse(it).scheme == "content" }.orEmpty())
    }.getOrElse { PrayerSettings() }

    fun save(context: Context, value: PrayerSettings) {
        value.place.validate()
        val place = JSONObject().put("name", value.place.name).put("lat", value.place.latitude)
            .put("lon", value.place.longitude).put("zone", value.place.zone).put("method", value.place.method)
        val json = JSONObject().put("place", place).put("confirmed", value.confirmedPlace)
            .put("enabled", value.enabled).put("method", value.method).put("hanafi", value.hanafi)
            .put("prayers", org.json.JSONArray(value.prayers.toList()))
            .put("offsets", JSONObject(value.offsets)).put("soundUri", value.soundUri)
        prefs(context).edit().putString("settings", json.toString()).apply()
    }
}

internal object AdhanScheduler {
    const val ACTION_ALARM = "com.alatta.nativeapp.PRAYER_ALARM"
    const val ACTION_OPEN = "com.alatta.nativeapp.OPEN_PRAYERS"
    const val CHANNEL = "alatta_adhan_v1"
    fun exactAllowed(context: Context) = Build.VERSION.SDK_INT < 31 ||
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    fun notificationAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).getNotificationChannel(CHANNEL)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun createChannel(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "مواقيت الصلاة والأذان", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "أذان الصلوات التي تختارها؛ يمكنك إيقاف الصوت من التنبيه"
                setSound(null, null)
            })
    }
    private fun alarmIntent(context: Context, generation: String = "", moment: PrayerMoment? = null): PendingIntent =
        PendingIntent.getBroadcast(context, 8310, Intent(context, AdhanAlarmReceiver::class.java).setAction(ACTION_ALARM)
            .putExtra("generation", generation).putExtra("at", moment?.at ?: 0L).putExtra("prayer", moment?.id.orEmpty()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** One future alarm, recalculated after every event/change; no permanent background polling. */
    fun schedule(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(alarmIntent(context))
        val preferences = PrayerStore.prefs(context)
        preferences.edit().remove("generation").remove("scheduled_at").remove("scheduled_prayer").apply()
        val settings = PrayerStore.read(context)
        if (!settings.enabled || !settings.confirmedPlace || !exactAllowed(context) || !notificationAllowed(context)) return false
        return runCatching {
            val next = PrayerCalculation.next(settings, now, true) ?: return false
            val generation = UUID.randomUUID().toString()
            preferences.edit().putString("generation", generation).putLong("scheduled_at", next.at)
                .putString("scheduled_prayer", next.id).apply()
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, alarmIntent(context, generation, next))
            true
        }.getOrElse {
            preferences.edit().remove("generation").remove("scheduled_at").remove("scheduled_prayer").apply()
            false
        }
    }
    fun update(context: Context, settings: PrayerSettings): Boolean {
        PrayerStore.save(context, settings)
        if (!settings.enabled) context.stopService(Intent(context, AdhanPlaybackService::class.java))
        return schedule(context)
    }
    fun disable(context: Context) { update(context, PrayerStore.read(context).copy(enabled = false)) }
    fun restore(context: Context) { if (PrayerStore.read(context).enabled) schedule(context) }
    fun preview(context: Context) {
        if (notificationAllowed(context)) ContextCompat.startForegroundService(context,
            Intent(context, AdhanPlaybackService::class.java).setAction(AdhanPlaybackService.PREVIEW))
    }
}

class AdhanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdhanScheduler.ACTION_ALARM) return
        val settings = PrayerStore.read(context)
        val prefs = PrayerStore.prefs(context)
        val at = intent.getLongExtra("at", 0)
        val prayer = intent.getStringExtra("prayer").orEmpty()
        val valid = acceptsPrayerAlarm(settings,
            PrayerAlarmTicket(prayer, at, intent.getStringExtra("generation").orEmpty()),
            PrayerAlarmTicket(prefs.getString("scheduled_prayer", "").orEmpty(), prefs.getLong("scheduled_at", -1), prefs.getString("generation", "").orEmpty()),
            System.currentTimeMillis(), AdhanScheduler.notificationAllowed(context))
        AdhanScheduler.schedule(context)
        if (valid) runCatching {
            ContextCompat.startForegroundService(context, Intent(context, AdhanPlaybackService::class.java)
                .setAction(AdhanPlaybackService.PLAY).putExtra("prayer", prayer))
        }
    }
}

class AdhanRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            // Boot only restores the next alarm; never starts audio from BOOT_COMPLETED.
            AdhanScheduler.restore(context)
        }
    }
}

class AdhanPlaybackService : Service() {
    companion object {
        const val PLAY = "com.alatta.nativeapp.PLAY_ADHAN"
        const val PREVIEW = "com.alatta.nativeapp.PREVIEW_ADHAN"
        const val STOP = "com.alatta.nativeapp.STOP_ADHAN"
        val playing = MutableStateFlow(false)
        private const val NOTIFICATION_ID = 8311
    }
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == STOP) { stopSelf(); return START_NOT_STICKY }
        val settings = PrayerStore.read(this)
        val prayer = intent?.getStringExtra("prayer").orEmpty()
        if ((action != PREVIEW && (action != PLAY || !settings.enabled || prayer !in settings.prayers)) ||
            !AdhanScheduler.notificationAllowed(this)) { stopSelf(); return START_NOT_STICKY }
        releasePlayer()
        AdhanScheduler.createChannel(this)
        val stop = PendingIntent.getService(this, 8312, Intent(this, AdhanPlaybackService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 8313, Intent(this, MainActivity::class.java).setAction(AdhanScheduler.ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (action == PREVIEW) "تجربة صوت الأذان" else "حان وقت صلاة ${prayerNames[prayer].orEmpty()}"
        val notification = NotificationCompat.Builder(this, AdhanScheduler.CHANNEL)
            .setSmallIcon(R.drawable.atta_ic_bell).setContentTitle(title)
            .setContentText("${settings.place.name} • اضغط إيقاف لإيقاف الصوت")
            .setContentIntent(open).setDeleteIntent(stop).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM).addAction(R.drawable.atta_ic_x, "إيقاف الأذان", stop).build()
        startForeground(NOTIFICATION_ID, notification)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                if (change < 0) stopSelf()
            }.build()
        focus = request
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { stopSelf(); return START_NOT_STICKY }
        runCatching {
            val sound = MediaPlayer()
            player = sound
            sound.setAudioAttributes(attributes)
            sound.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            sound.setOnCompletionListener { stopSelf() }
            sound.setOnErrorListener { _, _, _ -> stopSelf(); true }
            fun bundledSound() {
                resources.openRawResourceFd(R.raw.atta_adhan).use { descriptor ->
                    sound.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                }
            }
            if (settings.soundUri.isNotBlank()) {
                runCatching { sound.setDataSource(this, Uri.parse(settings.soundUri)) }.getOrElse {
                    sound.reset(); sound.setAudioAttributes(attributes); bundledSound()
                }
            } else bundledSound()
            sound.setOnPreparedListener { prepared -> prepared.start(); playing.value = true }
            sound.prepareAsync()
            handler.postDelayed({ stopSelf() }, 10 * 60_000L)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }
    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        player?.run { runCatching { stop() }; release() }; player = null
        focus?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focus = null; playing.value = false
    }
    override fun onDestroy() { releasePlayer(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
