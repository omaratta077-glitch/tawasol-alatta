package com.alatta.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

private fun Context.mediaActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.mediaActivity()
    else -> null
}

/** A feed preview does not create a player for every off-screen post. */
@Composable
internal fun AttaExpandableVideo(url: String, onOpen: (() -> Unit)? = null) {
    var expanded by remember(url) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
        .background(Color.Black).clickable { if (onOpen != null) onOpen() else expanded = true },
        contentAlignment = Alignment.Center) {
        Surface(color = Color(0xDD161B20), shape = RoundedCornerShape(50)) {
            SiteIcon("play", size = 42.dp, tint = Color.White, modifier = Modifier.padding(15.dp))
        }
    }
    if (expanded) AttaPostMediaViewer(
        posts = listOf(JSONObject().put("id", "attachment").put("video", url)),
        startId = "attachment", kind = "video", onDismiss = { expanded = false }
    )
}

/** One fullscreen viewer and, for its whole video playlist, exactly one Media3 player. */
@Composable
internal fun AttaPostMediaViewer(
    posts: List<JSONObject>, startId: String, kind: String,
    onDismiss: () -> Unit,
    loadInteractions: PostInteractionsLoader? = null,
    sendReaction: PostReactionSender? = null,
    sendComment: PostCommentSender? = null,
    onStats: (String, JSONObject) -> Unit = { _, _ -> },
    onOpenProfile: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isVideo = kind == "video"
    val items = remember(posts, kind) { posts.filter { media(it.optString(kind)).isNotBlank() }.distinctBy { it.optString("id") } }
    if (items.isEmpty()) return
    var index by remember(startId, kind) { mutableIntStateOf(items.indexOfFirst { it.optString("id") == startId }.coerceAtLeast(0)) }
    val current = items[index.coerceIn(items.indices)]
    val id = current.optString("id")
    val url = media(current.optString(kind))
    var sheet by remember(id) { mutableStateOf<String?>(null) }
    var controls by remember(id) { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var failed by remember(id) { mutableStateOf("") }
    var progress by remember(id) { mutableFloatStateOf(0f) }
    var duration by remember(id) { mutableStateOf(0L) }
    var reactions by remember(id) { mutableIntStateOf(postReactionCount(current)) }
    var comments by remember(id) { mutableIntStateOf(current.optInt("commentCount", current.optJSONArray("comments")?.length() ?: 0)) }
    var selected by remember(id) { mutableStateOf(current.optString("myReaction").ifBlank { if (current.optBoolean("likedByMe")) "like" else "" }) }
    var reacting by remember(id) { mutableStateOf(false) }
    val player = remember(isVideo) { if (isVideo) ExoPlayer.Builder(context).build() else null }
    val updateStats: (String, JSONObject) -> Unit = { postId, data ->
        if (postId == id) {
            reactions = data.optInt("reactionCount", reactions)
            comments = data.optInt("commentCount", comments)
            selected = data.optString("myReaction", selected)
        }
        onStats(postId, data)
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onPlayerError(error: PlaybackException) { failed = "تعذر تشغيل الفيديو. أعد المحاولة أو تحقق من الاتصال."; controls = true }
        }
        player?.addListener(listener)
        val activity = context.mediaActivity()
        val lifecycleListener = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player?.pause() }
        activity?.lifecycle?.addObserver(lifecycleListener)
        onDispose { activity?.lifecycle?.removeObserver(lifecycleListener); player?.removeListener(listener); player?.release() }
    }
    var continuePlayback by remember { mutableStateOf(false) }
    LaunchedEffect(id, url) {
        player?.apply {
            pause(); clearMediaItems(); setMediaItem(MediaItem.fromUri(url)); prepare()
            playWhenReady = continuePlayback
            continuePlayback = false
        }
        if (loadInteractions != null) {
            try { updateStats(id, loadInteractions(id, "reactions", 0)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Reading media remains available during an API outage. */ }
        }
    }
    LaunchedEffect(player, id) {
        while (player != null) {
            duration = player.duration.coerceAtLeast(0)
            progress = if (duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            delay(350)
        }
    }
    LaunchedEffect(playing, controls, sheet) { if (playing && controls && sheet == null) { delay(2500); controls = false } }
    val changeVideo: (Int) -> Unit = { delta ->
        val next = index + delta
        if (next in items.indices) { continuePlayback = player?.playWhenReady == true; player?.pause(); index = next; failed = ""; controls = true }
    }
    val react: (String) -> Unit = { choice ->
        if (!reacting && sendReaction != null && loadInteractions != null) scope.launch {
            reacting = true; failed = ""
            try { sendReaction(id, choice, selected == choice); updateStats(id, loadInteractions(id, "reactions", 0)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { failed = e.message ?: "تعذر تسجيل التفاعل"; controls = true }
            finally { reacting = false }
        }
    }
    Dialog(onDismissRequest = { if (sheet != null) sheet = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                WindowCompat.getInsetsController(it, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
                it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        BackHandler { if (sheet != null) sheet = null else onDismiss() }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (player != null) {
                AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                    this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                } }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
                var drag by remember(id) { mutableFloatStateOf(0f) }
                Box(Modifier.fillMaxSize().clickable { controls = !controls }
                    .pointerInput(id) { detectVerticalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragCancel = { drag = 0f },
                        onDragEnd = { if (abs(drag) > 80.dp.toPx()) changeVideo(if (drag < 0) 1 else -1); drag = 0f },
                        onVerticalDrag = { change, amount -> change.consume(); drag += amount }
                    ) })
                if (!playing) IconButton(onClick = {
                    if (player.playWhenReady) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); if (player.playerError != null) { failed = ""; player.prepare() }; player.play() }
                    controls = true
                }, modifier = Modifier.align(Alignment.Center).size(76.dp).background(Color(0x99000000), RoundedCornerShape(50))) {
                    if (buffering) CircularProgressIndicator(color = Color(0xFFE6B94E), modifier = Modifier.size(34.dp))
                    else SiteIcon(if (playing) "pause" else "play", size = 38.dp, tint = Color.White)
                }
            } else AttaZoomableImage(url, "صورة المنشور", onTap = { controls = !controls })
            // Playback controls belong over the middle of the VIDEO.
            // Social actions stay beneath it, near the bottom edge.
            if (player != null && controls) Column(
                Modifier.align(Alignment.Center).fillMaxWidth()
                    .background(Color(0x88000000))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (failed.isNotBlank()) {
                    Text(failed, color = Color(0xFFFFB5B5), fontSize = 13.sp)
                    if (player.playerError != null) TextButton(onClick = {
                        failed = ""; player.prepare(); player.play()
                    }) { Text("إعادة المحاولة", color = Color(0xFFE6B94E)) }
                }
                if (duration > 0) {
                    Slider(value = progress, onValueChange = {
                        progress = it; player.seekTo((duration * it).toLong())
                    }, modifier = Modifier.fillMaxWidth().height(28.dp),
                        colors = SliderDefaults.colors(thumbColor = Color.White,
                            activeTrackColor = Color(0xFFE6B94E)))
                    val currentSeconds = player.currentPosition.coerceAtLeast(0) / 1000
                    val totalSeconds = duration / 1000
                    Text("%02d:%02d / %02d:%02d".format(currentSeconds / 60,
                        currentSeconds % 60, totalSeconds / 60, totalSeconds % 60),
                        color = Color.White, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        player.seekTo((player.currentPosition - 10000L).coerceAtLeast(0))
                    }) { SiteIcon("rotate-ccw", size = 23.dp, tint = Color.White) }
                    IconButton(onClick = { if (playing) player.pause() else player.play() }) {
                        SiteIcon(if (playing) "pause" else "play", size = 29.dp,
                            tint = Color(0xFFE6B94E))
                    }
                    IconButton(onClick = {
                        player.seekTo((player.currentPosition + 10000L)
                            .coerceAtMost(duration.coerceAtLeast(0)))
                    }) { SiteIcon("rotate-cw", size = 23.dp, tint = Color.White) }
                }
            }
            if (controls && loadInteractions != null && sendComment != null) Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                    .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (loadInteractions != null && sendComment != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    PremiumReactionAction(reactions, selected == "like", selected,
                        onLike = { react("like") }, onReact = react, onShowPeople = { sheet = "reactions" })
                    PremiumPostAction("message-circle", comments.toString(), Color.White) { sheet = "comments" }
                }
            }
        }
        if (sheet != null && loadInteractions != null && sendComment != null) key(id, sheet) {
            PostInteractionsSheet(id, sheet!!, loadInteractions, sendComment, updateStats,
                onOpenProfile = { username -> onDismiss(); onOpenProfile(username) }, onDismiss = { sheet = null })
        }
    }
}

