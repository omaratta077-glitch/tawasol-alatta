package com.alatta.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import org.json.JSONObject

// Android vectors are produced directly from cloudflare-site/public/icons.js.
// This file does NOT use substitute text glyphs or platform-dependent emoji.
internal val PremiumGold = Color(0xFFE6B94E)
internal val PremiumGoldLight = Color(0xFFF6D776)
internal val PremiumBackground = Color(0xFF040506)
internal val PremiumPanel = Color(0xFF090D0F)
internal val PremiumBorder = Color(0xFF6B5429)
internal val PremiumMuted = Color(0xFFB6BBC0)

@Composable
internal fun SiteIcon(name: String, modifier: Modifier = Modifier, tint: Color = PremiumGold, size: Dp = 24.dp) {
    val resId = when (name) {
        "home" -> R.drawable.atta_ic_home
        "search" -> R.drawable.atta_ic_search
        "bell" -> R.drawable.atta_ic_bell
        "message-circle" -> R.drawable.atta_ic_message_circle
        "mail" -> R.drawable.atta_ic_mail
        "user" -> R.drawable.atta_ic_user
        "user-plus" -> R.drawable.atta_ic_user_plus
        "users" -> R.drawable.atta_ic_users
        "bookmark" -> R.drawable.atta_ic_bookmark
        "ellipsis" -> R.drawable.atta_ic_ellipsis
        "plus" -> R.drawable.atta_ic_plus
        "image" -> R.drawable.atta_ic_image
        "video" -> R.drawable.atta_ic_video
        "chart" -> R.drawable.atta_ic_chart
        "smile" -> R.drawable.atta_ic_smile
        "send" -> R.drawable.atta_ic_send
        "chevron-left" -> R.drawable.atta_ic_chevron_left
        "chevron-down" -> R.drawable.atta_ic_chevron_down
        "phone" -> R.drawable.atta_ic_phone
        "phone-off" -> R.drawable.atta_ic_phone_off
        "paperclip" -> R.drawable.atta_ic_paperclip
        "mic" -> R.drawable.atta_ic_mic
        "mic-off" -> R.drawable.atta_ic_mic_off
        "camera" -> R.drawable.atta_ic_camera
        "camera-off" -> R.drawable.atta_ic_camera_off
        "monitor" -> R.drawable.atta_ic_monitor
        "volume" -> R.drawable.atta_ic_volume
        "heart" -> R.drawable.atta_ic_heart
        "repeat" -> R.drawable.atta_ic_repeat
        "share" -> R.drawable.atta_ic_share
        "globe" -> R.drawable.atta_ic_globe
        "flame" -> R.drawable.atta_ic_flame
        "upload" -> R.drawable.atta_ic_upload
        "x" -> R.drawable.atta_ic_x
        "square" -> R.drawable.atta_ic_square
        "log-out" -> R.drawable.atta_ic_log_out
        "youtube" -> R.drawable.atta_ic_youtube
        "play" -> R.drawable.atta_ic_play
        "arrow-left" -> R.drawable.atta_ic_arrow_left
        "check" -> R.drawable.atta_ic_check
        else -> R.drawable.atta_ic_ellipsis
    }
    Icon(painter = painterResource(resId), contentDescription = name, tint = tint, modifier = modifier.size(size))
}

