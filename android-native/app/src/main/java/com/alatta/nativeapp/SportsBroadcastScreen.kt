package com.alatta.nativeapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/** AL-ATTA displays the selected live stream inline; AL-ATTA TV owns the channel list. */
@Composable
internal fun SportsBroadcastScreen(
    session: NativePlaybackSession?,
    loading: Boolean,
    error: String,
    isAdmin: Boolean,
    broadcastExpanded: Boolean,
    onBroadcast: () -> Unit,
    onManage: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    extraContent: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(PremiumBackground).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("البث المباشر", color = PremiumGoldLight, fontSize = 20.sp,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onReload, enabled = !loading) {
                Text("تحديث", color = PremiumGoldLight)
            }
            if (isAdmin) TextButton(onClick = onManage) {
                Text("إدارة", color = PremiumGoldLight)
            }
        }
        Surface(color = Color.Black, modifier = Modifier.fillMaxWidth()) {
            if (session == null) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center) {
                    Text("اختر قناة من AL-ATTA TV لعرض البث هنا",
                        color = PremiumGoldLight, fontSize = 14.sp)
                }
            } else {
                Column {
                    SportsInlinePlayer(session)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(session.channel.name, color = PremiumGoldLight,
                            modifier = Modifier.weight(1f), fontSize = 14.sp)
                        TextButton(onClick = onStop) { Text("إيقاف", color = PremiumGoldLight) }
                    }
                    if (session.terminal) {
                        Text(session.lastError ?: "تعذر تشغيل البث.", color = Color(0xFFFFB4A9),
                            modifier = Modifier.padding(horizontal = 10.dp))
                        TextButton(onClick = onRetry) { Text("إعادة المحاولة", color = PremiumGoldLight) }
                    }
                }
            }
        }
        Button(onClick = {
            // Explicit launcher: only a channel ID returns in the AL-ATTA TV handoff.
            try {
                onStop()
                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName("com.alatta.tv", "com.alatta.tv.LauncherActivity")
                    addCategory(Intent.CATEGORY_LAUNCHER)
                })
            } catch (_: Exception) {
                Toast.makeText(context, "ثبّت AL-ATTA TV لعرض قائمة القنوات.", Toast.LENGTH_LONG).show()
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("اختيار قناة من AL-ATTA TV")
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PremiumGold)
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFB4A9), fontSize = 13.sp)
        OutlinedButton(onClick = onBroadcast, modifier = Modifier.fillMaxWidth()) {
            Text(if (broadcastExpanded) "إخفاء أدوات بث الشاشة" else "إظهار أدوات بث الشاشة",
                color = PremiumGoldLight)
        }
        extraContent()
    }
}

/** One Media3 player for the active channel; failover keeps the existing session/reporting model. */
@Composable
internal fun SportsInlinePlayer(session: NativePlaybackSession, attemptTimeoutMs: Long = 20_000) {
    val context = LocalContext.current
    val token = session.attemptToken
    val source = session.source
    var playing by remember(session.id, token) { mutableStateOf(false) }
    val player = remember(session.id, token, context) {
        val transport = NativeChannelPlayback.transport(context, source.headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(transport))
            .setLoadControl(VideoTransport.loadControl(true))
            .build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                session.onSnapshot(token, MediaPlaybackSnapshot(
                    "player_event", player.playbackState, player.playWhenReady,
                    player.isLoading, player.isPlaying, player.playbackSuppressionReason,
                    player.videoSize.width, player.videoSize.height))
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) session.onStarted(token)
            }
            override fun onPlayerError(error: PlaybackException) { session.fail(token, error) }
        }
        player.addListener(listener)
        if (!session.terminal) {
            player.setMediaItem(MediaItem.Builder().setUri(source.url)
                .setMimeType(source.mimeType).build())
            player.prepare()
            player.playWhenReady = true
        }
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(session.id, token) {
        delay((attemptTimeoutMs - session.attemptElapsedMs()).coerceAtLeast(0))
        if (!session.started && !session.terminal) {
            session.fail(token, PlaybackException("Startup timeout", null,
                PlaybackException.ERROR_CODE_TIMEOUT))
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
        contentAlignment = Alignment.Center) {
        AndroidView(factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
        if (!playing && !session.terminal) {
            CircularProgressIndicator(color = PremiumGoldLight,
                modifier = Modifier.size(32.dp).align(Alignment.Center))
        }
    }
}
