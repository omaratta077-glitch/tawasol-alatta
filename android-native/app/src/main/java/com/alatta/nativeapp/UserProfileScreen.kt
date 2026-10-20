package com.alatta.nativeapp

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.json.JSONObject

private val profileGold = Color(0xFFE6B94E)
private val profileBackground = Color(0xFF050709)
private val profilePanel = Color(0xFF101519)
private val profileMuted = Color(0xFFB6BBC0)

@Composable
internal fun PublicUserProfile(
    username: String,
    profile: JSONObject?,
    recentPosts: List<JSONObject>,
    friendUsers: List<JSONObject>,
    onOpenFriend: (String) -> Unit,
    isLoading: Boolean,
    onBack: () -> Unit,
    onMessage: () -> Unit,
    onFriendAction: (String) -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    val user = profile?.optJSONObject("user") ?: JSONObject().put("username", username)
    val fullName = user.optString("fullName").ifBlank { user.optString("displayName").ifBlank { "مستخدم" } }
    val avatar = media(user.optString("avatar"))
    val cover = media(user.optString("cover"))
    var filter by remember(username) { mutableStateOf("المنشورات") }
    val posts = remember(recentPosts, filter) {
        recentPosts.filter { p ->
            val isVideo = p.optString("video").isNotBlank()
            val isImage = p.optString("image").isNotBlank()
            filter == "المنشورات" || (filter == "الصور" && isImage) || (filter == "الفيديوهات" && isVideo)
        }
    }
    var playing by remember(username) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(profileBackground)) {
        Row(Modifier.fillMaxWidth().height(55.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                SiteIcon("chevron-left", size = 26.dp, tint = profileGold)
            }
            Text("الملف الشخصي", Modifier.weight(1f), color = Color.White, fontSize = 19.sp,
                fontWeight = FontWeight.Bold)
        }
        if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = profileGold)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Box(Modifier.fillMaxWidth().height(164.dp).background(profilePanel)) {
                    if (cover.isNotBlank()) ExpandableProfileMedia(cover, "صورة الغلاف", Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.offset(y = (-44).dp).size(96.dp).border(3.dp, profileGold, CircleShape)
                        .padding(3.dp).clip(CircleShape).background(Color(0xFF41311A)),
                        contentAlignment = Alignment.Center) {
                        if (avatar.isNotBlank()) ExpandableProfileMedia(avatar, fullName, Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop)
                        else Text(fullName.take(1), fontSize = 36.sp, color = profileGold)
                    }
                    Text(fullName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.offset(y = (-37).dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    val activityLabel = if (user.optBoolean("isOnline", false)) "نشط الآن" else {
                        val last = user.optLong("lastSeen", 0L)
                        if (last <= 0L) "غير متصل" else {
                            val minutes = ((System.currentTimeMillis() - last).coerceAtLeast(0L) / 60000L)
                            when {
                                minutes < 1L -> "نشط منذ لحظات"
                                minutes < 60L -> "نشط منذ $minutes دقيقة"
                                minutes < 1440L -> "نشط منذ ${minutes / 60L} ساعة"
                                else -> "نشط منذ ${minutes / 1440L} يوم"
                            }
                        }
                    }
                    Text(activityLabel, color = profileGold, fontSize = 12.sp,
                        modifier = Modifier.offset(y = (-32).dp).padding(bottom = 3.dp))
                    if (user.optString("bio").isNotBlank()) Text(user.optString("bio"),
                        color = profileMuted, fontSize = 13.sp, modifier = Modifier.offset(y = (-25).dp))
                    val friendCount = profile?.optInt("friendCount", -1) ?: -1
                    if (friendCount >= 0) Text("$friendCount صديق", color = profileGold, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 9.dp))
                    val registeredAt = user.optLong("createdAt",0L)
                    if (registeredAt>0) Text("تاريخ إنشاء الحساب: " + java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date(registeredAt)),
                        color=profileMuted,fontSize=12.sp,modifier=Modifier.padding(bottom=9.dp))
                    val details = listOf(
                        "البلد" to user.optString("country"),
                        "المدينة" to user.optString("city"),
                        "التعليم" to user.optString("education"),
                        "العمل" to user.optString("work")
                    ).filter { it.second.isNotBlank() }
                    if (details.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 11.dp)
                            .background(profilePanel, RoundedCornerShape(13.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("معلومات عن المستخدم", color = profileGold,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            details.forEach { (label, value) ->
                                Text("$label: $value", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                    // Reflect the friend state returned by /api/profile-public.
                    // All actions are verified by the Cloudflare backend.
                    val friendState = profile?.optString("friendState").orEmpty()
                    val blocked = profile?.optBoolean("blockRestricted",false) == true
                    val requestsAllowed = user.optBoolean("allowFriendRequests",true)
                    if (!blocked) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        when (friendState) {
                            "friends" -> {
                                Text("✓ صديق", color = profileGold,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onFriendAction("remove") }) {
                                    Text("إلغاء الصداقة", color = profileMuted, fontSize = 12.sp)
                                }
                            }
                            "outgoing" -> {
                                Text("تم إرسال طلب الصداقة", color = profileGold,
                                    fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onFriendAction("cancel") }) {
                                    Text("إلغاء الطلب", color = profileMuted)
                                }
                            }
                            "incoming" -> {
                                Button(onClick = { onFriendAction("accept") },
                                    colors = ButtonDefaults.buttonColors(containerColor = profileGold,
                                        contentColor = Color.Black)) { Text("قبول الصداقة") }
                                TextButton(onClick = { onFriendAction("decline") }) {
                                    Text("رفض", color = profileMuted)
                                }
                            }
                            else -> if (requestsAllowed) {
                                Button(onClick = { onFriendAction("request") },
                                    colors = ButtonDefaults.buttonColors(containerColor = profileGold,
                                        contentColor = Color.Black)) {
                                    SiteIcon("user-plus", size = 18.dp, tint = Color.Black)
                                    Spacer(Modifier.width(5.dp))
                                    Text("إضافة صديق", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    if (!blocked && friendState == "friends") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = onMessage, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = profileGold,
                                contentColor = Color.Black)) {
                            Text("محادثة", fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = onVoiceCall, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 8.dp)) {
                            SiteIcon("phone", size = 20.dp, tint = profileGold)
                            Spacer(Modifier.width(3.dp))
                            Text("صوتي", color = profileGold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = onVideoCall, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 8.dp)) {
                            SiteIcon("video", size = 20.dp, tint = profileGold)
                            Spacer(Modifier.width(3.dp))
                            Text("مرئي", color = profileGold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("المنشورات", "الصور", "الفيديوهات").forEach { option ->
                        Box(Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (filter == option) profileGold else profilePanel)
                            .clickable { filter = option }.padding(horizontal = 13.dp, vertical = 9.dp)) {
                            Text(option, fontSize = 12.sp,
                                color = if (filter == option) Color.Black else profileMuted)
                        }
                    }
                }
                if (friendUsers.isNotEmpty()) {
                    Text("أصدقاء المستخدم", color = profileGold, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 17.dp, vertical = 6.dp))
                    friendUsers.take(30).forEach { friend ->
                        val friendName = friend.optString("username")
                        Text(friend.optString("fullName").ifBlank { friendName },
                            color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().clickable { onOpenFriend(friendName) }
                                .padding(horizontal = 22.dp, vertical = 6.dp))
                    }
                }
                Text("أحدث المنشورات العامة", color = profileMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 17.dp, vertical = 4.dp))
            }
            if (posts.isEmpty()) item {
                Text(if (isLoading) "جاري تحميل الصفحة..." else "لا توجد منشورات ظاهرة في هذا القسم",
                    color = profileMuted, modifier = Modifier.fillMaxWidth().padding(25.dp))
            }
            items(posts, key = { it.optString("id").ifBlank { it.hashCode().toString() } }) { post ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(15.dp)).background(profilePanel).padding(13.dp)) {
                    if (post.optString("text").isNotBlank()) Text(post.optString("text"), color = Color.White,
                        fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
                    MushafPostActions(post.optString("text"))
                    val photo = media(post.optString("image"))
                    val video = media(post.optString("video"))
                    if (photo.isNotBlank()) ExpandableProfileMedia(photo, "صورة المنشور", Modifier.fillMaxWidth()
                        .aspectRatio(1.15f).clip(RoundedCornerShape(11.dp)), contentScale = ContentScale.Crop)
                    if (video.isNotBlank()) {
                        if (playing == post.optString("id")) {
                            NativeVideo(video)
                            TextButton(onClick = { playing = "" }) { Text("إيقاف الفيديو", color = profileGold) }
                        } else TextButton(onClick = { playing = post.optString("id") }) {
                            SiteIcon("play", size = 22.dp, tint = profileGold)
                            Text(" تشغيل الفيديو", color = profileGold)
                        }
                    }
                }
            }
        }
    }
}