@Composable
internal fun PremiumHeader(
    current: String,
    unreadNotifications: Int = 0,
    onNavigate: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(62.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF0B0F11), PremiumBackground)))
            .border(BorderStroke(0.5.dp, PremiumBorder.copy(alpha = .75f)))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // RTL: first child is positioned on the right. Brand icon is only
        // slightly bigger than the notification icons, as requested.
        Row(
            Modifier.width(96.dp).clickable { onNavigate("الرئيسية") },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Image(painterResource(R.drawable.logo_premium), "شعار العطا", Modifier.size(34.dp).clip(CircleShape))
            Text("AL-ATTA", color = PremiumGoldLight, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, maxLines = 1)
        }
        // Two icons on the right of YouTube, two on the left; the YouTube icon
        // remains in the TOP bar and is never substituted for Home at bottom.
        PremiumTopItem("search", "استكشاف", current, onNavigate)
        // YouTube remains in the header but without a large surrounding square.
        // Friends only appears in the bottom navigation.
        Box(Modifier.size(35.dp).clickable { onNavigate("يوتيوب") }, contentAlignment = Alignment.Center) {
            SiteIcon("youtube", tint = if (current == "يوتيوب") PremiumGoldLight else PremiumGold, size = 25.dp)
        }
        PremiumTopItem("message-circle", "الرسائل", current, onNavigate)
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            PremiumTopItem("bell", "الإشعارات", current, onNavigate)
            if (unreadNotifications > 0) {
                // Tiny unread counter anchored to the LOWER edge of the bell.
                Box(Modifier.align(Alignment.BottomStart).offset(x = (-1).dp, y = 2.dp)
                    .sizeIn(minWidth = 15.dp, minHeight = 15.dp)
                    .background(PremiumGold, CircleShape)
                    .padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
                    Text(unreadNotifications.coerceAtMost(99).toString(),
                        fontSize = 9.sp, lineHeight = 10.sp, color = PremiumBackground, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PremiumTopItem(name: String, destination: String, current: String, go: (String) -> Unit) {
    val glow = rememberInfiniteTransition(label = "top-icon-glow")
    val intensity by glow.animateFloat(initialValue = .08f, targetValue = .24f,
        animationSpec = infiniteRepeatable(tween(1300), repeatMode = RepeatMode.Reverse), label = "gold-glow")
    Box(Modifier.size(38.dp)
        .background(if (current == destination) PremiumGold.copy(alpha = intensity) else Color.Transparent,
            RoundedCornerShape(10.dp))
        .clickable { go(destination) }, contentAlignment = Alignment.Center) {
        SiteIcon(name, tint = if (current == destination) PremiumGoldLight else PremiumGold, size = 23.dp)
    }
}

@Composable
internal fun PremiumSearchBar(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 7.dp)
            .height(40.dp).border(.8.dp, PremiumBorder.copy(alpha = .8f), RoundedCornerShape(22.dp))
            .background(PremiumPanel, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SiteIcon("search", size = 20.dp, tint = PremiumGold)
        Text("ابحث عن الأشخاص أو المنشورات...", color = PremiumMuted, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PremiumBottomNav(current: String, go: (String) -> Unit) {
    // This bar is inside safeDrawingPadding() in the parent, ABOVE Android's
    // system back/home controls, not overlapping them.
    val destinations = listOf(
        Triple("الرئيسية", "home", "الرئيسية"),
        Triple("استكشاف", "search", "استكشف"),
        Triple("الأصدقاء", "users", "الأصدقاء"),
        Triple("الرسائل", "mail", "الرسائل"),
        Triple("الملف", "user", "الملف الشخصي")
    )
    Row(
        Modifier.fillMaxWidth().height(71.dp)
            .background(PremiumBackground)
            .border(0.8.dp, PremiumBorder, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .padding(horizontal = 3.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        destinations.forEach { (name, icon, label) ->
            val selected = current == name
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (selected) PremiumGold.copy(alpha = .14f) else Color.Transparent, RoundedCornerShape(13.dp))
                    .clickable { go(name) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                SiteIcon(icon, tint = if (selected) PremiumGoldLight else PremiumMuted, size = 23.dp)
                Spacer(Modifier.height(4.dp))
                Text(label, color = if (selected) PremiumGoldLight else PremiumMuted,
                    fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selected) Box(Modifier.padding(top = 3.dp).width(20.dp).height(2.dp)
                    .background(PremiumGold, CircleShape))
            }
        }
    }
}

@Composable
internal fun PremiumOutlineAction(icon: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.height(37.dp)
            .border(.8.dp, PremiumBorder.copy(alpha = .85f), RoundedCornerShape(9.dp))
            .background(PremiumPanel, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        SiteIcon(icon, size = 18.dp)
        Text(text, fontSize = 11.sp, color = PremiumGoldLight, maxLines = 1)
    }
}

@Composable
internal fun PremiumPostAction(icon: String, label: String, color: Color = PremiumGoldLight, onClick: () -> Unit) {
    Row(
        Modifier.height(42.dp).clickable(onClick = onClick).padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SiteIcon(icon, size = 21.dp, tint = color)
        if (label.isNotEmpty()) Text(label, fontSize = 11.sp, maxLines = 1, color = color)
    }
}

@Composable
internal fun StoryRing(label: String, url: String, isAdd: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(75.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(62.dp)
            .border(1.7.dp, PremiumGoldLight, CircleShape)
            .padding(3.dp)
            .clip(CircleShape)
            .background(Color(0xFF191307)), contentAlignment = Alignment.Center) {
            if (isAdd) SiteIcon("plus", size = 28.dp)
            else if (url.endsWith(".mp4", ignoreCase = true)) SiteIcon("video", size = 26.dp)
            else if (url.isNotBlank()) AsyncImage(url, label, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
            else Image(painterResource(R.drawable.logo_premium), label, Modifier.fillMaxSize().clip(CircleShape))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
