from pathlib import Path
root=Path('/mnt/data/alatta_v117_work')
src=root/'android-native/app/src/main/java/com/alatta/nativeapp'
chrome=src/'PremiumChrome.kt'
s=chrome.read_text()
s=s.replace('import androidx.compose.foundation.clickable\n','import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.combinedClickable\nimport androidx.compose.foundation.ExperimentalFoundationApi\n')
s=s.replace('import androidx.compose.material3.Icon\n','import androidx.compose.material3.Icon\nimport androidx.compose.material3.DropdownMenu\n')
s=s.replace('import androidx.compose.runtime.Composable\n','import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.remember\nimport androidx.compose.runtime.setValue\n')
s=s.replace('        "ellipsis" -> R.drawable.atta_ic_ellipsis\n','        "ellipsis" -> R.drawable.atta_ic_ellipsis\n        "menu" -> R.drawable.atta_ic_menu\n')
old='''    val destinations = listOf(
        Triple("الرئيسية", "home", "الرئيسية"),
        Triple("استكشاف", "search", "استكشف"),
        Triple("الأصدقاء", "users", "الأصدقاء"),
        Triple("الرسائل", "mail", "الرسائل"),
        Triple("الملف", "user", "الملف الشخصي")
    )'''
new='''    // RTL order: Home | Profile | Messages (center) | Friends | Menu.
    // Search is already in the header; do not duplicate it at the bottom.
    val destinations = listOf(
        Triple("الرئيسية", "home", "الرئيسية"),
        Triple("الملف", "user", "ملفي"),
        Triple("الرسائل", "mail", "الرسائل"),
        Triple("الأصدقاء", "users", "الأصدقاء"),
        Triple("القائمة", "menu", "القائمة")
    )'''
assert old in s
s=s.replace(old,new)
pos=s.index('\n@Composable\ninternal fun StoryRing')
new_reaction='''

// Tap to toggle the normal like (highlighted gold). Long-press to reveal
// advanced reactions. No reaction row is rendered below every post.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PremiumReactionAction(
    count: Int,
    liked: Boolean,
    onLike: () -> Unit,
    onReact: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(44.dp)
                .combinedClickable(
                    onClick = onLike,
                    onLongClick = { expanded = true },
                    onClickLabel = if (liked) "إلغاء الإعجاب" else "إعجاب",
                    onLongClickLabel = "عرض التفاعلات"
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SiteIcon("heart", size = 23.dp, tint = if (liked) PremiumGoldLight else PremiumMuted)
            Text(count.coerceAtLeast(0).toString(), color = if (liked) PremiumGoldLight else PremiumMuted, fontSize = 12.sp)
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
'''
s=s[:pos]+new_reaction+s[pos:]
chrome.write_text(s)

main=src/'MainActivity.kt';t=main.read_text()
t=t.replace('    var confirmExit by remember { mutableStateOf(false) }','    var confirmExit by remember { mutableStateOf(false) }\n    var confirmLogout by remember { mutableStateOf(false) }')
anchor='''    if (changePasswordDialog) AlertDialog('''
assert anchor in t
logout='''    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        containerColor = Panel,
        titleContentColor = GoldLight,
        textContentColor = White,
        title = { Text("تسجيل الخروج") },
        text = { Text("هل تريد تسجيل الخروج من حسابك على هذا الجهاز؟") },
        confirmButton = {
            TextButton(onClick = {
                confirmLogout = false
                activeCall = null; incomingCall = null
                chatWith = ""; messages = emptyList(); inboxPreviews = emptyList()
                posts = emptyList(); notifications = emptyList(); friends = emptyList()
                profile = JSONObject(); displayName = ""; myUsername = ""; myAvatar = ""
                visited.clear(); tab = "الرئيسية"
                token = ""
                prefs.edit().clear().apply()
            }) { Text("تسجيل الخروج", color = GoldLight) }
        },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("إلغاء", color = White) } }
    )
'''
t=t.replace(anchor,logout+anchor)
old='''                            TextButton(onClick = { refresh() }) { Text("تحديث", fontSize = 12.sp) }
                            TextButton(onClick = { token = ""; posts = emptyList(); prefs.edit().clear().apply() }) { Text("خروج", fontSize = 12.sp) }'''
