package com.alatta.nativeapp

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

private fun contactName(person: JSONObject): String = person.optString("fullName").ifBlank {
    person.optString("displayName").ifBlank { person.optString("username", "مستخدم") }
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
private fun chatLastText(message: JSONObject?): String {
    if (message == null) return "اضغط لبدء المحادثة"
    val text = message.optString("text").trim()
    if (text.isNotBlank()) return text
    return when (message.optString("mediaType")) {
        "image" -> "📷 صورة"
        "audio" -> "🎙️ رسالة صوتية"
        "video" -> "🎬 فيديو"
        else -> if (message.optString("media").isNotBlank()) "مرفق" else "ابدأ المحادثة"
    }
}

@Composable
private fun ChatAvatar(person: JSONObject, size: Int = 55, showRing: Boolean = false) {
    val name = contactName(person)
    val avatar = media(person.optString("avatar"))
    val ring = if (showRing) ChatGold else Color(0xFF48443A)
    Box(Modifier.size(size.dp).border(if (showRing) 2.dp else .7.dp, ring, CircleShape)
        .padding(if (showRing) 3.dp else 1.dp).clip(CircleShape)
        .background(Brush.linearGradient(listOf(Color(0xFF6D5124), Color(0xFF241A0D)))), contentAlignment = Alignment.Center) {
        if (avatar.isNotBlank()) AsyncImage(avatar, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), color = ChatGoldBright, fontWeight = FontWeight.Bold,
            fontSize = (size * .38f).sp)
    }
}

@Composable
private fun ChatCircleAction(icon: String, description: String, onClick: () -> Unit) {
    Box(Modifier.size(43.dp).clip(CircleShape).background(ChatPanel).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        SiteIcon(icon, size = 21.dp, tint = ChatGoldBright)
    }
}

@Composable
private fun ChatSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(19.dp))
        .background(if (selected) ChatGold else ChatPanel)
        .clickable(onClick = onClick)
        .padding(horizontal = 17.dp, vertical = 8.dp)) {
        Text(label, color = if (selected) Color(0xFF060707) else ChatSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
    }
}

