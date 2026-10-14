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
    isLoading: Boolean,
    onBack: () -> Unit,
    onMessage: () -> Unit,
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
                    if (cover.isNotBlank()) ProfileMediaImage(cover, "صورة الغلاف", Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.offset(y = (-44).dp).size(96.dp).border(3.dp, profileGold, CircleShape)
                        .padding(3.dp).clip(CircleShape).background(Color(0xFF41311A)),
                        contentAlignment = Alignment.Center) {
                        if (avatar.isNotBlank()) ProfileMediaImage(avatar, fullName, Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop)
                        else Text(fullName.take(1), fontSize = 36.sp, color = profileGold)
                    }
                    Text(fullName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.offset(y = (-37).dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (user.optString("bio").isNotBlank()) Text(user.optString("bio"),
                        color = profileMuted, fontSize = 13.sp, modifier = Modifier.offset(y = (-25).dp))
                    val friendCount = profile?.optInt("friendCount", -1) ?: -1
                    if (friendCount >= 0) Text("$friendCount صديق", color = profileGold, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 9.dp))
                    // Compact, accessible profile actions: no Arabic words split over two lines.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onMessage,
                            modifier = Modifier.weight(1f).height(76.dp),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = profileGold, contentColor = Color.Black
                            )
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                SiteIcon("message-circle", size = 23.dp, tint = Color.Black)
                                Spacer(Modifier.height(5.dp))
                                Text("محادثة", color = Color.Black, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        OutlinedButton(
                            onClick = onVoiceCall,
                            modifier = Modifier.weight(1f).height(76.dp),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, profileGold.copy(alpha = 0.8f))
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                SiteIcon("phone", size = 23.dp, tint = profileGold)
                                Spacer(Modifier.height(5.dp))
                                Text("صوتي", color = profileGold, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        OutlinedButton(
                            onClick = onVideoCall,
                            modifier = Modifier.weight(1f).height(76.dp),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, profileGold.copy(alpha = 0.8f))
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                SiteIcon("video", size = 23.dp, tint = profileGold)
                                Spacer(Modifier.height(5.dp))
                                Text("مرئي", color = profileGold, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("المنشورات", "الصور", "الفيديوهات").forEach { option ->
                        Box(
                            modifier = Modifier.weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (filter == option) profileGold else profilePanel)
                                .clickable { filter = option },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                option, fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                color = if (filter == option) Color.Black else profileMuted
                            )
                        }
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
                    val photo = media(post.optString("image"))
                    val video = media(post.optString("video"))
                    if (photo.isNotBlank()) ProfileMediaImage(photo, "صورة المنشور", Modifier.fillMaxWidth()
                        .heightIn(min = 120.dp, max = 420.dp).clip(RoundedCornerShape(11.dp)), contentScale = ContentScale.Fit)
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