new='''                            if (tab != "القائمة") TextButton(onClick = { refresh() }) { Text("تحديث", fontSize = 12.sp) }'''
assert old in t
t=t.replace(old,new)
old='''                                var comment by remember(postId) { mutableStateOf("") }
                                var showComments by remember(postId) { mutableStateOf(false) }'''
new='''                                var comment by remember(postId) { mutableStateOf("") }
                                var showComments by remember(postId) { mutableStateOf(false) }
                                val remoteLiked = p.optBoolean("likedByMe")
                                var likedNow by remember(postId, remoteLiked) { mutableStateOf(remoteLiked) }
                                val visibleLikeCount = (p.optInt("likeCount") +
                                    (if (likedNow) 1 else 0) - (if (remoteLiked) 1 else 0)).coerceAtLeast(0)'''
assert old in t
t=t.replace(old,new)
old='''                                            PremiumPostAction("heart", p.optInt("likeCount").toString(),
                                                color = if (p.optBoolean("likedByMe")) GoldLight else Muted) {
                                                task { post("/api/post-like", JSONObject().put("postId", postId)); load(tab) }
                                            }'''
new='''                                            PremiumReactionAction(
                                                count = visibleLikeCount,
                                                liked = likedNow,
                                                onLike = {
                                                    likedNow = !likedNow // Gold feedback without waiting for the network.
                                                    task {
                                                        try {
                                                            post("/api/post-like", JSONObject().put("postId", postId))
                                                            load(tab)
                                                        } catch (e: Exception) {
                                                            likedNow = !likedNow // Revert if the server rejects the request.
                                                            throw e
                                                        }
                                                    }
                                                },
                                                onReact = { reaction ->
                                                    task {
                                                        post("/api/post-react", JSONObject()
                                                            .put("postId", postId).put("reaction", reaction))
                                                        load(tab)
                                                    }
                                                }
                                            )'''
assert old in t
t=t.replace(old,new)
old='''                                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                                        listOf("love" to "❤️", "haha" to "😂", "wow" to "😮", "sad" to "😢", "angry" to "😡").forEach { (reaction, emoji) ->
                                            TextButton(onClick = { task { post("/api/post-react", JSONObject().put("postId", postId).put("reaction", reaction)); load(tab) } }) { Text(emoji) }
                                        }
                                    }
'''
assert old in t
t=t.replace(old,'')
anchor='''                        "الأصدقاء" -> LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {'''
assert anchor in t
menu='''                        "القائمة" -> LazyColumn(
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                CardBox {
                                    SectionTitle("القائمة")
                                    Text("تواصل العطا", color = Muted, fontSize = 12.sp)
                                }
                            }
                            item {
                                CardBox {
                                    PremiumMenuEntry("user", "الملف الشخصي") { navigate("الملف") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("bookmark", "المنشورات المحفوظة") { navigate("المحفوظات") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("users", "المجموعات") { navigate("المجموعات") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("bell", "الإشعارات") { navigate("الإشعارات") }
                                    HorizontalDivider(color = Border.copy(alpha = .5f))
                                    PremiumMenuEntry("log-out", "تغيير كلمة السر") { changePasswordDialog = true }
                                }
                            }
                            item {
                                CardBox {
                                    PremiumMenuEntry("log-out", "تسجيل الخروج", danger = true) {
                                        confirmLogout = true
                                    }
                                }
                            }
                        }
'''
t=t.replace(anchor,menu+anchor)
main.write_text(t)

# Menu style implementation directly in PremiumChrome where palette/colors available.
s=chrome.read_text()
pos=s.index('\n@Composable\ninternal fun StoryRing')
menu_component='''

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
'''
s=s[:pos]+menu_component+s[pos:]
chrome.write_text(s)

# Exact 3 horizontal lines rather than 3 dots for user's "التلات شروط".
drawable=root/'android-native/app/src/main/res/drawable/atta_ic_menu.xml'
drawable.write_text('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M4,6 L20,6 M4,12 L20,12 M4,18 L20,18"
        android:fillColor="@android:color/transparent"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="2.2"
        android:strokeLineCap="round"/>
</vector>
''')

gradle=root/'android-native/app/build.gradle.kts'
gradle_s=gradle.read_text(); assert 'versionCode = 116' in gradle_s
gradle.write_text(gradle_s.replace('versionCode = 116','versionCode = 117').replace('versionName = "1.0.16"','versionName = "1.0.17"'))
print('Patched: nav, reactions, menu, logout, version.')