@Composable
internal fun MessengerInbox(
    contacts: List<JSONObject>,
    inbox: List<ChatPreview>,
    loading: Boolean,
    onOpen: (String) -> Unit,
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
                            ChatAvatar(person, 43)
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
        val byUser = inbox.associateBy { it.username }
        contacts.filter { it.optString("username").isNotBlank() }
            .map { byUser[it.optString("username")] ?: ChatPreview(it, null) }
            .sortedWith(compareByDescending<ChatPreview> { it.timestamp }
                .thenBy { contactName(it.user) })
    }
    val visible = previews.filter { item ->
        (segment != "محادثات" || item.lastMessage != null) &&
        (query.isBlank() || contactName(item.user).contains(query.trim(), ignoreCase = true) ||
            item.username.contains(query.trim(), ignoreCase = true))
    }

    Column(Modifier.fillMaxSize().background(ChatBackground)) {
        Row(Modifier.fillMaxWidth().padding(start = 17.dp, end = 17.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("الدردشات", fontWeight = FontWeight.ExtraBold, color = ChatText, fontSize = 29.sp)
                Text("تواصل العطا", color = ChatGold, fontSize = 12.sp)
            }
            ChatCircleAction("plus", "بدء دردشة") { showNewChat = true }
            Spacer(Modifier.width(10.dp))
            ChatCircleAction("repeat", "تحديث") { onRefresh() }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp)
            .border(1.dp, ChatDivider, RoundedCornerShape(23.dp))
            .background(ChatPanel, RoundedCornerShape(23.dp))
            .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SiteIcon("search", tint = ChatSecondary, size = 19.dp)
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.TextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("بحث عن محادثة أو صديق", fontSize = 14.sp, color = ChatSecondary) },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = ChatText, unfocusedTextColor = ChatText,
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = ChatText)
            )
        }
        Row(Modifier.padding(horizontal = 17.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatSegment("الكل", segment == "الكل") { segment = "الكل" }
            ChatSegment("محادثات", segment == "محادثات") { segment = "محادثات" }
        }
        // These are contacts, NOT claimed to be online. Presence requires a separate API.
        if (contacts.isNotEmpty() && query.isBlank()) {
            Text("الأصدقاء", color = ChatSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                contacts.take(16).forEach { user ->
                    Column(Modifier.width(70.dp).clickable { onOpen(user.optString("username")) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChatAvatar(user, size = 59, showRing = true)
                        Text(contactName(user), color = ChatText, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        HorizontalDivider(color = ChatDivider, modifier = Modifier.padding(top = 5.dp))
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
                        ChatAvatar(preview.user)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(contactName(preview.user), color = ChatText,
                                fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(5.dp))
                            Text(chatLastText(preview.lastMessage), color = ChatSecondary,
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
private fun ChatBubble(message: JSONObject, mine: Boolean) {
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
            if (body.isNotBlank()) Text(body, color = if (mine) Color(0xFF17130B) else ChatText,
                fontSize = 14.sp, lineHeight = 21.sp)
            if (mediaUrl.isNotBlank()) {
                Spacer(Modifier.height(if (body.isNotBlank()) 7.dp else 0.dp))
                if (mediaType == "image" || mediaType.isBlank()) {
                    AsyncImage(mediaUrl, "صورة داخل المحادثة", Modifier
                        .widthIn(max = 250.dp).heightIn(min = 90.dp, max = 330.dp)
                        .clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
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
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, fallbackUsername) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    val name = contactName(person).takeIf { it != "مستخدم" } ?: fallbackUsername
    Column(Modifier.fillMaxSize().background(ChatBackground).imePadding()) {
        Row(Modifier.fillMaxWidth().height(71.dp)
            .background(ChatPanel).padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center) {
                SiteIcon("chevron-left", tint = ChatGold, size = 26.dp)
            }
            Spacer(Modifier.width(4.dp))
            ChatAvatar(person, size = 43)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = ChatText, fontWeight = FontWeight.Bold,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("محادثة خاصة", color = ChatSecondary, fontSize = 11.sp)
            }
            ChatCircleAction("repeat", "تحديث الرسائل", onRefresh)
        }
        HorizontalDivider(color = ChatDivider)
        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                ChatAvatar(person, size = 78, showRing = true)
                Spacer(Modifier.height(12.dp))
                Text(name, color = ChatGoldBright, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("ابدأ المحادثة برسالة لطيفة ✨", color = ChatSecondary,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        } else {
            LazyColumn(Modifier.weight(1f), state = listState,
                contentPadding = PaddingValues(top = 15.dp, bottom = 12.dp)) {
                items(messages, key = { it.optString("id").ifBlank { it.hashCode().toString() } }) { message ->
                    if (message.optString("mediaType") != "call-signal") {
                        ChatBubble(message, message.optString("from").equals(myUsername, ignoreCase = true))
                    }
                }
            }
        }
        HorizontalDivider(color = ChatDivider)
        Row(Modifier.fillMaxWidth().background(ChatPanel).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(43.dp).clip(CircleShape).clickable(enabled = !sending, onClick = onPickImage),
                contentAlignment = Alignment.Center) {
                SiteIcon("image", size = 25.dp, tint = ChatGold)
            }
            OutlinedTextField(value = chatText, onValueChange = onTextChange,
                modifier = Modifier.weight(1f).heightIn(min = 49.dp, max = 124.dp),
                maxLines = 4, shape = RoundedCornerShape(26.dp),
                placeholder = { Text("اكتب رسالة...", fontSize = 14.sp, color = ChatSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = ChatText, unfocusedTextColor = ChatText,
                    focusedBorderColor = ChatGold, unfocusedBorderColor = ChatDivider,
                    focusedContainerColor = ChatBackground, unfocusedContainerColor = ChatBackground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (chatText.isNotBlank() && !sending) onSend() }))
            Box(Modifier.size(44.dp).clip(CircleShape)
                .background(if (chatText.isNotBlank() && !sending) ChatGold else Color(0xFF3C3324))
                .clickable(enabled = chatText.isNotBlank() && !sending, onClick = onSend),
                contentAlignment = Alignment.Center) {
                SiteIcon("send", size = 22.dp,
                    tint = if (chatText.isNotBlank() && !sending) Color(0xFF080909) else ChatSecondary)
            }
        }
    }
}
