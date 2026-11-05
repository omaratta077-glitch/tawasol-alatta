package com.alatta.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import kotlinx.coroutines.delay

/**
 * Legacy ExoPlayer 2 is intentionally separate from the Media3 instances used
 * by reels, user uploads and calls. TV playback uses this engine exclusively.
 *
 * A channel still needs a legitimate playable source (HLS/DASH/MP4); this does
 * not attempt to copy, patch, or run third-party YTV APK code or native modules.
 */
@Suppress("DEPRECATION")
@Composable
internal fun SportsInlinePlayer(session: NativePlaybackSession, attemptTimeoutMs: Long = 20_000) {
    val context = LocalContext.current
    val token = session.attemptToken
    val source = session.source
    var playing by remember(session.id, token) { mutableStateOf(false) }
    val player = remember(session.id, token, context) {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(source.headers["User-Agent"] ?: NativeChannelPlayback.FALLBACK_USER_AGENT)
            .setDefaultRequestProperties(source.headers)
        val transport = DefaultDataSource.Factory(context.applicationContext, http)
        ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(transport))
            .build()
    }
    DisposableEffect(player, session, token) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                session.onSnapshot(token, MediaPlaybackSnapshot(
                    "legacy_player_event", player.playbackState, player.playWhenReady,
                    player.isLoading, player.isPlaying, player.playbackSuppressionReason,
                    player.videoSize.width, player.videoSize.height))
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) session.onStarted(token)
            }
            override fun onPlayerError(error: PlaybackException) {
                // Session diagnostics/failover are shared with the existing TV API.
                session.fail(token, androidx.media3.common.PlaybackException(
                    error.message ?: "Legacy player error", error, error.errorCode))
            }
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
            session.fail(token, androidx.media3.common.PlaybackException(
                "Legacy playback startup timeout", null,
                androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT))
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
