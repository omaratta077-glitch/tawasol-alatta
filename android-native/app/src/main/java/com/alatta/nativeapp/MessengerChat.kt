package com.alatta.nativeapp

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Messenger-inspired interface with AL-ATTA's own black / gold appearance.
// Existing Cloudflare conversations remain the source of truth: no mock data.
internal data class ChatPreview(val user: JSONObject, val lastMessage: JSONObject?) {
    val username: String get() = user.optString("username")
    val timestamp: Long get() = lastMessage?.optLong("ts", 0L) ?: 0L
}

private val ChatBackground = Color(0xFF050709)
private val ChatPanel = Color(0xFF11161A)
private val ChatDivider = Color(0xFF252A2C)
private val ChatText = Color(0xFFF8F8F8)
private val ChatSecondary = Color(0xFFAEB6BC)
private val ChatGold = Color(0xFFE6B94E)
private val ChatGoldBright = Color(0xFFF6D776)

private fun contactName(person: JSONObject): String {
    val candidate = listOf("fullName", "displayName", "name", "title")
        .map { person.optString(it).trim() }
        .firstOrNull { it.isNotBlank() && it != "null" && !it.startsWith("g_") }
    return candidate ?: "محادثة خاصة"
}
private fun prettyTime(ms: Long): String {
    if (ms <= 0) return ""
    return SimpleDateFormat("h:mm a", Locale("ar")).format(Date(ms))
}
private fun shortTime(ms: Long): String {
    if (ms <= 0) return ""
    val elapsed = System.currentTimeMillis() - ms
    return if (elapsed in 0 until 86_400_000L) prettyTime(ms)
    else SimpleDateFormat("d MMM", Locale("ar")).format(Date(ms))
}
// Call history comes from the existing private conversation, never from raw SDP/ICE.
private fun callSummary(message: JSONObject, outgoing: Boolean): String {
    val video = message.optString("callType") == "video"
    val base = if (video) "مكالمة فيديو" else "مكالمة صوتية"
    val status = when (message.optString("callStatus")) {
        "answered" -> "تم الرد على المكالمة"
        "completed" -> "انتهت المكالمة"
        "rejected" -> "تم رفض المكالمة"
        "busy" -> "المستخدم مشغول"
        "missed" -> if (outgoing) "لم يتم الرد" else "مكالمة فائتة"
        "cancelled" -> if (outgoing) "تم إلغاء الاتصال" else "مكالمة فائتة"
        else -> if (System.currentTimeMillis() - message.optLong("ts") > 55_000L)
            { if (outgoing) "لم يتم الرد" else "مكالمة فائتة" } else "اتصال وارد"
    }
    return "$base • $status"
}

private fun chatLastText(message: JSONObject?, myUsername: String): String {
    if (message == null) return "اضغط لبدء المحادثة"
    if (message.optString("mediaType") == "call-event")
        return "📞 " + callSummary(message, message.optString("from").equals(myUsername, ignoreCase = true))
    val text = message.optString("text").trim()
    if (text.isNotBlank()) return text
    return when (message.optString("mediaType")) {
        "image" -> "📷 صورة"
        "audio" -> "🎙️ رسالة صوتية"
        "file" -> "📎 ملف PDF"
        "video" -> "🎬 فيديو"
        else -> if (message.optString("media").isNotBlank()) "مرفق" else "ابدأ المحادثة"
    }
}

@Composable
private fun ChatAvatar(person: JSONObject, size: Int = 55, showRing: Boolean = false, onClick: (() -> Unit)? = null) {
    val name = contactName(person)
    val avatar = media(person.optString("avatar"))
    val ring = if (showRing) ChatGold else Color(0xFF48443A)
    Box(Modifier.size(size.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .border(if (showRing) 2.dp else .7.dp, ring, CircleShape)
        .padding(if (showRing) 3.dp else 1.dp).clip(CircleShape)
        .background(Brush.linearGradient(listOf(Color(0xFF6D5124), Color(0xFF241A0D)))), contentAlignment = Alignment.Center) {
        if (avatar.isNotBlank()) ProfileMediaImage(avatar, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), color = ChatGoldBright, fontWeight = FontWeight.Bold,
            fontSize = (size * .38f).sp)
    }
}

