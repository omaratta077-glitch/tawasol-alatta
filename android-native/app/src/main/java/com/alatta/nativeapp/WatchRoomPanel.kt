package com.alatta.nativeapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer

@Composable
internal fun WatchRoomPanel(token: String, channels: List<FootballChannel>, callInProgress: Boolean,
    entryRequest: Int, onPlay: (FootballChannel) -> Unit, onBeforeJoin: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var dialog by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var channelId by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var expanded by WatchRoomController::expanded
    val room = WatchRoomController.room
    val active = WatchRoomController.active
    val visibleChannels = channels.filter { it.enabled }
    LaunchedEffect(entryRequest) {
        if (entryRequest > 0 && token.isNotBlank()) {
            if (active) expanded = true
            else {
                channelId = visibleChannels.firstOrNull()?.id.orEmpty()
                error = ""; dialog = true
            }
        }
    }
    fun start() {
        if (callInProgress || active || WatchRoomController.closing) { error = "غادر المكالمة الحالية أولًا"; return }
        val owner = context.getSharedPreferences("alatta_native", Context.MODE_PRIVATE).getString("username", "").orEmpty()
        onBeforeJoin()
        WatchRoomController.begin(context, owner, action, if (action == "join") watchRoomCode(code).orEmpty() else "", channelId)
        dialog = false; expanded = true
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all { name ->
            androidx.core.content.ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
        }) start() else error = "اسمح بالكاميرا والميكروفون لبدء المكالمة الجماعية"
    }
    fun join(kind: String) {
        if (kind == "join" && watchRoomCode(code) == null) { error = "اكتب كود الغرفة المكوّن من ثلاثة حروف إنجليزية"; return }
        action = kind; error = ""
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = needed.filter { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) start() else permissions.launch(missing.toTypedArray())
    }
    if (!active) {
        if (WatchRoomController.status.isNotBlank()) Text(WatchRoomController.status, color = PremiumMuted, fontSize = 11.sp)
    } else if (expanded) FootballPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("غرفة المشاهدة • ${room?.members?.size ?: 1}/6", color = PremiumGoldLight, modifier = Modifier.weight(1f), fontSize = 13.sp)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "تصغير" else "إظهار") }
        }
        Text(WatchRoomController.status, color = PremiumMuted, fontSize = 11.sp)
        if (expanded) {
            if (room != null && (context as? MainActivity)?.youtubePipMode != true)
                WatchVideoStage(room, WatchRoomController.engine, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            room?.let { state ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.code, color = PremiumGoldLight, modifier = Modifier.weight(1f), fontSize = 20.sp)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(state.code)) }) { Text("نسخ الكود") }
                    IconButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "انضم لغرفة المشاهدة في العطا. الكود: ${state.code}")
                    }, "مشاركة كود الغرفة")) }) { SiteIcon("share", size = 18.dp) }
                }
                channels.firstOrNull { it.id == state.channelId && it.enabled }?.let { channel ->
                    OutlinedButton(enabled = WatchRoomController.ready, onClick = { onPlay(channel) }, modifier = Modifier.fillMaxWidth()) {
                        Text("شغّل ${channel.name}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                WatchAction(if (WatchRoomController.muted) "mic-off" else "mic", "كتم الميكروفون", WatchRoomController.ready) { WatchRoomController.toggleMute() }
                WatchAction(if (WatchRoomController.cameraOff) "camera-off" else "camera", "إغلاق الكاميرا", WatchRoomController.ready) { WatchRoomController.toggleCamera() }
                WatchAction("repeat", "تبديل الكاميرا", WatchRoomController.ready && !WatchRoomController.cameraOff) { WatchRoomController.engine?.switchCamera() }
                WatchAction("phone-off", "مغادرة الغرفة", true) { WatchRoomController.status = "غادرت غرفة المشاهدة"; WatchRoomController.stop(context) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("صوت الأصحاب", color = PremiumMuted, fontSize = 11.sp)
                Slider(WatchRoomController.volume, onValueChange = { WatchRoomController.adjustVolume(it) }, modifier = Modifier.weight(1f))
            }
        } else TextButton(onClick = { WatchRoomController.stop(context) }) { Text("مغادرة", color = Color(0xFFFF9090)) }
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text("مشاهدة مع الأصحاب") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ابدأ بشخصين، وينضم أصحابك حتى ستة. شارك الكود مع أصدقائك المضافين في العطا.", fontSize = 13.sp)
            Text("ستعمل الكاميرا والميكروفون أثناء المكالمة، ويمكن تصغيرها وتشغيل القناة.", fontSize = 12.sp)
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(visibleChannels.firstOrNull { it.id == channelId }?.name ?: "بدون قناة محددة", fontSize = 12.sp) }
                DropdownMenu(menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 220.dp)) {
                    visibleChannels.forEach { channel -> DropdownMenuItem(text = { Text(channel.name) }, onClick = { channelId = channel.id; menu = false }) }
                }
            }
            Button(enabled = !callInProgress, onClick = { join("create") }, modifier = Modifier.fillMaxWidth()) { Text("إنشاء غرفة") }
            OutlinedTextField(code, onValueChange = { code = it.filter { c -> c in 'A'..'Z' || c in 'a'..'z' }.take(3).uppercase(); error = "" },
                label = { Text("كود غرفة صاحبك • ABC") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                trailingIcon = { TextButton(onClick = {
                    val pasted = watchRoomInviteCode(clipboard.getText()?.text.orEmpty())
                    if (pasted == null) error = "انسخ كود الغرفة أو رسالة دعوتها أولًا" else { code = pasted; error = "" }
                }) { Text("لصق") } })
            OutlinedButton(enabled = !callInProgress, onClick = { join("join") }, modifier = Modifier.fillMaxWidth()) { Text("دخول الغرفة") }
            if (error.isNotBlank()) Text(error, color = Color(0xFFFF9090), fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { dialog = false }) { Text("رجوع") } })
}

