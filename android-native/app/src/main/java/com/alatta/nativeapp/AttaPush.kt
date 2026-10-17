package com.alatta.nativeapp

import android.Manifest
import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// Firebase's Android identifiers are public configuration, NOT a service-account
// credential. The server-side service account remains a Cloudflare secret.
class AttaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) { started++; AttaPush.appVisible = started > 0 }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                AttaPush.appVisible = started > 0
            }
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        AttaPush.createChannel(this)
        if (AttaPush.configured()) {
            try {
                val options = FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.PUSH_APP_ID)
                    .setGcmSenderId(BuildConfig.PUSH_SENDER_ID)
                    .setProjectId(BuildConfig.PUSH_PROJECT_ID)
                    .setApiKey(BuildConfig.PUSH_API_KEY)
                    .build()
                if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this, options)
            } catch (error: Exception) {
                android.util.Log.e("AttaPush", "Firebase initialization failed", error)
            }
        }
    }
}

internal data class PushCall(
    val from: String, val to: String, val callId: String, val type: String,
    val createdAt: Long, val action: String
) {
    companion object {
        fun fromIntent(intent: Intent): PushCall? {
            val from = intent.getStringExtra("atta_call_from")?.trim()?.lowercase().orEmpty()
            val to = intent.getStringExtra("atta_call_to")?.trim()?.lowercase().orEmpty()
            val callId = intent.getStringExtra("atta_call_id")?.trim().orEmpty()
            if (from.isBlank() || to.isBlank() || callId.length !in 8..128) return null
            return PushCall(from, to, callId,
                if (intent.getStringExtra("atta_call_type") == "video") "video" else "audio",
                intent.getLongExtra("atta_call_created", 0L),
                intent.getStringExtra("atta_call_action") ?: AttaPush.ACTION_VIEW)
        }
    }
}

internal object AttaPush {
    @Volatile var appVisible = false
    // A visible incoming-call dialog rings on its own; suppress only that
    // notification, not all calls just because the Activity is STARTED.
    @Volatile var foregroundRingCallId = ""
    const val ACTION_OPEN_CALL = "com.alatta.nativeapp.OPEN_CALL"
    const val ACTION_REJECT = "com.alatta.nativeapp.REJECT_CALL"
    const val ACTION_ACCEPT = "accept"
    const val ACTION_VIEW = "view"
    private const val BASE = "https://tawasol-alatta.omaratta077-4fd.workers.dev"
    private const val CHANNEL = "alatta_incoming_call_v2"
    private const val CHAT_CHANNEL = "alatta_messages_v1"
    private const val SOCIAL_CHANNEL = "alatta_social_v1"
    const val ACTION_OPEN_NOTIFICATION = "com.alatta.nativeapp.OPEN_NOTIFICATION"
    private val io = Executors.newSingleThreadExecutor()

    fun configured() = BuildConfig.PUSH_APP_ID.isNotBlank() &&
        BuildConfig.PUSH_SENDER_ID.isNotBlank() && BuildConfig.PUSH_PROJECT_ID.isNotBlank() &&
        BuildConfig.PUSH_API_KEY.isNotBlank()

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val channel = NotificationChannel(CHANNEL, "مكالمات تواصل العطا الواردة", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تنبيهات صوتية وفيديو عند وصول المكالمة"
            enableVibration(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attrs)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        // Dedicated channels let the user choose separate sounds for messages and social alerts.
        manager.createNotificationChannel(NotificationChannel(
            CHAT_CHANNEL, "رسائل تواصل العطا", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "رسائل جديدة في المحادثات"; enableVibration(true) })
        manager.createNotificationChannel(NotificationChannel(
            SOCIAL_CHANNEL, "تفاعلات وطلبات الصداقة", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "طلبات الصداقة والإعجابات والتعليقات" })
    }

    private fun prefs(context: Context) = context.getSharedPreferences("alatta_native", 0)
    private fun validSession(context: Context, call: PushCall): Boolean {
        val values = prefs(context)
        return values.getString("username", "")?.lowercase() == call.to &&
            !values.getString("token", "").isNullOrBlank() &&
            System.currentTimeMillis() - call.createdAt in 0..45000L
    }

