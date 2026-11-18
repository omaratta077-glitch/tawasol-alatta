package com.alatta.nativeapp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Single-package Gold-first entry point. No ExoPlayer, URLs or stream proxies. */
class GoldFirstHubActivity : Activity() {
    private val gold = 0xFFFFD06A.toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(34), dp(22), dp(22))
            setBackgroundColor(0xFF090909.toInt())
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(TextView(this).apply {
            text = "العطا Media"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(gold)
        }, LinearLayout.LayoutParams(-1, dp(76)))
        root.addView(TextView(this).apply {
            text = "مشغّل الذهبية الأصلي + أقسام العطا في تطبيق واحد"
            textSize = 14f
            setTextColor(0xFFE4D1A9.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(62)))
        root.addView(action("القنوات والبث المباشر") {
            open(GoldFirstTargets.goldPlayerIntent(packageName))
        })
        root.addView(action("المنشورات والمكالمات والقرآن وباقي أقسام العطا") {
            open(GoldFirstTargets.mediaIntent(packageName))
        })
        root.addView(TextView(this).apply {
            text = "نسخة اختبار خاصة: نجاح القائمة لا يثبت عمل كل خدمات Media."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFB8AEA0.toInt())
            setPadding(0, dp(28), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun action(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 17f
        isAllCaps = false
        setTextColor(Color.BLACK)
        background = GradientDrawable().apply {
            setColor(gold)
            cornerRadius = dp(16).toFloat()
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(17) }
    }
    private fun open(target: Intent) {
        try { startActivity(target) }
        catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "القسم لم يُسجل بعد في حزمة الاختبار", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "تعذر فتح القسم في نسخة الاختبار", Toast.LENGTH_LONG).show()
        }
    }
    private fun dp(x: Int): Int = (x * resources.displayMetrics.density + .5f).toInt()
}

internal object GoldFirstTargets {
    // Respect original Gold launcher initialization: never bypass SplashActivity.
    const val GOLD_ACTIVITY = "dev.google.ytvplayer.ui.splash.SplashActivity"
    const val MEDIA_ACTIVITY = "com.alatta.nativeapp.MainActivity"
    fun goldPlayerIntent(hostPackage: String) = Intent(Intent.ACTION_MAIN)
        .setComponent(ComponentName(hostPackage, GOLD_ACTIVITY))
    fun mediaIntent(hostPackage: String) = Intent(Intent.ACTION_MAIN)
        .setComponent(ComponentName(hostPackage, MEDIA_ACTIVITY))
}
