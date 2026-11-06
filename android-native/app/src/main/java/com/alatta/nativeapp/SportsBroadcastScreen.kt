package com.alatta.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** AL-ATTA displays the selected live stream inline; AL-ATTA TV owns the channel list. */
@Composable
internal fun SportsBroadcastScreen(
    session: NativePlaybackSession?,
    modifier: Modifier = Modifier,
    loading: Boolean,
    error: String,
    isAdmin: Boolean,
    broadcastExpanded: Boolean,
    onBroadcast: () -> Unit,
    onManage: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenTv: () -> Unit = {},
    onLegacyPlayback: (() -> Unit)? = null,
    watchRoomExpanded: Boolean = false,
    onWatchRoom: () -> Unit = {},
    watchRoomContent: @Composable () -> Unit = {},
    extraContent: @Composable () -> Unit = {}
) {
    Column(
        modifier.fillMaxSize().background(PremiumBackground).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("البث المباشر", color = PremiumGoldLight, fontSize = 20.sp,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onReload, enabled = !loading) {
                Text("تحديث", color = PremiumGoldLight)
            }
            if (isAdmin) TextButton(onClick = onManage) {
                Text("إدارة", color = PremiumGoldLight)
            }
        }
        Surface(color = Color.Black, modifier = Modifier.fillMaxWidth()) {
            if (session == null) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center) {
                    Text("صفحة الرياضة مستقلة. افتح تبويب العطا TV للقنوات والمشغّل.",
                        color = PremiumGoldLight, fontSize = 14.sp)
                }
            } else {
                Column {
                    Text("لا يوجد مشغّل ExoPlayer2 في صفحة البث. اختيار القناة من AL-ATTA TV وتشغيلها بالمحرّك الذهبي داخل Media.", color = PremiumGoldLight, modifier = Modifier.padding(12.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(session.channel.name, color = PremiumGoldLight,
                            modifier = Modifier.weight(1f), fontSize = 14.sp)
                        TextButton(onClick = onStop) { Text("إيقاف", color = PremiumGoldLight) }
                    }
                    if (session.terminal) {
                        Text(session.lastError ?: "تعذر تشغيل البث.", color = Color(0xFFFFB4A9),
                            modifier = Modifier.padding(horizontal = 10.dp))
                        TextButton(onClick = onRetry) { Text("إعادة المحاولة", color = PremiumGoldLight) }
                    }
                }
            }
        }
        Button(onClick = { onStop(); onOpenTv() }, modifier = Modifier.fillMaxWidth()) {
            Text("فتح تبويب العطا TV")
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PremiumGold)
        if (error.isNotBlank()) {
            Text(error, color = Color(0xFFFFB4A9), fontSize = 13.sp)
            if (session == null) OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("إعادة محاولة تشغيل القناة", color = PremiumGoldLight)
            }
        }
        if (session == null && onLegacyPlayback != null) {
            OutlinedButton(onClick = onLegacyPlayback, modifier = Modifier.fillMaxWidth()) {
                Text("تشغيل القناة بالمشغّل القديم", color = PremiumGoldLight)
            }
            Text("المشغّل القديم يفتح كشاشة مستقلة إذا كان مثبتًا؛ لا يتم عرضه داخل هذه الصفحة.",
                color = PremiumMuted, fontSize = 11.sp)
        }
        OutlinedButton(onClick = onBroadcast, modifier = Modifier.fillMaxWidth()) {
            Text(if (broadcastExpanded) "إخفاء أدوات بث الشاشة" else "إظهار أدوات بث الشاشة",
                color = PremiumGoldLight)
        }
        OutlinedButton(onClick = onWatchRoom, modifier = Modifier.fillMaxWidth()) {
            Text(if (watchRoomExpanded) "إخفاء شاهد مع أصحابك" else "شاهد مع أصحابك",
                color = PremiumGoldLight)
        }
        if (watchRoomExpanded) watchRoomContent()
        extraContent()
    }
}