@Composable
private fun ChatCircleAction(icon: String, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(ChatPanel).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        SiteIcon(icon, size = 23.dp, tint = ChatGoldBright)
    }
}

@Composable
private fun ChatSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(18.dp))
        .background(if (selected) ChatGold else ChatPanel)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, color = if (selected) Color(0xFF060707) else ChatSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp)
    }
}

@Composable
internal fun MessengerInbox(
    myUsername: String,
    contacts: List<JSONObject>,
    inbox: List<ChatPreview>,
    stories: List<JSONObject>,
    onAddStory: () -> Unit,
    onStory: (JSONObject) -> Unit,
    loading: Boolean,
    onOpen: (String) -> Unit,
    onProfile: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var segment by remember { mutableStateOf("الكل") }
    var showNewChat by remember { mutableStateOf(false) }
    if (showNewChat) {
        AlertDialog(
            onDismissRequest = { showNewChat = false },
            title = { Text("رسالة جديدة", color = ChatGoldBright) },
            text = {
                if (contacts.isEmpty()) Text("أضف أصدقاء من تبويب الأصدقاء أولًا", color = ChatSecondary)
                else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(contacts, key = { it.optString("username") }) { person ->
                        Row(Modifier.fillMaxWidth().clickable {
                            showNewChat = false
                            onOpen(person.optString("username"))
                        }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChatAvatar(person, 43, onClick = { onProfile(person.optString("username")) })
                            Spacer(Modifier.width(10.dp))
                            Text(contactName(person), color = ChatText, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNewChat = false }) { Text("إغلاق", color = ChatGold) }
            },
            containerColor = ChatPanel
        )
    }
    val previews = remember(contacts, inbox) {
        val byUser = inbox.associateBy { it.username.lowercase() }
        // Do not hide an existing chat just because the contact is not yet a friend.
        val allUsers = (inbox.map { it.user } + contacts)
            .filter { it.optString("username").isNotBlank() }
            .distinctBy { it.optString("username").lowercase() }
        allUsers.map { user ->
            byUser[user.optString("username").lowercase()] ?: ChatPreview(user, null)
        }.sortedWith(compareByDescending<ChatPreview> { it.timestamp }
            .thenBy { contactName(it.user) })
    }
    val visible = previews.filter { item ->
        (segment != "محادثات" || item.lastMessage != null) &&
        (query.isBlank() || contactName(item.user).contains(query.trim(), ignoreCase = true) ||
            item.username.contains(query.trim(), ignoreCase = true))
    }

    Column(Modifier.fillMaxSize().background(ChatBackground)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("الدردشات", fontWeight = FontWeight.ExtraBold, color = ChatText, fontSize = 23.sp)
            }
            ChatCircleAction("plus", "بدء دردشة") { showNewChat = true }
            Spacer(Modifier.width(5.dp))
            ChatCircleAction("repeat", "تحديث") { onRefresh() }
        }
        // Compact, responsive single row: All + Chats + Search (RTL).
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ChatSegment("الكل", segment == "الكل") { segment = "الكل" }
            ChatSegment("محادثات", segment == "محادثات") { segment = "محادثات" }
            Row(Modifier.weight(1f).height(37.dp)
                .border(1.dp, ChatDivider, RoundedCornerShape(19.dp))
                .background(ChatPanel, RoundedCornerShape(19.dp))
                .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SiteIcon("search", tint = ChatSecondary, size = 16.dp)
                Spacer(Modifier.width(5.dp))
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    cursorBrush = SolidColor(ChatGold),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = ChatText),
                    decorationBox = { innerField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isBlank()) Text("بحث...", fontSize = 12.sp, color = ChatSecondary)
                            innerField()
                        }
                    }
                )
            }
        }
        // Stories have one location: the message inbox, replacing the old friends strip.
        if (query.isBlank()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                StoryRing("إضافة قصة", "", true, diameter = 49, onClick = onAddStory)
                stories.forEach { story ->
                    val author = story.optJSONObject("authorInfo") ?: JSONObject().put("username", story.optString("author"))
                    StoryRing(contactName(author), author.optString("avatar"), false, diameter = 49) { onStory(story) }
                }
            }
        }
        HorizontalDivider(color = ChatDivider, modifier = Modifier.padding(top = 3.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ChatGold)
        if (visible.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                SiteIcon("message-circle", size = 48.dp, tint = ChatGold)
                Spacer(Modifier.height(15.dp))
                Text(if (query.isNotBlank()) "لا توجد نتائج" else if (segment == "محادثات") "لا توجد محادثات بعد" else "أضف أصدقاء لبدء الدردشة",
                    color = ChatText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("افتح اسم الصديق لبدء محادثة خاصة", color = ChatSecondary,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 7.dp)) {
                items(visible, key = { it.username }) { preview ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(preview.username) }
                        .padding(horizontal = 17.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ChatAvatar(preview.user, onClick = { onProfile(preview.username) })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(contactName(preview.user), color = ChatText,
                                fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(5.dp))
                            Text(chatLastText(preview.lastMessage, myUsername), color = ChatSecondary,
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(shortTime(preview.timestamp), color = ChatSecondary, fontSize = 10.sp)
                            Spacer(Modifier.height(7.dp))
                            SiteIcon("chevron-left", tint = ChatGold, size = 16.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatAudioInline(url: String, mine: Boolean) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    var playing by remember(url) { mutableStateOf(false) }
    DisposableEffect(player) { onDispose { player.release() } }
    Row(Modifier.clip(RoundedCornerShape(12.dp))
        .background(if (mine) Color(0x33FFFFFF) else Color(0xFF293136))
        .clickable {
            if (player.isPlaying) { player.pause(); playing = false }
            else { if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) player.seekTo(0); player.play(); playing = true }
        }.padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SiteIcon(if (playing) "square" else "play", size = 23.dp,
            tint = if (mine) Color(0xFF211A0A) else ChatGoldBright)
        Spacer(Modifier.width(10.dp))
        Text(if (playing) "إيقاف الصوت" else "تشغيل المقطع الصوتي",
            color = if (mine) Color(0xFF211A0A) else ChatText, fontSize = 12.sp)
    }
}

@Composable
private fun ChatPdfDocument(url: String, mine: Boolean) {
    val context = LocalContext.current
    Row(Modifier.clip(RoundedCornerShape(12.dp))
        .background(if (mine) Color(0x33FFFFFF) else Color(0xFF293136))
        .clickable {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                })
            }
        }.padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SiteIcon("paperclip", size = 21.dp, tint = if (mine) Color.Black else ChatGoldBright)
        Spacer(Modifier.width(9.dp))
        Text("فتح ملف PDF", color = if (mine) Color.Black else ChatText, fontSize = 12.sp)
    }
}

@Composable
private fun ChatBubble(message: JSONObject, mine: Boolean) {
    var videoExpanded by remember(message.optString("id")) { mutableStateOf(false) }
    val body = message.optString("text")
    val mediaUrl = media(message.optString("media"))
    val mediaType = message.optString("mediaType")
    // In RTL layout Start is the RIGHT side: my bubbles appear on the right.
    Box(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 4.dp),
        contentAlignment = if (mine) Alignment.CenterStart else Alignment.CenterEnd) {
        Column(Modifier.widthIn(max = 300.dp)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 5.dp else 18.dp, bottomEnd = if (mine) 18.dp else 5.dp))
            .background(if (mine) Brush.linearGradient(listOf(ChatGoldBright, ChatGold))
                else Brush.linearGradient(listOf(Color(0xFF22292D), Color(0xFF151B1F))))
            .padding(horizontal = 12.dp, vertical = 9.dp)) {
            if (mediaType == "call-event") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SiteIcon(if (message.optString("callType") == "video") "video" else "phone",
                        size = 21.dp, tint = if (mine) Color(0xFF17130B) else ChatGoldBright)
                    Spacer(Modifier.width(9.dp))
                    Text(callSummary(message, mine),
                        color = if (mine) Color(0xFF17130B) else ChatText,
                        fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
            } else if (body.isNotBlank()) Text(body, color = if (mine) Color(0xFF17130B) else ChatText,
                fontSize = 14.sp, lineHeight = 21.sp)
            if (mediaUrl.isNotBlank()) {
                Spacer(Modifier.height(if (body.isNotBlank()) 7.dp else 0.dp))
                if (mediaType == "image" || mediaType.isBlank()) {
                    ProfileMediaImage(mediaUrl, "صورة داخل المحادثة", Modifier
                        .widthIn(max = 250.dp).heightIn(min = 90.dp, max = 330.dp)
                        .clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                } else if (mediaType == "video") {
                    Column(Modifier.clip(RoundedCornerShape(11.dp))) {
                        if (videoExpanded) {
                            NativeVideo(mediaUrl)
                            TextButton(onClick = { videoExpanded = false }) {
                                Text("إيقاف الفيديو", color = if (mine) Color.Black else ChatGoldBright)
                            }
                        } else {
                            Box(Modifier.width(235.dp).height(136.dp)
                                .background(Color(0xFF141C20))
                                .clickable { videoExpanded = true }, contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    SiteIcon("play", size = 38.dp, tint = ChatGold)
                                    Spacer(Modifier.height(7.dp))
                                    Text("تشغيل الفيديو", color = ChatText, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else if (mediaType == "audio") {
                    ChatAudioInline(mediaUrl, mine)
                } else if (mediaType == "file") {
                    ChatPdfDocument(mediaUrl, mine)
                } else {
                    Text("📎 مرفق (${mediaType})", color = if (mine) Color.Black else ChatGoldBright)
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(prettyTime(message.optLong("ts")),
                    color = if (mine) Color(0xFF685124) else ChatSecondary, fontSize = 10.sp)
                if (mine) Text(if (message.optBoolean("read")) "✓✓" else if (message.optLong("deliveredAt") > 0L) "✓✓" else "✓",
                    color = Color(0xFF685124), fontSize = 11.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessengerThread(
    person: JSONObject,
    fallbackUsername: String,
    myUsername: String,
    messages: List<JSONObject>,
    chatText: String,
    sending: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickDocument: () -> Unit,
    onProfile: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val listState = rememberLazyListState()
    var showAttachments by remember(fallbackUsername) { mutableStateOf(false) }
    if (showAttachments) {
        ModalBottomSheet(onDismissRequest = { showAttachments = false },
            containerColor = ChatPanel, contentColor = ChatText,
            dragHandle = { BottomSheetDefaults.DragHandle(color = ChatGold) }) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("إرفاق بالمحادثة", color = ChatGoldBright, fontSize = 19.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                listOf(
                    Triple("image", "صورة من المعرض", onPickImage),
                    Triple("video", "فيديو من المعرض", onPickVideo),
                    Triple("mic", "ملف صوتي", onPickAudio),
                    Triple("paperclip", "ملف PDF", onPickDocument)
                ).forEach { (icon, label, callback) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1D2529)).clickable {
                            showAttachments = false
                            callback()
                        }.padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(Color(0xFF3A3020), CircleShape),
                            contentAlignment = Alignment.Center) {
                            SiteIcon(icon, size = 21.dp, tint = ChatGoldBright)
                        }
                        Spacer(Modifier.width(13.dp))
                        Text(label, fontSize = 14.sp, color = ChatText)
                    }
                }
                Text("الحد الأقصى للمرفق 50 ميجابايت", fontSize = 11.sp,
                    color = ChatSecondary, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
    val visibleMessages = remember(messages) {
        messages.filter { it.optString("mediaType") != "call-signal" }
    }
    LaunchedEffect(visibleMessages.size, fallbackUsername) {
        if (visibleMessages.isNotEmpty()) listState.animateScrollToItem(visibleMessages.lastIndex)
    }
    val name = contactName(person).takeIf { it != "مستخدم" } ?: "محادثة خاصة"
    Column(Modifier.fillMaxSize().background(ChatBackground).imePadding()) {
        Row(Modifier.fillMaxWidth().height(71.dp)
            .background(ChatPanel).padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center) {
                SiteIcon("chevron-left", tint = ChatGold, size = 26.dp)
            }
            Spacer(Modifier.width(4.dp))
            ChatAvatar(person, size = 43, onClick = onProfile)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = ChatText, fontWeight = FontWeight.Bold,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("محادثة خاصة", color = ChatSecondary, fontSize = 11.sp)
            }
            ChatCircleAction("phone", "اتصال صوتي", onVoiceCall)
            Spacer(Modifier.width(6.dp))
            ChatCircleAction("video", "اتصال مرئي", onVideoCall)
        }
        HorizontalDivider(color = ChatDivider)
        if (visibleMessages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                ChatAvatar(person, size = 78, showRing = true, onClick = onProfile)
                Spacer(Modifier.height(12.dp))
                Text(name, color = ChatGoldBright, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("ابدأ المحادثة برسالة لطيفة ✨", color = ChatSecondary,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        } else {
            LazyColumn(Modifier.weight(1f), state = listState,
                contentPadding = PaddingValues(top = 15.dp, bottom = 12.dp)) {
                items(visibleMessages, key = { it.optString("id").ifBlank { it.hashCode().toString() } }) { message ->
                    ChatBubble(message, message.optString("from").equals(myUsername, ignoreCase = true))
                }
            }
        }
        HorizontalDivider(color = ChatDivider)
        // Composer stays at the BOTTOM of the conversation, just above the IME.
        // Deliberately keep the plus / send actions outside the text field.
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(ChatPanel)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF332919))
                    .border(1.dp, ChatGold.copy(alpha = 0.55f), CircleShape)
                    .clickable(enabled = !sending) { showAttachments = true },
                contentAlignment = Alignment.Center
            ) {
                SiteIcon("plus", size = 28.dp, tint = ChatGoldBright)
            }

            // BasicTextField avoids TextField's large built-in vertical insets.
            // Keep the entered characters white on black, even while focused.
            BasicTextField(
                value = chatText,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f)
                    .heightIn(min = 52.dp, max = 122.dp)
                    .border(1.5.dp, ChatGold, RoundedCornerShape(27.dp))
                    .background(ChatBackground, RoundedCornerShape(27.dp))
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                textStyle = TextStyle(
                    color = ChatText,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Start
                ),
                cursorBrush = SolidColor(ChatGoldBright),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (chatText.isNotBlank() && !sending) onSend() }
                ),
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (chatText.isEmpty()) {
                            Text("اكتب رسالة...", color = ChatSecondary, fontSize = 15.sp)
                        }
                        innerTextField()
                    }
                }
            )

            val canSend = chatText.isNotBlank() && !sending
            Box(
                modifier = Modifier.size(52.dp)
                    .clip(CircleShape)
                    .background(if (canSend) ChatGold else Color(0xFF332919))
                    .border(1.dp, ChatGold.copy(alpha = 0.5f), CircleShape)
                    .attaGoldShine(canSend)
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                SiteIcon(
                    "send", size = 26.dp,
                    tint = if (canSend) ChatBackground else ChatGoldBright
                )
            }
        }
    }
}