/** Real callbacks are optional; never fabricate post interactions in media viewer. */
internal typealias PostInteractionsLoader = suspend (postId: String, category: String, offset: Int) -> JSONObject
internal typealias PostReactionSender = suspend (postId: String, reaction: String, remove: Boolean) -> Unit
internal typealias PostCommentSender = suspend (postId: String, message: String) -> JSONObject

private fun postReactionCount(post: JSONObject): Int =
    post.optInt("reactionCount", post.optInt("likesCount", post.optJSONArray("reactions")?.length() ?: 0))

/** Reuse the existing image loader, supporting pinch zoom without a duplicate player. */
@Composable
private fun AttaZoomableImage(url: String, description: String, onTap: () -> Unit) {
    var zoom by remember(url) { mutableFloatStateOf(1f) }
    var shift by remember(url) { mutableStateOf(Offset.Zero) }
    ProfileMediaImage(url, description,
        Modifier.fillMaxSize()
            .pointerInput(url) {
                detectTransformGestures { _, pan, scale, _ ->
                    zoom = (zoom * scale).coerceIn(1f, 4f)
                    shift = if (zoom > 1f) shift + pan else Offset.Zero
                }
            }
            .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = shift.x, translationY = shift.y)
            .clickable(onClick = onTap),
        contentScale = ContentScale.Fit)
}

