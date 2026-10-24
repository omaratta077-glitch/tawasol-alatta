package com.alatta.nativeapp

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat

/** Explicitly opted-in call card. Never runs beyond the original invitation deadline. */
internal object CallWindow {
    fun enabled(context: Context) = Settings.canDrawOverlays(context)
    fun settings(context: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        android.net.Uri.parse("package:" + context.packageName))
    fun start(context: Context, call: PushCall): Boolean = runCatching {
        ContextCompat.startForegroundService(context, AttaPush.intentFor(context, call, AttaPush.ACTION_VIEW)
            .setClass(context, IncomingCallOverlayService::class.java))
        true
    }.getOrDefault(false)
}

/** Same dimensions, colors, logo and RTL action order as the in-app card. */
internal fun callWindowCard(context: Context, call: PushCall, answer: () -> Unit, reject: () -> Unit): View {
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = shape(0xF2202638.toInt(), 24)
        elevation = dp(8).toFloat()
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.logo_premium); contentDescription = "AL-ATTA MEDIA"
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = if (call.type == "video") "مكالمة فيديو واردة" else "مكالمة صوتية واردة"
                    textSize = 14f; setTextColor(Color.WHITE)
                })
                addView(TextView(context).apply {
                    text = call.displayName.ifBlank { "مستخدم" }; textSize = 22f
                    setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        }, LinearLayout.LayoutParams(-1, -2))
        addView(LinearLayout(context).apply {
            fun action(label: String, color: Int, click: () -> Unit) = TextView(context).apply {
                text = label; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                minHeight = dp(48); isClickable = true; isFocusable = true
                background = shape(color, 24); setOnClickListener { click() }
            }
            addView(action("رد", 0xFF239B56.toInt(), answer), LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(Space(context), LinearLayout.LayoutParams(dp(12), 1))
            addView(action("رفض", 0xFFD32F2F.toInt(), reject), LinearLayout.LayoutParams(0, dp(48), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
}

class IncomingCallOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var card: View? = null
    private var call: PushCall? = null
    private var lockScreenShown = false
    private val refresh = object : Runnable {
        override fun run() {
            val invitation = call ?: return
            if (AttaPush.pendingIncoming(this@IncomingCallOverlayService)?.callId != invitation.callId ||
                !AttaPush.canNotify(this@IncomingCallOverlayService)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
            val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
            if (locked) {
                removeCard()
                if (!lockScreenShown && CallWindow.enabled(this@IncomingCallOverlayService)) {
                    lockScreenShown = true
                    runCatching { startActivity(AttaPush.intentFor(this@IncomingCallOverlayService, invitation, AttaPush.ACTION_VIEW)
                        .setClass(this@IncomingCallOverlayService, IncomingCallLockActivity::class.java)) }
                }
            } else {
                lockScreenShown = false
                if (card == null) showOverlay(invitation)
            }
            handler.postDelayed(this, 500)
        }
    }
    private val expire = Runnable { call?.let { AttaPush.dismiss(this, it.callId) }; stopSelf() }
    companion object {
        private var current: IncomingCallOverlayService? = null
        fun dismiss(id: String) { current?.let { service -> service.handler.post {
            if (service.call?.callId == id) { service.removeCard(); service.stopForeground(STOP_FOREGROUND_REMOVE); service.stopSelf() }
        } } }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); current = this }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val invitation = intent?.let(PushCall::fromIntent)
        if (invitation == null || AttaPush.pendingIncoming(this)?.callId != invitation.callId || !AttaPush.canNotify(this)) {
            stopSelf(); return START_NOT_STICKY
        }
        if (AttaPush.appVisible && AttaPush.foregroundRingCallId == invitation.callId) { stopSelf(); return START_NOT_STICKY }
        if (call?.callId == invitation.callId) return START_NOT_STICKY
        removeCard(); call = invitation
        val code = invitation.callId.hashCode()
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val view = PendingIntent.getActivity(this, code, AttaPush.intentFor(this, invitation, AttaPush.ACTION_VIEW)
            .setClass(this, IncomingCallLockActivity::class.java), pendingFlags)
        val accept = PendingIntent.getActivity(this, code + 1, AttaPush.intentFor(this, invitation, AttaPush.ACTION_ACCEPT), pendingFlags)
        val reject = PendingIntent.getBroadcast(this, code + 2, AttaPush.intentFor(this, invitation, "reject")
            .setClass(this, AttaCallRejectReceiver::class.java).setAction(AttaPush.ACTION_REJECT), pendingFlags)
        val notice = incomingCallNotification(this, invitation, view, accept, reject, false).apply {
            this.flags = this.flags or Notification.FLAG_INSISTENT
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(8162, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(8162, notice)
        } catch (error: RuntimeException) {
            // Permission/system restrictions must leave the normal call notification usable.
            AttaPush.postStandardIncoming(this, invitation)
            stopSelf(); return START_NOT_STICKY
        }
        handler.removeCallbacks(refresh); handler.post(refresh)
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, remainingIncomingCallMillis(invitation.createdAt).coerceAtLeast(1L))
        return START_NOT_STICKY
    }
    private fun showOverlay(invitation: PushCall) {
        if (CallWindow.enabled(this)) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val candidate = callWindowCard(this, invitation,
                { runCatching { startActivity(AttaPush.intentFor(this, invitation, AttaPush.ACTION_ACCEPT)) } }, { sendBroadcast(AttaPush.intentFor(this, invitation, "reject")
                    .setClass(this, AttaCallRejectReceiver::class.java).setAction(AttaPush.ACTION_REJECT)) })
            val width = (resources.displayMetrics.widthPixels - 32 * resources.displayMetrics.density).toInt()
                .coerceAtMost((420 * resources.displayMetrics.density).toInt())
            val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (12 * resources.displayMetrics.density).toInt() }
            runCatching { wm.addView(candidate, params); card = candidate }
        }
    }
    private fun removeCard() { card?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(it) } }; card = null }
    override fun onTimeout(startId: Int) { call?.let { AttaPush.dismiss(this, it.callId) }; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); removeCard(); if (current === this) current = null; super.onDestroy() }
}

/** Public lock-screen card; answering still enters the authenticated app's normal call flow. */
class IncomingCallLockActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var call: PushCall? = null
    private val check = object : Runnable {
        override fun run() {
            if (AttaPush.pendingIncoming(this@IncomingCallLockActivity)?.callId != call?.callId ||
                !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) finish()
            else handler.postDelayed(this, 300)
        }
    }
    override fun onCreate(state: Bundle?) { super.onCreate(state); show(intent) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); show(intent) }
    private fun show(intent: Intent) {
        val invitation = PushCall.fromIntent(intent)
        if (invitation == null || AttaPush.pendingIncoming(this)?.callId != invitation.callId) { finish(); return }
        call = invitation
        if (!(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) {
            startActivity(AttaPush.intentFor(this, invitation, AttaPush.ACTION_VIEW)); finish(); return
        }
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.setLayout(-1, -2); window.setGravity(Gravity.TOP)
        val frame = FrameLayout(this).apply { val gap = (16 * resources.displayMetrics.density).toInt(); setPadding(gap, gap, gap, gap) }
        frame.addView(callWindowCard(this, invitation, {
            startActivity(AttaPush.intentFor(this, invitation, AttaPush.ACTION_ACCEPT)); finish()
        }, {
            sendBroadcast(AttaPush.intentFor(this, invitation, "reject").setClass(this, AttaCallRejectReceiver::class.java).setAction(AttaPush.ACTION_REJECT))
            finish()
        }))
        setContentView(frame); handler.removeCallbacks(check); handler.post(check)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
