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
                // The avatar overlaps the cover without Modifier.offset: offset left a
                // large invisible gap between the name and the friend count on phones.
                Box(Modifier.fillMaxWidth().height(211.dp).background(profileBackground)) {
                    Box(Modifier.fillMaxWidth().height(161.dp).background(profilePanel)) {
                        if (cover.isNotBlank()) ExpandableProfileMedia(
                            cover, "صورة الغلاف", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Box(Modifier.align(Alignment.BottomCenter).size(101.dp)
                        .border(3.dp, profileGold, CircleShape).padding(3.dp)
                        .clip(CircleShape).background(Color(0xFF41311A)),
                        contentAlignment = Alignment.Center) {
                        if (avatar.isNotBlank()) ExpandableProfileMedia(
                            avatar, fullName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(fullName.take(1), fontSize = 36.sp, color = profileGold)
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(7.dp))
                    Text(fullName, color = Color.White, fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    if (user.optString("bio").isNotBlank()) Text(
                        user.optString("bio"), color = profileMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp), maxLines = 4)
                    val friendCount = profile?.optInt("friendCount", -1) ?: -1
                    if (friendCount >= 0) Text("$friendCount صديق", color = profileGold, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 9.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(onClick = onMessage, modifier = Modifier.weight(1f).height(49.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = profileGold,
                                contentColor = Color.Black)) { Text("محادثة", maxLines = 1, fontSize = 13.sp) }
                        OutlinedButton(onClick = onVoiceCall, modifier = Modifier.weight(1f).height(49.dp),
                            contentPadding = PaddingValues(horizontal = 3.dp)) {
                            SiteIcon("phone", size = 16.dp, tint = profileGold)
                            Spacer(Modifier.width(3.dp))
                            Text("صوتي", maxLines = 1, softWrap = false, color = profileGold, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onVideoCall, modifier = Modifier.weight(1f).height(49.dp),
                            contentPadding = PaddingValues(horizontal = 3.dp)) {
                            SiteIcon("video", size = 16.dp, tint = profileGold)
                            Spacer(Modifier.width(3.dp))
                            Text("مرئي", maxLines = 1, softWrap = false, color = profileGold, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ProfileInfoSummary(user)
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
                    if (photo.isNotBlank()) ExpandableProfileMedia(photo, "صورة المنشور", Modifier.fillMaxWidth()
                        .aspectRatio(1.03f).clip(RoundedCornerShape(11.dp)), contentScale = ContentScale.Crop)
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


/** Only real fields returned by the API are shown; never fabricate profile data. */
@Composable
internal fun ProfileInfoSummary(user: JSONObject) {
    val details = listOf(
        Triple("البلد", user.optString("country"), "🌐"),
        Triple("المدينة", user.optString("city"), "⌖"),
        Triple("التعليم", user.optString("education"), "🎓"),
        Triple("العمل", user.optString("work"), "✧")
    ).filter { it.second.isNotBlank() && it.second != "null" }
    if (details.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)
        .border(1.dp, profileGold.copy(alpha = .36f), RoundedCornerShape(14.dp))
        .background(profilePanel, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("معلومات المستخدم", color = profileGold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        details.forEach { (label, value, glyph) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(glyph, fontSize = 17.sp, color = profileGold)
                Column(Modifier.weight(1f)) {
                    Text(label, color = profileGold, fontSize = 11.sp)
                    Text(value, color = Color.White, fontSize = 13.sp, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