/** Render comments/reactions only with working server callbacks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostInteractionsSheet(
    postId: String, tab: String,
    loadInteractions: PostInteractionsLoader,
    sendComment: PostCommentSender,
    onStats: (String, JSONObject) -> Unit,
    onOpenProfile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var payload by remember(postId, tab) { mutableStateOf<JSONObject?>(null) }
    var failure by remember(postId, tab) { mutableStateOf<String?>(null) }
    var content by remember(postId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(postId, tab) {
        try { payload = loadInteractions(postId, tab, 0) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = "تعذّر تحميل البيانات" }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF12161A)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(if (tab == "comments") "التعليقات" else "التفاعلات", color = Color(0xFFE6B94E))
            failure?.let { Text(it, color = Color(0xFFFFB5B5)) }
            val records = payload?.optJSONArray("items")
                ?: payload?.optJSONArray(if (tab == "comments") "comments" else "reactions")
                ?: payload?.optJSONArray("users")
            if (records == null && failure == null) CircularProgressIndicator(color = Color(0xFFE6B94E))
            if (records != null) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(records.length()) { position ->
                    val item = records.optJSONObject(position)
                    if (item != null) {
                        val username = item.optString("username", item.optString("user"))
                        val displayName = item.optString("displayName", username)
                        val message = item.optString("text", item.optString("comment"))
                        Column(Modifier.fillMaxWidth()
                            .clickable { if (username.isNotBlank()) onOpenProfile(username) }
                            .padding(vertical = 9.dp)) {
                            Text(displayName, color = Color(0xFFE6B94E))
                            if (message.isNotBlank()) Text(message, color = Color.White)
                        }
                    }
                }
            }
            if (tab == "comments") {
                OutlinedTextField(value = content, onValueChange = { content = it },
                    label = { Text("اكتب تعليقًا", color = Color.LightGray) },
                    modifier = Modifier.fillMaxWidth(), singleLine = false)
                TextButton(onClick = {
                    if (content.isNotBlank() && !sending) scope.launch {
                        sending = true
                        try {
                            val response = sendComment(postId, content.trim())
                            onStats(postId, response)
                            content = ""
                            payload = loadInteractions(postId, "comments", 0)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failure = "تعذّر إرسال التعليق" }
                        finally { sending = false }
                    }
                }, enabled = content.isNotBlank() && !sending) {
                    Text("إرسال", color = Color(0xFFE6B94E))
                }
            }
        }
    }
}
