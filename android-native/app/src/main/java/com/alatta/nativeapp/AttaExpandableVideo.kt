package com.alatta.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.max
import kotlin.math.min

/** One Media3 player per visible video: fitting, cropping, portrait preview, and fullscreen. */
@Composable
internal fun AttaExpandableVideo(url: String) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val playableUrl = remember(url) { media(url) }
    if (playableUrl.isBlank() || playableUrl.startsWith("data:")) {
        Text("تعذر تشغيل هذا الفيديو", color = Color(0xFFE6B94E))
        return
    }
    val player = remember(playableUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(playableUrl))
            prepare()
            playWhenReady = false
        }
    }
    var fullscreen by remember(playableUrl) { mutableStateOf(false) }
    var zoom by remember(playableUrl) { mutableStateOf(false) }
    var videoRatio by remember(playableUrl) { mutableFloatStateOf(16f / 9f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    val ratio = size.width.toFloat() / size.height * size.pixelWidthHeightRatio
                    if (ratio > 0.01f) videoRatio = ratio
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val previewHeight = min(config.screenHeightDp * 0.72f,
        max(230f, (config.screenWidthDp - 28f) / videoRatio)).dp

    if (!fullscreen) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color.Black)) {
            AndroidView(factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }, modifier = Modifier.fillMaxWidth().height(previewHeight),
                update = { view ->
                    if (view.player !== player) view.player = player
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                })
            Row(Modifier.fillMaxWidth().background(Color(0xFF13191D)).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("مشغّل الفيديو", fontSize = 12.sp, color = Color(0xFFD9C28F))
                TextButton(onClick = { fullscreen = true }) {
                    Text("⛶  ملء الشاشة", color = Color(0xFFE6B94E), fontSize = 13.sp)
                }
            }
        }
    }
    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF101519)).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { fullscreen = false }) {
                        Text("✕ إغلاق", color = Color.White, fontSize = 15.sp)
                    }
                    Text("تواصل العطا · الفيديو", color = Color(0xFFE6B94E), fontSize = 14.sp)
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                    AndroidView(factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }, modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            if (view.player !== player) view.player = player
                            view.resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        })
                }
                Row(Modifier.fillMaxWidth().background(Color(0xFF101519)).padding(8.dp),
                    horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { zoom = false }) {
                        Text("احتواء كامل", color = if (!zoom) Color(0xFFE6B94E) else Color.White)
                    }
                    Spacer(Modifier.width(16.dp))
                    TextButton(onClick = { zoom = true }) {
                        Text("تكبير وملء", color = if (zoom) Color(0xFFE6B94E) else Color.White)
                    }
                }
            }
        }
    }
}
