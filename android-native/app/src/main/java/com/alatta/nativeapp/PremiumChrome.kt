package com.alatta.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

// تُنتج متجهات Android مباشرةً من cloudflare-site/public/icons.js.
// لا يستخدم هذا الملف رموزًا نصية بديلة أو إيموجي يختلف حسب المنصة.
internal val PremiumGold = Color(0xFFE6B94E)
internal val PremiumGoldLight = Color(0xFFF6D776)
internal val PremiumBackground = Color(0xFF040506)
internal val PremiumPanel = Color(0xFF090D0F)
internal val PremiumBorder = Color(0xFF6B5429)
internal val PremiumMuted = Color(0xFFB6BBC0)

@Composable
internal fun SiteIcon(name: String, modifier: Modifier = Modifier, tint: Color = PremiumGold, size: Dp = 24.dp) {
    val resId = when (name) {
        "book-open" -> R.drawable.atta_ic_book_open
        "book-text" -> R.drawable.atta_ic_book_text
        "hand-heart" -> R.drawable.atta_ic_hand_heart
        "trophy" -> R.drawable.atta_ic_trophy
        "play-circle" -> R.drawable.atta_ic_play_circle
        "user-round" -> R.drawable.atta_ic_user_round
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
        "menu" -> R.drawable.atta_ic_menu
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
        "heart-filled" -> R.drawable.atta_ic_heart_filled
        "repeat" -> R.drawable.atta_ic_repeat
        "share" -> R.drawable.atta_ic_share
        "globe" -> R.drawable.atta_ic_globe
        "flame" -> R.drawable.atta_ic_flame
        "upload" -> R.drawable.atta_ic_upload
        "x" -> R.drawable.atta_ic_x
        "square" -> R.drawable.atta_ic_square
        "log-out" -> R.drawable.atta_ic_log_out
        "lock" -> R.drawable.atta_ic_lock
        "youtube" -> R.drawable.atta_ic_youtube
        "play" -> R.drawable.atta_ic_play
        "pause" -> R.drawable.atta_ic_pause
        "skip-back" -> R.drawable.atta_ic_skip_back
        "skip-forward" -> R.drawable.atta_ic_skip_forward
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
        // يكون العنصر الفرعي الأول على اليمين في RTL. وأيقونة العلامة
        // أكبر قليلًا فقط من أيقونات الإشعارات.
        Row(
            Modifier.widthIn(min = 111.dp).clickable { onNavigate("الرئيسية") },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Image(painterResource(R.drawable.logo_premium), "شعار العطا", Modifier.size(34.dp).clip(CircleShape).attaGoldShine())
            Text("AL-ATTA", color = PremiumGoldLight, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
        // اجمع أزرار التنقل الأربعة بإحكام على اليسار في RTL.
        // احتفظ بمساحة لمس 38dp؛ وقلل الفجوة بين الأزرار فقط.
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            PremiumTopItem("search", "استكشاف", current, onNavigate)
            PremiumTopItem("message-circle", "الرسائل", current, onNavigate)
            PremiumTopItem("users", "الأصدقاء", current, onNavigate)
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                PremiumTopItem("bell", "الإشعارات", current, onNavigate)
                if (unreadNotifications > 0) {
                    Box(Modifier.align(Alignment.BottomStart).offset(x = (-1).dp, y = 2.dp)
                        .sizeIn(minWidth = 15.dp, minHeight = 15.dp)
                        .background(PremiumGold, CircleShape)
                        .padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
                        Text(unreadNotifications.coerceAtMost(99).toString(),
                            fontSize = 9.sp, lineHeight = 10.sp, color = PremiumBackground,
                            fontWeight = FontWeight.Bold)
                    }
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
    // يقع هذا الشريط داخل safeDrawingPadding() في الحاوية الأم، فوق
    // عناصر الرجوع والرئيسية في Android، لا متداخلًا معها.
    // شريط تنقل أفقي قابل للتمرير؛ يحل محل الشريط القديم الثابت ذي خمسة عناصر.
    // يستخدم أيقونات متجهية على نمط Lucide كما في النموذج التفاعلي المعتمد.
    val destinations = listOf(
        // يظهر العنصر الأول عند أقصى اليمين في صف التنقل العربي.
        // وتظل الشاشات الأخرى متاحة بالتمرير الأفقي، ولم تُحذف.
        Triple("الرئيسية", "home", "الرئيسية"),
        // Messenger remains accessible via PremiumHeader on the home screen.
        Triple("ريلز", "video", "ريلز"),
        Triple("الملف", "user-round", "الملف"),
        Triple("القرآن", "book-open", "القرآن"),
        Triple("الأحاديث", "book-text", "الأحاديث"),
        Triple("الأذكار", "hand-heart", "الأذكار"),
        Triple("الرياضة", "trophy", "الرياضة"),
        Triple("يوتيوب", "play-circle", "يوتيوب"),
        Triple("القائمة", "ellipsis", "المزيد")
    )
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().height(74.dp)
            .background(PremiumBackground)
            .border(0.8.dp, PremiumBorder, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .horizontalScroll(scroll)
            .padding(horizontal = 5.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        destinations.forEach { (name, icon, label) ->
            val selected = current == name
            Column(
                Modifier.width(66.dp).fillMaxHeight()
                    .background(
                        if (selected) PremiumGold.copy(alpha = .20f)
                        else Color(0xFF171A1D),
                        RoundedCornerShape(13.dp)
                    )
                    .clip(RoundedCornerShape(13.dp))
                    .attaGoldShine(selected)
                    .clickable { go(name) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (name == "ريلز") {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                        .background(PremiumGold.copy(alpha = if (selected) .24f else .12f))
                        .border(1.dp, PremiumGold.copy(alpha = if (selected) .9f else .55f), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center) {
                        SiteIcon(icon, tint = PremiumGoldLight, size = 22.dp)
                    }
                } else SiteIcon(icon, tint = if (selected) PremiumGoldLight else PremiumMuted, size = 23.dp)
                Spacer(Modifier.height(if (name == "ريلز") 1.dp else 4.dp))
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


// اضغط لتبديل الإعجاب العادي المميز بالذهبي. واضغط مطولًا لإظهار
// التفاعلات المتقدمة. لا يُرسم صف التفاعلات أسفل كل منشور.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PremiumReactionAction(
    count: Int,
    liked: Boolean,
    selectedReaction: String = "",
    onLike: () -> Unit,
    onReact: (String) -> Unit,
    onShowPeople: (() -> Unit)? = null,
    vertical: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val reactionModifier = (if (vertical) Modifier.width(48.dp) else Modifier.height(44.dp))
                .combinedClickable(
                    onClick = onLike,
                    onLongClick = { expanded = true },
                    onClickLabel = if (liked) "إلغاء الإعجاب" else "إعجاب",
                    onLongClickLabel = "عرض التفاعلات"
                )
                .padding(horizontal = if (vertical) 0.dp else 8.dp)
        val reactionGlyph = when (selectedReaction) {
            "love" -> "❤️"
            "haha" -> "😂"
            "wow" -> "😮"
            "sad" -> "😢"
            "angry" -> "😡"
            else -> ""
        }
        @Composable fun ReactionSymbol() {
            if (reactionGlyph.isNotBlank()) {
                Text(reactionGlyph, fontSize = 21.sp)
            } else {
                SiteIcon(if (liked) "heart-filled" else "heart", size = 23.dp,
                    tint = if (liked) PremiumGoldLight else PremiumMuted)
            }
        }
        @Composable fun ReactionCount() {
            Text(count.coerceAtLeast(0).toString(),
                color = if (selectedReaction.isNotBlank() || liked) PremiumGoldLight else PremiumMuted,
                fontSize = 12.sp,
                modifier = Modifier.sizeIn(minWidth = 36.dp, minHeight = if (vertical) 24.dp else 44.dp)
                    .then(if (onShowPeople != null) Modifier.clickable(onClickLabel = "عرض أصحاب التفاعلات", onClick = onShowPeople) else Modifier)
                    .wrapContentHeight().padding(horizontal = 5.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (vertical) Column(reactionModifier
            .background(Color(0xAA080808), RoundedCornerShape(24.dp))
            .border(1.dp, PremiumGold, RoundedCornerShape(24.dp)).padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            ReactionSymbol(); ReactionCount()
        } else Row(reactionModifier, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ReactionSymbol(); ReactionCount()
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(PremiumPanel)
                .border(1.dp, PremiumBorder, RoundedCornerShape(16.dp))
        ) {
            Text("اختر تفاعلك", color = PremiumGoldLight, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
            Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("love" to "❤️", "haha" to "😂", "wow" to "😮", "sad" to "😢", "angry" to "😡")
                    .forEach { (reaction, emoji) ->
                        Text(emoji,
                            modifier = Modifier.clickable {
                                expanded = false
                                onReact(reaction)
                            }.padding(horizontal = 7.dp, vertical = 9.dp),
                            fontSize = 25.sp
                        )
                    }
            }
        }
    }
}

/** احجز مساحة أشرطة النظام التي قد لا يمررها الحوار ضمن هوامشه الخاصة. */
@Composable
internal fun attaCommentBottomClearance(): Dp {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val safeBottom = WindowInsets.safeDrawing.getBottom(density)
    var ownerContext = context
    while (ownerContext is android.content.ContextWrapper && ownerContext !is android.app.Activity) {
        ownerContext = ownerContext.baseContext
    }
    val activity = ownerContext as? android.app.Activity
    val physicalBottom = activity?.window?.decorView?.let {
        androidx.core.view.ViewCompat.getRootWindowInsets(it)
            ?.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom
    } ?: safeBottom
    val missing = if (WindowInsets.ime.getBottom(density) > 0) 0 else (physicalBottom - safeBottom).coerceAtLeast(0)
    return with(density) { missing.toDp() } + 20.dp
}


@Composable
internal fun PremiumMenuEntry(icon: String, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(55.dp).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SiteIcon(icon, size = 22.dp, tint = if (danger) Color(0xFFFF8989) else PremiumGoldLight)
        Text(label, color = if (danger) Color(0xFFFF8989) else Color.White,
            fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun StoryRing(label: String, url: String, isAdd: Boolean, diameter: Int = 62, onClick: () -> Unit) {
    Column(
        Modifier.width((diameter + 13).dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(diameter.dp)
            .border(1.7.dp, PremiumGoldLight, CircleShape)
            .padding(3.dp)
            .clip(CircleShape)
            .background(Color(0xFF191307)), contentAlignment = Alignment.Center) {
            if (isAdd) SiteIcon("plus", size = 28.dp)
            else if (url.endsWith(".mp4", ignoreCase = true)) SiteIcon("video", size = 26.dp)
            else if (url.isNotBlank()) ProfileMediaImage(url, label, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
            else Image(painterResource(R.drawable.logo_premium), label, Modifier.fillMaxSize().clip(CircleShape).attaGoldShine())
        }
        Spacer(Modifier.height(5.dp))
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
