package com.alatta.nativeapp

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** لا تُفتح إلا لمن فعّل منبّه صلاة مجدولًا؛ ولا تتجاوز قفل شاشة الجهاز. */
class AdhanAlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        setContent {
            val prayer by AdhanPlaybackService.activePrayer.collectAsState()
            LaunchedEffect(prayer) { if (prayer !in alarmPrayerIds) finish() }
            val settings = PrayerStore.read(this)
            MaterialTheme(colorScheme = darkColorScheme(primary = PremiumGold, background = PremiumBackground)) {
                Column(Modifier.fillMaxSize().background(PremiumBackground).safeDrawingPadding().padding(28.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.logo_premium), "AL-ATTA MEDIA", Modifier.size(130.dp))
                    Spacer(Modifier.height(28.dp))
                    Text("حان وقت صلاة ${prayerNames[prayer].orEmpty()}", color = PremiumGoldLight,
                        fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(settings.place.name, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(36.dp))
                    Button(onClick = { stopService(Intent(this@AdhanAlarmActivity, AdhanPlaybackService::class.java)); finish() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), colors = ButtonDefaults.buttonColors(containerColor = PremiumGold, contentColor = Color.Black)) {
                        Text("إيقاف الأذان", fontSize = 19.sp)
                    }
                    TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("إخفاء الشاشة ومتابعة الأذان", color = PremiumGoldLight) }
                }
            }
        }
    }
}
