package com.alatta.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

internal fun Context.mediaActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.mediaActivity()
    else -> null
}

@Composable
internal fun AttaExpandableVideo(url: String, posterUrl: String? = null, onOpen: (() -> Unit)? = null) {
    val context = LocalContext.current
    var expanded by remember(url) { mutableStateOf(false) }
    var posterFailed by remember(url, posterUrl) { mutableStateOf(false) }
    var retry by remember(url) { mutableIntStateOf(0) }
    val needsFrame = posterUrl.isNullOrBlank() || posterFailed
    var loading by remember(url) { mutableStateOf(false) }
    val preview by produceState<Bitmap?>(initialValue = null, url, needsFrame, retry) {
        if (needsFrame) {
            loading = true
            try { value = VideoPostPreview.remote(context, url) } finally { loading = false }
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
        .background(Color.Black).clickable { if (onOpen != null) onOpen() else expanded = true },
        contentAlignment = Alignment.Center) {
        // احتفظ دائمًا بالصورة المستخرجة أسفل الملصق البعيد الاختياري.
        preview?.let { frame ->
            Image(frame.asImageBitmap(), contentDescription = "معاينة الفيديو",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (!posterUrl.isNullOrBlank() && !posterFailed) {
            coil.compose.AsyncImage(model = remember(posterUrl) { coil.request.ImageRequest.Builder(context).data(posterUrl).allowHardware(false).build() }, contentDescription = "صورة معاينة الفيديو",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                onError = { posterFailed = true }, onSuccess = { state ->
                    // قد تكون صورة الغلاف في الإصدار v120 إطارًا أول أسود تم تحميله بنجاح.
                    val bitmap = (state.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap != null && bitmap.config != Bitmap.Config.HARDWARE && VideoPostPreview.mostlyBlack(bitmap)) posterFailed = true
                })
        }
        if (needsFrame && preview == null) TextButton(onClick = { retry++ },
            modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(if (loading) "جارٍ تحميل صورة الفيديو…" else "إعادة تحميل صورة الفيديو", color = Color(0xFFE6B94E), fontSize = 11.sp)
        }
        Surface(color = Color(0xBB101418), shape = RoundedCornerShape(50),
            border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFE6B94E))) {
            SiteIcon("play", size = 35.dp, tint = Color(0xFFE6B94E), modifier = Modifier.padding(13.dp))
        }
    }
    if (expanded) AttaPostMediaViewer(
        posts = listOf(JSONObject().put("id", "attachment").put("video", url)),
        startId = "attachment", kind = "video", onDismiss = { expanded = false },
        reelsOnly = true // يفتح نفس عارض الريلز، لا تخطيط ملء شاشة ثانٍ.
    )
}

/** عنصر واحد في ريلز العطا: إمّا ملف فيديو أو فيديو YouTube مرفق بمنشور.
 * تُحفظ هويّة المنشور الحقيقية للتعليقات والتفاعل؛ المفتاح الخاص يمنع ضياع فيديو إن وُجد النوعان بمنشور واحد.
 */
internal data class AttaReelEntry(val post: JSONObject, val key: String, val videoUrl: String = "", val youtubeId: String? = null)

/** الريلز تضم فيديوهات العطا المرفوعة وYouTube Shorts التي نُشرت صراحة كشورت.
 * مقاطع YouTube الأفقية العادية لا تظهر هنا، ولا تُنزَّل أو تُعاد استضافتها.
 */
internal fun uploadedPostReels(posts: List<JSONObject>): List<AttaReelEntry> = buildList {
    for (post in posts) {
        val postId = post.optString("id")
        val url = media(post.optString("video"))
        if (url.isNotBlank()) add(AttaReelEntry(post, "$postId:video", videoUrl = url))
        youtubeShortPostId(post.optString("text"))?.let { shortId ->
            add(AttaReelEntry(post, "$postId:short:$shortId", youtubeId = shortId))
        }
    }
}.distinctBy { it.key }

/** مشغّل ملء شاشة واحد ومشغّل Media3 واحد طوال قائمة الفيديوهات كلها. */
internal data class MediaPlaybackSnapshot(val stage: String, val state: Int = Player.STATE_IDLE,
    val playWhenReady: Boolean = false, val isLoading: Boolean = false, val isPlaying: Boolean = false,
    val suppressionReason: Int = 0, val width: Int = 0, val height: Int = 0)

private fun mediaPlaybackSnapshot(stage: String, player: Player) = MediaPlaybackSnapshot(stage,
    player.playbackState, player.playWhenReady, player.isLoading, player.isPlaying,
    player.playbackSuppressionReason, player.videoSize.width, player.videoSize.height)

@Composable
internal fun AttaPostMediaViewer(
    posts: List<JSONObject>, startId: String, kind: String,
    onDismiss: () -> Unit,
    loadInteractions: PostInteractionsLoader? = null,
    sendReaction: PostReactionSender? = null,
    sendComment: PostCommentSender? = null,
    onShare: (suspend (String) -> Unit)? = null,
    onStats: (String, JSONObject) -> Unit = { _, _ -> },
    onOpenProfile: (String) -> Unit = {},
    live: LivePlayback? = null,
    sourceMimeType: String? = null,
    requestHeaders: Map<String, String> = emptyMap(),
    playbackErrorMessage: String? = null,
    onPlaybackFailure: ((PlaybackException) -> Unit)? = null,
    onPlaybackStarted: (() -> Unit)? = null,
    onPlaybackSnapshot: ((MediaPlaybackSnapshot) -> Unit)? = null,
    onPlaybackLoadEvent: ((MediaLoadTrace) -> Unit)? = null,
    onViewerDismissReason: ((String) -> Unit)? = null,
    diagnosticReport: (() -> String)? = null,
    stopPlayback: Boolean = false,
    dataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
    playbackKey: Any? = null,
    validatedChannelUrl: String? = null,
    reelsOnly: Boolean = false,
    youtubeShortContent: (@Composable (String, Int, Boolean, (Boolean) -> Unit, (Float, Float) -> Unit) -> Unit)? = null,
    nextReelsCursor: String? = null,
    onLoadMoreReels: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isVideo = kind == "video"
    // Reels are edge-to-edge; channels and ordinary media viewers keep their original FIT layout.
    val reelsMode = reelsOnly && live == null
    // عارض Media3 للملفات المرفوعة فقط. YouTube لا يدخل مسار العرض الرأسي.
    val items = remember(posts, kind, validatedChannelUrl, reelsOnly) {
        if (reelsOnly && isVideo && validatedChannelUrl == null) uploadedPostReels(posts)
        else posts.filter { (validatedChannelUrl ?: media(it.optString(kind))).isNotBlank() }
            .distinctBy { it.optString("id") }
            .map { AttaReelEntry(it, it.optString("id"), validatedChannelUrl ?: media(it.optString(kind))) }
    }
    if (items.isEmpty()) return
    var index by remember(startId, kind, reelsOnly) {
        mutableIntStateOf(items.indexOfFirst { it.post.optString("id") == startId }.coerceAtLeast(0))
    }
    // عند اقتراب نهاية الريلز، استكمل منشورات الخادم الأقدم بدل إظهار قائمة فارغة.
    LaunchedEffect(reelsOnly, index, items.size, nextReelsCursor) {
        if (reelsOnly && nextReelsCursor != null && index >= items.size - 2) {
            onLoadMoreReels?.invoke()
        }
    }
    val reel = items[index.coerceIn(items.indices)]
    val current = reel.post
    val id = current.optString("id")
    val url = reel.videoUrl
    val youtubeId = reel.youtubeId
    val nativeVideo = isVideo && youtubeId == null
    val stateKey = playbackKey ?: reel.key
    var youtubePlaying by remember(stateKey) { mutableStateOf(false) }
    var youtubeStarted by remember(stateKey) { mutableStateOf(false) }
    var youtubeCommand by remember(stateKey) { mutableIntStateOf(0) }
    var youtubeCommandPlay by remember(stateKey) { mutableStateOf(true) }
    var sheet by remember(stateKey) { mutableStateOf<String?>(null) }
    var controls by remember(stateKey) { mutableStateOf(true) }
    var playing by remember(playbackKey) { mutableStateOf(false) }
    var buffering by remember(playbackKey) { mutableStateOf(isVideo) }
    var failed by remember(stateKey) { mutableStateOf("") }
    var progress by remember(stateKey) { mutableFloatStateOf(0f) }
    var duration by remember(stateKey) { mutableStateOf(0L) }
    var reactions by remember(stateKey) { mutableIntStateOf(postReactionCount(current)) }
    var comments by remember(stateKey) { mutableIntStateOf(current.optInt("commentCount", current.optJSONArray("comments")?.length() ?: 0)) }
    var selected by remember(stateKey) { mutableStateOf(current.optString("myReaction").ifBlank { if (current.optBoolean("likedByMe")) "like" else "" }) }
    var reacting by remember(stateKey) { mutableStateOf(false) }
    var videoHeights by remember(playbackKey) { mutableStateOf(emptyList<Int>()) }
    // أبعاد الفيديو الفعلية تمنع تكبير الريلز الذي كان يقصّ جانبي الصورة.
    var videoAspectRatio by remember(playbackKey) { mutableFloatStateOf(9f / 16f) }
    var qualityCap by remember(stateKey) { mutableIntStateOf(0) }
    var qualityMenu by remember(stateKey) { mutableStateOf(false) }
    val currentPlaybackErrorMessage by rememberUpdatedState(playbackErrorMessage)
    val currentDiagnosticReport by rememberUpdatedState(diagnosticReport)
    var playbackEverStarted by remember(url, playbackKey) { mutableStateOf(false) }
    var sources by remember(nativeVideo, live != null, requestHeaders, dataSourceFactory, playbackKey) { mutableStateOf<androidx.media3.datasource.DataSource.Factory?>(null) }
    LaunchedEffect(nativeVideo, live != null, requestHeaders, dataSourceFactory, playbackKey) {
        if (nativeVideo) {
            onPlaybackSnapshot?.invoke(MediaPlaybackSnapshot("transport_initializing"))
            try {
                sources = withContext(Dispatchers.IO) { dataSourceFactory ?: VideoTransport.factory(context, live != null, requestHeaders) }
                onPlaybackSnapshot?.invoke(MediaPlaybackSnapshot("transport_ready"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                failed = "تعذر تجهيز اتصال الفيديو."; controls = true
                onPlaybackFailure?.invoke(PlaybackException("Transport setup failed", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            }
        }
    }
    // خلفية هادئة من إطار معاينة الفيديو نفسه (مخزّن مسبقًا إن وُجد).
    // ليست مشغّل فيديو إضافيًا ولا تغيّر سرعة أو نسبة عرض الفيديو الأصلي.
    val reelBackdrop by produceState<Bitmap?>(initialValue = null, url, reelsOnly) {
        value = if (reelsOnly && url.isNotBlank())
            runCatching { VideoPostPreview.remote(context, url) }.getOrNull()
        else null
    }
    val player = remember(nativeVideo, sources, playbackKey) {
        if (nativeVideo && sources != null) ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(requireNotNull(sources)))
            .setLoadControl(VideoTransport.loadControl(live != null)).build() else null
    }
    val updateStats: (String, JSONObject) -> Unit = { postId, data ->
        if (postId == id) {
            reactions = data.optInt("reactionCount", reactions)
            comments = data.optInt("commentCount", comments)
            selected = data.optString("myReaction", selected)
        }
        onStats(postId, data)
    }
    DisposableEffect(player, playbackKey) {
        val snapshotCallback = onPlaybackSnapshot
        val startedCallback = onPlaybackStarted
        val failureCallback = onPlaybackFailure
        val loadCallback = onPlaybackLoadEvent
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                snapshotCallback?.invoke(mediaPlaybackSnapshot("player_event", player))
            }
            override fun onIsPlayingChanged(value: Boolean) {
                playing = value
                if (value) failed = ""
                if (value && !playbackEverStarted) { playbackEverStarted = true; startedCallback?.invoke() }
            }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = (videoSize.width.toFloat() *
                        videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()).coerceIn(.35f, 2.5f)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                failed = currentPlaybackErrorMessage ?: "تعذر تشغيل الفيديو. أعد المحاولة أو تحقق من الاتصال."; controls = true
                failureCallback?.invoke(error)
            }
            override fun onTracksChanged(tracks: Tracks) {
                videoHeights = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
                    (0 until group.length).filter { group.isTrackSupported(it) }.map { group.getTrackFormat(it).height }
                }.filter { it > 0 }.distinct().sorted()
            }
        }
        // تشخيص الشبكة فقط: لا تنسخ روابط البث أو رموز الاستعلام أو قيم الترويسات.
        val loadListener = object : AnalyticsListener {
            override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData) {
                loadCallback?.invoke(MediaLoadTrace("load_started", mediaLoadData.dataType, loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs,
                    requestHost = loadEventInfo.uri.host))
            }
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData) {
                loadCallback?.invoke(MediaLoadTrace("load_completed", mediaLoadData.dataType, loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs,
                    requestHost = loadEventInfo.uri.host))
            }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
                loadCallback?.invoke(MediaLoadTrace("load_error", mediaLoadData.dataType,
                    loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs, wasCanceled, safeMediaFailure(error), loadEventInfo.uri.host))
            }
        }
        player?.addListener(listener)
        player?.addAnalyticsListener(loadListener)
        player?.let { snapshotCallback?.invoke(mediaPlaybackSnapshot("player_created", it)) }
        val activity = context.mediaActivity()
        val lifecycleListener = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player?.pause() }
        activity?.lifecycle?.addObserver(lifecycleListener)
        onDispose { activity?.lifecycle?.removeObserver(lifecycleListener); player?.removeListener(listener); player?.removeAnalyticsListener(loadListener); player?.release() }
    }
    // يبدأ التشغيل عند فتح مشغّل Media3 الوحيد بملء الشاشة؛ وتبدّل
    // حركات السحب الرأسية المقاطع من دون إنشاء مشغّل فيديو ثانٍ.
    var continuePlayback by remember(isVideo, playbackKey) { mutableStateOf(isVideo) }
    LaunchedEffect(player, stopPlayback) {
        if (stopPlayback) { player?.stop(); failed = currentPlaybackErrorMessage ?: "انتهت محاولة التشغيل."; controls = true }
    }
    LaunchedEffect(player, reel.key, url, live?.mimeType, sourceMimeType, playbackKey) {
        videoHeights = emptyList()
        videoAspectRatio = 9f / 16f
        player?.takeUnless { stopPlayback }?.apply {
            val item = MediaItem.Builder().setUri(url).apply { (sourceMimeType ?: live?.mimeType)?.takeIf { it.isNotBlank() }?.let { setMimeType(it) } }.build()
            pause(); clearMediaItems(); setMediaItem(item); prepare()
            playWhenReady = continuePlayback
            if (continuePlayback) play()
            continuePlayback = false
            onPlaybackSnapshot?.invoke(mediaPlaybackSnapshot("prepared", this))
        }
        if (loadInteractions != null) {
            try { updateStats(id, loadInteractions(id, "reactions", 0)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Reading media remains available during an API outage. */ }
        }
    }
    LaunchedEffect(player, reel.key, qualityCap) {
        if (live != null && player != null) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, if (qualityCap == 0) Int.MAX_VALUE else qualityCap).build()
    }
    LaunchedEffect(player, reel.key) {
        while (player != null) {
            duration = player.duration.coerceAtLeast(0)
            progress = if (duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            delay(350)
        }
    }
    LaunchedEffect(playing, controls, sheet, qualityMenu, reelsOnly, stateKey) {
        if (controls && sheet == null && !qualityMenu && (reelsOnly || playing)) {
            delay(if (reelsOnly) 1500L else 2500L)
            controls = false
        }
    }
    // زر وسط الفيديو بدل التشغيل/الإيقاف المكرر في أسفل الريلز.
    val toggleReelPlayback: () -> Unit = {
        if (youtubeId != null) {
            youtubeCommandPlay = !youtubePlaying
            youtubeCommand += 1
        } else if (player != null && !stopPlayback) {
            if (playing) player.pause() else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                if (player.playerError != null) { failed = ""; player.prepare() }
                player.play()
            }
        }
        controls = true
    }
    val changeVideo: (Int) -> Unit = { delta ->
        val next = index + delta
        if (next in items.indices) {
            continuePlayback = player?.playWhenReady == true
            player?.pause()
            index = next
            failed = ""
            controls = true
        }
    }
    // رصد سحب الشورت داخل WebView الأصلي؛ لا نعطّل نقر زر تشغيل YouTube الرسمي.
    val shortSwipeThreshold = with(LocalDensity.current) { 88.dp.toPx() }
    var shortDragX by remember(stateKey) { mutableFloatStateOf(0f) }
    var shortDragY by remember(stateKey) { mutableFloatStateOf(0f) }
    var shortDragHandled by remember(stateKey) { mutableStateOf(false) }
    val onShortDrag: (Float, Float) -> Unit = { dx, dy ->
        if (!shortDragHandled) {
            shortDragX += dx
            shortDragY += dy
            if (abs(shortDragY) >= shortSwipeThreshold &&
                abs(shortDragY) > abs(shortDragX) * 1.5f) {
                shortDragHandled = true
                changeVideo(if (shortDragY < 0f) 1 else -1)
            }
        }
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
    fun dismissViewer(reason: String) { onViewerDismissReason?.invoke(reason); onDismiss() }
    Dialog(onDismissRequest = { if (sheet != null) sheet = null else dismissViewer("dialog_dismiss") },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnClickOutside = live == null)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                WindowCompat.getInsetsController(it, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
                it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        BackHandler { if (sheet != null) sheet = null else dismissViewer("system_back") }
        // يشغل الفيديو كامل المساحة الآمنة، وتظهر التفاعلات الاجتماعية في شريط واحد.
        Column(Modifier.fillMaxSize().background(Color.Black)
            .then(if (reelsMode) Modifier else Modifier.safeDrawingPadding())) {
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .pointerInput(reel.key) {
                    if (reelsMode && youtubeId == null) {
                        var drag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragCancel = { drag = 0f },
                            onDragEnd = { if (abs(drag) > 80.dp.toPx()) changeVideo(if (drag < 0) 1 else -1) },
                            onVerticalDrag = { change, amount -> change.consume(); drag += amount }
                        )
                    }
                }) {
                if (reelsOnly) {
                    // خلفية ثابتة فقط، لا تستخدم مشغّلًا آخر لتعبئة الشاشة.
                    if (youtubeId != null) {
                        coil.compose.AsyncImage(model = "https://i.ytimg.com/vi/$youtubeId/hqdefault.jpg",
                            contentDescription = null, modifier = Modifier.fillMaxSize().blur(30.dp),
                            contentScale = ContentScale.Crop)
                    } else reelBackdrop?.let { frame ->
                        Image(frame.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxSize().blur(30.dp),
                            contentScale = ContentScale.Crop)
                    }
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0x99000000),
                            Color(0xB8000000), Color(0xD5000000)))))
                }
                if (player != null) {
                    if (reelsOnly) {
                        // وضع الفيديو نفسه في منتصف المساحة مع الحفاظ على نسبة الطول والعرض.
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val displayHeight = (maxWidth / videoAspectRatio.coerceAtLeast(.35f))
                                .coerceAtMost(maxHeight)
                            AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                                this.player = player
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            } }, update = {
                                it.player = player
                                it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }, modifier = Modifier.align(Alignment.Center)
                                .fillMaxWidth().height(displayHeight))
                        }
                    } else {
                        // الفيديوهات العادية والبث المباشر لا تتغير أبعادها.
                        AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        } }, update = { it.player = player },
                            modifier = if (live != null) Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f)
                                else Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxSize().clickable {
                        if (reelsOnly) toggleReelPlayback() else controls = !controls
                    })
                } else if (youtubeId != null && youtubeShortContent != null && reelsOnly) {
                    // نفس YouTubePlayer الرسمي الذي يستخدمه التطبيق، لكن في مساحة 9:16.
                    // لا نحجب زر بدء YouTube الأصلي قبل التشغيل الأول.
                    key(reel.key) {
                        youtubeShortContent(youtubeId, youtubeCommand, youtubeCommandPlay,
                            { active ->
                                youtubePlaying = active
                                if (active) youtubeStarted = true
                            }, onShortDrag)
                    }
                    // YouTube يعرض زر التشغيل الأصلي في المنتصف؛ لا نضع فوقه زرًا ثانيًا.
                    // تبقى المساحة حرة لسحب الشورت ولمس أدوات YouTube المضمّنة.
                } else if (!isVideo) AttaZoomableImage(url, "صورة المنشور", onTap = { controls = !controls })
                if (reelsOnly && controls && player != null) {
                    Box(Modifier.align(Alignment.Center).size(66.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xA5000000))
                        .border(1.2.dp, PremiumGoldLight, RoundedCornerShape(50))
                        .clickable { toggleReelPlayback() }, contentAlignment = Alignment.Center) {
                        SiteIcon(if (if (youtubeId != null) youtubePlaying else playing) "pause" else "play",
                            size = 30.dp, tint = Color.White)
                    }
                }
                if (live != null && controls) Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color(0xBB000000)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(live.title, color = Color(0xFFF6D776), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(live.provider, color = Color.White, fontSize = 12.sp)
                    }
                    IconButton(onClick = { dismissViewer("close_button") }) { SiteIcon("x", tint = Color.White, size = 22.dp) }
                }
                if (reelsMode && live == null) {
                    val authorInfo = current.optJSONObject("authorInfo")
                    val authorName = authorInfo?.optString("fullName").orEmpty()
                        .ifBlank { authorInfo?.optString("displayName").orEmpty() }
                        .ifBlank { current.optString("author") }
                    val caption = visibleYoutubePostText(current.optString("text")).trim()
                    // الاسم والوصف فوق منطقة الإيماءات وشريط تشغيل الفيديو؛ لا يختفيان أسفل الشاشة.
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(.77f)
                        .padding(start = 16.dp,
                            bottom = if (youtubeId != null) 94.dp
                                else if (reelsOnly) (if (controls && player != null) 122.dp else 60.dp)
                                else 56.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(authorName, color = Color.White, fontWeight = FontWeight.Bold,
                            fontSize = 19.sp, lineHeight = 24.sp,
                            modifier = Modifier.background(Color(0x77000000), RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp))
                        if (caption.isNotBlank()) Text(caption, color = Color.White, fontSize = 15.sp,
                            lineHeight = 20.sp,
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    // Reels have swipe navigation; only the close control stays in the upper corner.
                    IconButton(onClick = { dismissViewer("close_button") },
                        modifier = Modifier.align(AbsoluteAlignment.TopLeft).padding(8.dp)
                            .background(Color(0x77000000), RoundedCornerShape(50))) {
                        SiteIcon("x", tint = Color.White, size = 23.dp)
                    }
                }
                // ثبّت الشريط الاجتماعي الرأسي عند الحافة اليمنى الفعلية في تخطيطي RTL وLTR.
                if ((player != null || youtubeId != null) && (sendReaction != null || onShare != null)) {
                    // أبعد أدوات العطا عن عنوان وأزرار YouTube الأصلية أعلى الشورت.
                    Column(Modifier.align(AbsoluteAlignment.TopRight)
                        .absolutePadding(top = if (youtubeId != null) 122.dp else 12.dp,
                            right = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (sendReaction != null && loadInteractions != null) {
                            PremiumReactionAction(reactions, selected == "like", selected,
                                onLike = { react("like") }, onReact = react,
                                onShowPeople = { sheet = "reactions" }, vertical = true)
                        }
                        if (loadInteractions != null && sendComment != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { sheet = "comments" }) {
                                Box(Modifier.size(44.dp).clip(RoundedCornerShape(50))
                                    .background(Color(0xAA080808))
                                    .border(1.dp, PremiumGold, RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center) {
                                    SiteIcon("message-circle", size = 23.dp, tint = PremiumGoldLight)
                                }
                                Text(comments.toString(), color = PremiumGoldLight, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (onShare != null) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(50))
                                .background(Color(0xAA080808))
                                .border(1.dp, PremiumGold, RoundedCornerShape(50))
                                .clickable {
                                    scope.launch {
                                        try { onShare(id) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (e: Exception) {
                                            failed = e.message ?: "تعذر مشاركة المنشور"; controls = true
                                        }
                                    }
                                }, contentAlignment = Alignment.Center) {
                                SiteIcon("send", size = 24.dp, tint = PremiumGoldLight)
                            }
                        }
                    }
                }
                // تختفي عناصر التحكم في التشغيل تلقائيًا؛ ويبقى الشريط الاجتماعي الرأسي ظاهرًا.
                if ((player != null || diagnosticReport != null) && controls) Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(bottom = if (live != null) 20.dp else if (reelsOnly) 14.dp else 72.dp) // Reels controls overlay the video rather than reserving a black floor.
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                        .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (failed.isNotBlank()) {
                        Text(playbackErrorMessage ?: failed, color = Color(0xFFFFB5B5), fontSize = 13.sp)
                        if (player != null && player.playerError != null && !stopPlayback) TextButton(onClick = {
                            failed = ""; player.prepare(); player.play()
                        }) { Text("إعادة المحاولة", color = Color(0xFFE6B94E)) }
                    }
                    if (diagnosticReport != null) TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("تقرير تشغيل القناة", currentDiagnosticReport?.invoke().orEmpty()))
                        android.widget.Toast.makeText(context, "تم نسخ تقرير التشغيل", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("نسخ نتيجة التجربة", color = Color(0xFFE6B94E)) }
                    if (player != null && duration > 0 && live == null) {
                        // خط بحث رفيع واحد. احتفظ بمساحة لمس شفافة مريحة بارتفاع 24dp،
                        // مع رسم مسار بارتفاع 3dp ومؤشر ذهبي صغير فقط.
                        Canvas(Modifier.fillMaxWidth().height(24.dp)
                            .pointerInput(player, duration) {
                                detectTapGestures { position ->
                                    val fraction = (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    progress = fraction
                                    player.seekTo((duration * fraction).toLong())
                                }
                            }
                            .pointerInput(player, duration) {
                                detectDragGestures(
                                    onDragStart = { position ->
                                        val fraction = (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        progress = fraction
                                        player.seekTo((duration * fraction).toLong())
                                    },
                                    onDrag = { change, _ ->
                                        val fraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        progress = fraction
                                        player.seekTo((duration * fraction).toLong())
                                        change.consume()
                                    }
                                )
                            }
                        ) {
                            val y = size.height / 2f
                            val filled = size.width * progress.coerceIn(0f, 1f)
                            drawLine(Color(0xFF60636A), Offset(0f, y), Offset(size.width, y),
                                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                            if (filled > 0f) drawLine(Color(0xFFE6B94E), Offset(0f, y),
                                Offset(filled, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                            drawCircle(Color(0xFFF6D776), radius = 4.dp.toPx(),
                                center = Offset(filled, y))
                        }
                        val currentSeconds = player.currentPosition.coerceAtLeast(0) / 1000
                        val totalSeconds = duration / 1000
                        Text("%02d:%02d / %02d:%02d".format(currentSeconds / 60,
                            currentSeconds % 60, totalSeconds / 60, totalSeconds % 60),
                            color = Color.White, fontSize = 11.sp,
                            fontWeight = FontWeight.Light, lineHeight = 13.sp)
                    }
                    if (player != null && !reelsOnly) Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        if (live != null) TextButton(enabled = !stopPlayback, onClick = { player.seekToDefaultPosition(); player.play() }) {
                            Text("للمباشر", color = Color(0xFFF6D776), fontSize = 12.sp)
                        } else if (!reelsOnly) IconButton(onClick = {
                            changeVideo(-1)
                        }) { SiteIcon("skip-back", size = 20.dp, tint = Color.White) }
                        IconButton(enabled = !stopPlayback, onClick = {
                            if (playing) player.pause() else {
                                if (player.playbackState == Player.STATE_ENDED) {
                                    if (live != null) player.seekToDefaultPosition() else player.seekTo(0)
                                }
                                if (player.playerError != null) { failed = ""; player.prepare() }
                                player.play()
                            }
                        }, modifier = Modifier.size(48.dp)
                            .border(1.dp, Color(0xFFE6B94E), RoundedCornerShape(50))) {
                            if (buffering) CircularProgressIndicator(color = Color(0xFFE6B94E),
                                modifier = Modifier.size(23.dp))
                            else SiteIcon(if (playing) "pause" else "play", size = 26.dp,
                                tint = Color.White)
                        }
                        if (live != null) Box {
                            TextButton(onClick = { qualityMenu = true }, enabled = videoHeights.isNotEmpty()) {
                                Text(if (qualityCap == 0) "الجودة: تلقائي" else "حتى ${qualityCap}p", color = Color(0xFFF6D776), fontSize = 12.sp)
                            }
                            DropdownMenu(qualityMenu, onDismissRequest = { qualityMenu = false }) {
                                DropdownMenuItem(text = { Text("تلقائي حسب الاتصال") }, onClick = { qualityCap = 0; qualityMenu = false })
                                videoHeights.forEach { height -> DropdownMenuItem(text = { Text("حتى ${height}p") }, onClick = { qualityCap = height; qualityMenu = false }) }
                            }
                        } else if (!reelsOnly) IconButton(onClick = {
                            changeVideo(1)
                        }) { SiteIcon("skip-forward", size = 20.dp, tint = Color.White) }
                    }
                }
            }
        }
        if (sheet != null && loadInteractions != null && sendComment != null) key(id, sheet) {
            PostInteractionsSheet(id, sheet!!, loadInteractions, sendComment, updateStats,
                onOpenProfile = { username -> onDismiss(); onOpenProfile(username) }, onDismiss = { sheet = null })
        }
    }
}

/** معالجات التفاعل الحقيقية اختيارية؛ لا تختلق تفاعلات للمنشور داخل مشغّل الوسائط. */
internal typealias PostInteractionsLoader = suspend (postId: String, category: String, offset: Int) -> JSONObject
internal typealias PostReactionSender = suspend (postId: String, reaction: String, remove: Boolean) -> Unit
internal typealias PostCommentSender = suspend (postId: String, message: String) -> JSONObject

private fun postReactionCount(post: JSONObject): Int =
    post.optInt("reactionCount", post.optInt("likesCount", post.optJSONArray("reactions")?.length() ?: 0))

/** أعد استخدام محمّل الصور الموجود لدعم التكبير بالقرص من دون مشغّل مكرر. */
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

/** اعرض التعليقات والتفاعلات فقط عند توفر معالجات خادم عاملة. */
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(postId, tab) {
        try { payload = loadInteractions(postId, tab, 0) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = "تعذّر تحميل البيانات" }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = Color(0xFF12161A)) {
        // قِس قائمة التمرير بعد حجز مساحة العنوان ومربع الكتابة؛
        // تقلّص لوحة المفاتيح القائمة بدلًا من دفع زر الإرسال خارج الشاشة.
        val bottomClearance = attaCommentBottomClearance()
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp).padding(bottom = bottomClearance)) {
            Text(if (tab == "comments") "التعليقات" else "التفاعلات", color = Color(0xFFE6B94E))
            failure?.let { Text(it, color = Color(0xFFFFB5B5)) }
            val records = payload?.optJSONArray("items")
                ?: payload?.optJSONArray(if (tab == "comments") "comments" else "reactions")
                ?: payload?.optJSONArray("users")
            if (records == null && failure == null) CircularProgressIndicator(color = Color(0xFFE6B94E))
            if (records != null) LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
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
            } else Spacer(Modifier.weight(1f))
            if (tab == "comments") Row(Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = content, onValueChange = { content = it },
                    label = { Text("اكتب تعليقًا", color = Color.LightGray) },
                    modifier = Modifier.weight(1f), maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = PremiumGold, focusedBorderColor = PremiumGold))
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
                }, enabled = content.isNotBlank() && !sending,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Text("إرسال", color = Color(0xFFE6B94E))
                }
            }
        }
    }
}