@Composable
private fun WatchAction(icon: String, label: String, enabled: Boolean, action: () -> Unit) {
    IconButton(enabled = enabled, onClick = action, modifier = Modifier.semantics { contentDescription = label }) {
        SiteIcon(if (icon == "phone-off") "phone" else icon, size = 21.dp,
            modifier = if (icon == "phone-off") Modifier.rotate(135f) else Modifier,
            tint = if (icon == "phone-off") Color(0xFFFF8080) else PremiumGoldLight)
    }
}

@Composable
internal fun WatchVideoStage(room: WatchRoom, engine: WatchRoomEngine?, modifier: Modifier = Modifier) {
    val columns = if (room.members.size > 4) 3 else if (room.members.size > 1) 2 else 1
    val rows = room.members.chunked(columns)
    Column(modifier.background(Color.Black), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in rows) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (member in row) key(member.peerId) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color(0xFF15191C)), contentAlignment = Alignment.Center) {
                    if (engine != null && member.cameraOn && !(member.peerId == room.myPeerId && WatchRoomController.cameraOff))
                        WatchVideoTile(member.peerId, engine)
                    else Text(member.name.take(1), color = PremiumGoldLight, fontSize = 24.sp)
                    Text(member.name + if (!member.micOn) " • صامت" else "", color = Color.White, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .65f)).padding(3.dp))
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
@Composable private fun WatchVideoTile(id: String, engine: WatchRoomEngine) {
    val context = LocalContext.current
    val renderer = remember(id, engine) { SurfaceViewRenderer(context) }
    DisposableEffect(renderer) { onDispose { engine.detach(renderer) } }
    AndroidView(factory = { engine.attach(id, renderer); renderer }, modifier = Modifier.fillMaxSize())
}

@Composable
internal fun WatchRoomCallBubble(onReturn: () -> Unit) {
    val room = WatchRoomController.room
    val person = room?.members?.firstOrNull { it.peerId != room.myPeerId } ?: room?.members?.firstOrNull()
    FloatingCallBubble { host ->
        Box(host.shadow(8.dp, CircleShape).clip(CircleShape).background(Color(0xFF191C20))
            .border(2.dp, PremiumGoldLight, CircleShape).semantics { contentDescription = "العودة لمكالمة غرفة المشاهدة؛ اسحب لتحريكها" }
            .clickable { WatchRoomController.expanded = true; onReturn() }, contentAlignment = Alignment.Center) {
            if (!person?.avatar.isNullOrBlank()) ProfileMediaImage(media(person!!.avatar), person.name,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(person?.name?.take(1) ?: "ع", color = PremiumGoldLight, fontSize = 27.sp)
            Box(Modifier.align(Alignment.BottomCenter).size(22.dp).clip(CircleShape).background(PremiumGoldLight), contentAlignment = Alignment.Center) {
                SiteIcon(if (WatchRoomController.muted) "mic-off" else "video", size = 13.dp, tint = Color.Black)
            }
        }
    }
}
