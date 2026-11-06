package com.alatta.nativeapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AL-ATTA Media receives a channel number from AL-ATTA TV.
 *
 * This Activity deliberately does not use ExoPlayer, request network streams,
 * or start any separately installed application. It launches only the original
 * Gold engine Activity when compiled and registered IN THIS Media APK.
 *
 * Until the Gold engine port exists, it shows a clear error instead of claiming
 * that a fallback player is the original engine.
 */
class GoldPlaybackReceiverActivity : Activity() {
    private lateinit var message: TextView
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.BLACK)
        }
        title = TextView(this).apply {
            text = "العطا Media"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFD678.toInt())
            gravity = Gravity.CENTER
        }
        message = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFF1DFBA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(28))
        }
        val close = Button(this).apply {
            text = "الرجوع إلى القنوات"
            setOnClickListener { finish() }
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(message, LinearLayout.LayoutParams(-1, -2))
        root.addView(close, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        openRequestedChannel(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedChannel(intent)
    }

    private fun openRequestedChannel(intent: Intent?) {
        if (intent?.action != ACTION) {
            message.text = "طلب تشغيل غير معروف."
            return
        }
        val number = intent.getStringExtra(EXTRA_SOURCE_NUMBER)
        val validNumber = number?.let(YtvPlayer::channelId)
        if (validNumber == null) {
            message.text = "رقم القناة غير صالح. ارجع إلى AL-ATTA TV واختر القناة من جديد."
            return
        }
        message.text = "جاري تسليم القناة إلى المحرّك الذهبي..."
        when (EmbeddedGoldEngine.launch(this, validNumber)) {
            EmbeddedGoldEngine.Result.STARTED -> finish()
            EmbeddedGoldEngine.Result.NOT_PACKAGED ->
                message.text = "محرّك النسخة الذهبية لم يُدمج داخل العطا Media بعد. لا يتم تشغيل ExoPlayer بديلًا عنه."
            EmbeddedGoldEngine.Result.NOT_ALLOWED ->
                message.text = "لم يسمح أندرويد بفتح شاشة المحرّك الذهبي داخل Media."
            EmbeddedGoldEngine.Result.FAILED ->
                message.text = "فشل تهيئة المحرّك الذهبي داخل Media. يلزم فحص موارده ومكتباته."
            EmbeddedGoldEngine.Result.INVALID_CHANNEL ->
                message.text = "رقم القناة غير صالح."
        }
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value + 0.5f).toInt()

    companion object {
        const val ACTION = "com.alatta.nativeapp.action.OPEN_GOLD_PLAYER"
        const val EXTRA_SOURCE_NUMBER = "com.alatta.nativeapp.extra.GOLD_SOURCE_NUMBER"
    }
}
