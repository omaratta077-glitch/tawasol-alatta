package com.alatta.nativeapp

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

internal object WatchRoomController {
    var active by mutableStateOf(false)
    var ready by mutableStateOf(false)
    var closing by mutableStateOf(false)
    var room by mutableStateOf<WatchRoom?>(null)
    var status by mutableStateOf("")
    var muted by mutableStateOf(false)
    var cameraOff by mutableStateOf(false)
    var volume by mutableFloatStateOf(1f)
    var expanded by mutableStateOf(true)
    var engine by mutableStateOf<WatchRoomEngine?>(null)
    fun begin(context: Context, owner: String, action: String, code: String, channelId: String) {
        if (active || closing) return
        if (AttaPush.activeCallId.isNotBlank()) { status = "غادر المكالمة الحالية أولًا"; return }
        active = true; ready = false; expanded = true; status = "جارٍ دخول غرفة المشاهدة"
        try { ContextCompat.startForegroundService(context, Intent(context, WatchRoomService::class.java)
            .putExtra("owner", owner).putExtra("action", action).putExtra("code", code)
            .putExtra("channel", channelId).putExtra("client", UUID.randomUUID().toString())) }
        catch (_: Exception) { active = false; status = "تعذر بدء المكالمة. افتح العطا وراجع أذونات الكاميرا والميكروفون." }
    }
    fun stop(context: Context) { context.stopService(Intent(context, WatchRoomService::class.java)) }
    fun toggleMute() { muted = !muted; engine?.mute(muted) }
    fun toggleCamera() { cameraOff = !cameraOff; engine?.camera(cameraOff) }
    fun adjustVolume(value: Float) { volume = value.coerceIn(0f, 1f); engine?.volume(volume) }
    fun signal(packet: org.json.JSONObject) { engine?.enqueue(packet) }
}

/** Explicitly joined foreground camera/mic call; it survives changing tabs and opening the channel player. */
class WatchRoomService : Service() {
    private var engine: WatchRoomEngine? = null
    private var busyId = ""
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) { WatchRoomController.status = "غادرت غرفة المشاهدة"; stopSelf(); return START_NOT_STICKY }
        if (engine != null) return START_NOT_STICKY
        val prefs = getSharedPreferences("alatta_native", Context.MODE_PRIVATE)
        val token = prefs.getString("token", "").orEmpty()
        val owner = prefs.getString("username", "").orEmpty()
        val permitted = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (intent == null || !permitted || token.isBlank() || owner != intent.getStringExtra("owner")) {
            WatchRoomController.status = "راجع تسجيل الدخول وأذونات الكاميرا والميكروفون"; stopSelf(); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "مكالمة غرفة المشاهدة", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 186, Intent(this, MainActivity::class.java).setAction(ACTION_OPEN),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val leave = PendingIntent.getService(this, 187, Intent(this, WatchRoomService::class.java).setAction(ACTION_LEAVE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.atta_notification_icon)
                .setContentTitle("غرفة المشاهدة • AL-ATTA").setContentText("مكالمة فيديو وصوت جارية • اضغط للعودة")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_CALL)
                .addAction(R.drawable.atta_ic_phone_off, "مغادرة", leave).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startForeground(NOTIFICATION, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification)
            engine = WatchRoomEngine(applicationContext, token, intent.getStringExtra("client").orEmpty()) { stopSelf() }
            busyId = "watch:" + intent.getStringExtra("client").orEmpty()
            AttaPush.activeCallId = busyId
            WatchRoomController.engine = engine
            engine?.start(intent.getStringExtra("action") ?: "join", intent.getStringExtra("code").orEmpty(), intent.getStringExtra("channel").orEmpty())
        } catch (_: Exception) { WatchRoomController.status = "تعذر تشغيل المكالمة؛ راجع أذونات الكاميرا والميكروفون"; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        WatchRoomController.closing = engine != null
        engine?.stop {
            WatchRoomController.closing = false
            if (AttaPush.activeCallId == busyId) AttaPush.activeCallId = ""
        }
        engine = null
        WatchRoomController.engine = null; WatchRoomController.room = null
        WatchRoomController.active = false; WatchRoomController.ready = false
        WatchRoomController.muted = false; WatchRoomController.cameraOff = false; WatchRoomController.volume = 1f
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        internal const val ACTION_OPEN = "com.alatta.nativeapp.WATCH_ROOM"
        private const val ACTION_LEAVE = "com.alatta.nativeapp.WATCH_ROOM_LEAVE"
        private const val CHANNEL = "alatta_watch_room"
        private const val NOTIFICATION = 48186
    }
}