    // A login token is sent to the app's OWN authenticated API, never to FCM.
    private fun post(context: Context, path: String, body: JSONObject) {
        val url = URL(BASE + path)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("API response $code ($path)")
        } finally { connection.disconnect() }
    }

    fun register(context: Context, session: String, user: String) {
        if (!configured() || session.isBlank() || user.isBlank()) return
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { deviceToken ->
                prefs(context).edit().putString("push_device_token", deviceToken).apply()
                io.execute {
                    runCatching {
                        post(context, "/api/push/register", JSONObject()
                            .put("token", session).put("deviceToken", deviceToken))
                        prefs(context).edit().putLong("push_registered_at", System.currentTimeMillis())
                            .putString("push_registered_user", user.lowercase()).apply()
                    }.onFailure { android.util.Log.w("AttaPush", "Registration failed", it) }
                }
            }.addOnFailureListener { android.util.Log.w("AttaPush", "FCM token unavailable", it) }
        } catch (error: Exception) { android.util.Log.w("AttaPush", "FCM unavailable", error) }
    }

    fun unregister(context: Context, session: String) {
        val device = prefs(context).getString("push_device_token", "").orEmpty()
        if (session.isBlank() || device.isBlank()) return
        io.execute {
            runCatching { post(context, "/api/push/unregister", JSONObject()
                .put("token", session).put("deviceToken", device)) }
        }
    }

    fun dismiss(context: Context, callId: String) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(callId.hashCode())
    }

    private fun intentFor(context: Context, call: PushCall, action: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            this.action = ACTION_OPEN_CALL
            data = Uri.parse("alatta://call/${Uri.encode(call.callId)}/$action")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("atta_call_id", call.callId)
            putExtra("atta_call_from", call.from)
            putExtra("atta_call_to", call.to)
            putExtra("atta_call_type", call.type)
            putExtra("atta_call_created", call.createdAt)
            putExtra("atta_call_action", action)
        }

    // These are intentionally generic summaries; private message bodies never travel
    // in push payloads or appear on the lock screen. Server-authored sender and
    // destination are validated against the logged-in account on this device.
    fun notifyActivity(context: Context, data: Map<String, String>) {
        val type = data["type"].orEmpty()
        val account = prefs(context)
        val recipient = data["to"].orEmpty().lowercase()
        val sender = (data["sender"] ?: data["from"]).orEmpty().trim().take(64)
        val eventId = data["eventId"].orEmpty()
        val allowed = setOf("chat", "friend-request", "friend-accepted", "like",
            "reaction", "comment", "comment-reply", "follow", "share", "push-test")
        if (type !in allowed || eventId.length !in 8..128 || recipient.isBlank() ||
            recipient != account.getString("username", "")?.lowercase() ||
            account.getString("token", "").isNullOrBlank()) return
        // Foreground already has the WebSocket and in-app notification stream.
        if (appVisible && type != "push-test") return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        createChannel(context)
        val title = when (type) {
            "push-test" -> "اختبار إشعارات تواصل العطا"
            "chat" -> "رسالة جديدة"
            "friend-request" -> "طلب صداقة جديد"
            "friend-accepted" -> "تم قبول طلب الصداقة"
            "like" -> "إعجاب جديد"
            "reaction" -> "تفاعل جديد"
            "comment", "comment-reply" -> "تعليق جديد"
            "follow" -> "متابع جديد"
            else -> "مشاركة جديدة"
        }
        val message = if (type == "push-test") "وصل إشعار Firebase إلى جهازك بنجاح"
            else if (sender.isNotBlank()) "من $sender على تواصل العطا" else "لديك إشعار جديد في تواصل العطا"
        val tab = if (type == "chat") "الرسائل" else if (type == "friend-request" ||
            type == "friend-accepted") "الأصدقاء" else "الإشعارات"
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_NOTIFICATION
            this.data = Uri.parse("alatta://notification/${Uri.encode(eventId)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("atta_notification_tab", tab)
        }
        val pending = PendingIntent.getActivity(context, eventId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(context,
                if (type == "chat") CHAT_CHANNEL else SOCIAL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(if (type == "chat") NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(if (type == "chat") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setContentIntent(pending)
        // Use the event's stable ID to avoid showing the same event twice after retries.
        NotificationManagerCompat.from(context).notify(eventId.hashCode(), notice.build())
    }

    fun incoming(context: Context, call: PushCall) {
        if (!validSession(context, call)) return
        // Foreground calls already have a native dialog + ringtone through the
        // authenticated WebSocket/HTTP path. Never ring the same device twice.
        if (appVisible && foregroundRingCallId == call.callId) return
        createChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val code = call.callId.hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val view = PendingIntent.getActivity(context, code, intentFor(context, call, ACTION_VIEW), flags)
        val accept = PendingIntent.getActivity(context, code + 1, intentFor(context, call, ACTION_ACCEPT), flags)
        val rejectIntent = Intent(context, AttaCallRejectReceiver::class.java).apply {
            action = ACTION_REJECT
            data = Uri.parse("alatta://reject/${Uri.encode(call.callId)}")
            putExtra("atta_call_id", call.callId)
            putExtra("atta_call_from", call.from)
            putExtra("atta_call_to", call.to)
            putExtra("atta_call_created", call.createdAt)
        }
        val reject = PendingIntent.getBroadcast(context, code + 2, rejectIntent, flags)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(if (call.type == "video") "مكالمة فيديو واردة" else "مكالمة صوتية واردة")
            .setContentText("مكالمة واردة من مستخدم تواصل العطا")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(view)
            .setTimeoutAfter(45000L)
            .setOngoing(false)
            .addAction(android.R.drawable.sym_action_call, "رد", accept)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "رفض", reject)
        NotificationManagerCompat.from(context).notify(code, builder.build())
        // The caller should hear ringback only after the phone actually displayed
        // an incoming call notification (or the existing foreground dialog).
        val session = prefs(context).getString("token", "").orEmpty()
        io.execute {
            runCatching { post(context, "/api/call-signal", JSONObject()
                .put("token", session).put("to", call.from)
                .put("signal", JSONObject().put("type", "call-ringing").put("callId", call.callId))) }
        }
    }

    fun reject(context: Context, call: PushCall) {
        dismiss(context, call.callId)
        if (!validSession(context, call)) return
        val session = prefs(context).getString("token", "").orEmpty()
        post(context, "/api/call-signal", JSONObject()
            .put("token", session).put("to", call.from)
            .put("signal", JSONObject().put("type", "call-reject")
                .put("reason", "rejected").put("callId", call.callId)))
    }
}

class AttaCallRejectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AttaPush.ACTION_REJECT) return
        val from = intent.getStringExtra("atta_call_from").orEmpty()
        val to = intent.getStringExtra("atta_call_to").orEmpty()
        val id = intent.getStringExtra("atta_call_id").orEmpty()
        if (from.isBlank() || to.isBlank() || id.isBlank()) return
        val call = PushCall(from, to, id, "audio", intent.getLongExtra("atta_call_created", 0L), "reject")
        val pending = goAsync()
        Thread {
            try { AttaPush.reject(context.applicationContext, call) }
            catch (error: Exception) { android.util.Log.w("AttaPush", "Reject failed", error) }
            finally { pending.finish() }
        }.start()
    }
}

class AttaFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(deviceToken: String) {
        super.onNewToken(deviceToken)
        val values = getSharedPreferences("alatta_native", 0)
        values.edit().putString("push_device_token", deviceToken).apply()
        AttaPush.register(applicationContext, values.getString("token", "").orEmpty(),
            values.getString("username", "").orEmpty())
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val d = message.data
        val type = d["type"] ?: return
        if (type !in setOf("call-request", "hangup", "call-reject")) {
            AttaPush.notifyActivity(this, d)
            return
        }
        val id = d["callId"] ?: return
        if (type == "hangup" || type == "call-reject") {
            AttaPush.dismiss(this, id)
            return
        }
        if (type != "call-request") return
        val from = (d["sender"] ?: d["from"]).orEmpty().trim().lowercase()
        val to = d["to"].orEmpty().trim().lowercase()
        val created = d["createdAt"]?.toLongOrNull() ?: return
        if (from.isBlank() || to.isBlank() || id.length !in 8..128) return
        AttaPush.incoming(this, PushCall(from, to, id,
            if (d["callType"] == "video") "video" else "audio", created, AttaPush.ACTION_VIEW))
    }
}
