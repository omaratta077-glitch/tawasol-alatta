package com.alatta.nativeapp

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Only expose fixed diagnostic categories from AL-ATTA's playback endpoint.
 * Never echo upstream URLs, tokens, response bodies, or exception messages.
 */
internal fun tvPlaybackFailureMessage(channel: FootballChannel, failure: AttaApiException): String {
    val detail = failure.message.orEmpty()
    val description = when {
        failure.statusCode == 401 -> "جلسة الدخول انتهت. افتح العطا وسجّل الدخول ثم أعد اختيار القناة."
        failure.statusCode == 429 -> "طلب تشغيل آخر ما زال قيد الحماية. انتظر ثانيتين قبل إعادة المحاولة."
        failure.statusCode == 404 -> "القناة لم تعد موجودة في قائمة العطا أو تم إيقافها. حدّث القنوات."
        "CLOUDFLARE_1042" in detail -> "مزود المصدر رفض اتصال خادم العطا (Cloudflare 1042). تغيير المشغّل لا يحل رفض المصدر."
        "HTTP 403" in detail || "HTTP 401" in detail -> "مصدر القناة رفض طلب بيانات التشغيل. يلزم مصدر مصرح به أو تكامل رسمي."
        "FRESH_ID_UNCHANGED" in detail -> "رقم القناة ما زال كما هو في المصدر، لكن طلب التشغيل يرجع 404."
        "CHANNEL_NOT_FOUND" in detail || "CATEGORY_NOT_FOUND" in detail ->
            "لم يستطع الخادم العثور على القناة أو قسمها الحالي في المصدر."
        "CHANNEL_AMBIGUOUS" in detail || "CATEGORY_AMBIGUOUS" in detail ->
            "وجد الخادم أكثر من قناة أو قسم بالاسم نفسه؛ أوقف الاختيار لتجنب بث قناة مختلفة."
        "HTTP 404" in detail -> "المصدر أعاد 404 حتى بعد فحص تحديث رقم القناة أو تعذر تحديد الرقم الحالي."
        "NO_SUPPORTED_NATIVE_SOURCE" in detail -> "وصلت بيانات التشغيل، لكن ليس بها رابط مباشر متوافق مع مشغّل العطا. قد يتطلب المصدر ترخيصًا أو إعدادات لا يملكها العطا."
        "SOURCE_TIMEOUT" in detail -> "انتهت مهلة اتصال خادم العطا بمصدر القناة."
        "SOURCE_NETWORK_ERROR" in detail -> "فشل اتصال خادم العطا بمصدر القناة، قبل وصول رابط تشغيل."
        "PLAYBACK_API_DECODE" in detail -> "وصل رد من المصدر، لكن الخادم لم يستطع قراءة بيانات تشغيل صالحة."
        "PROBE_KEY" in detail -> "إعداد قراءة المصدر غير مكتمل في خادم العطا. راجع إعدادات Cloudflare."
        else -> "تعذر جلب مصدر بث متوافق من الخادم؛ لا يمكن تحويل رقم القناة وحده إلى فيديو."
    }
    val safeCode = Regex("(CLOUDFLARE_\\d{4}|SOURCE_[A-Z_]+|NO_SUPPORTED_NATIVE_SOURCE|FRESH_ID_UNCHANGED|CHANNEL_[A-Z_]+|CATEGORY_[A-Z_]+|PLAYBACK_API_[A-Z_]+)")
        .find(detail)?.value
    val upstreamHttp = Regex("HTTP (\\d{3})").find(detail)?.groupValues?.getOrNull(1)
    val diagnostic = listOfNotNull("HTTP العطا ${failure.statusCode}",
        upstreamHttp?.let { "HTTP المصدر $it" }, safeCode).joinToString(" • ")
    return "القناة ${channel.name} (رقم ${channel.streamUrl}): $description\\nرمز التشخيص: $diagnostic"
}

/**
 * AL-ATTA Media is the TV playback receiver; AL-ATTA TV is the channel chooser.
 * Only the stable catalog ID crosses the app boundary. Neither an unsigned
 * stream URL nor the third-party YTV player is launched from this screen.
 *
 * Existing reels, post-video and chat players are untouched. This screen
 * uses the internal ExoPlayer2 player for authorized HLS/DASH sources only.
 */
@Composable
internal fun AttaTvScreen(
    token: String,
    modifier: Modifier = Modifier,
    requestedChannelId: String? = null,
    onRequestedChannelConsumed: () -> Unit = {},
    onBeforePlayback: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf(FootballCatalog()) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FootballChannel?>(null) }
    var session by remember { mutableStateOf<NativePlaybackSession?>(null) }
    var error by remember { mutableStateOf("") }
    var goldHomeVisible by rememberSaveable { mutableStateOf(true) }

    fun openChannelChooser() {
        // AL-ATTA TV remains a separate, signed catalog app, not a player.
        try {
            context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName("com.alatta.tv", "com.alatta.tv.LauncherActivity")
                addCategory(Intent.CATEGORY_LAUNCHER)
            })
        } catch (_: ActivityNotFoundException) {
            error = "تطبيق AL-ATTA TV الخاص باختيار القنوات غير مثبت. ثبّت تطبيق القنوات، وليس مشغّل YTV."
            goldHomeVisible = false
        } catch (_: SecurityException) {
            error = "تعذر فتح تطبيق اختيار القنوات من إعدادات الجهاز."
            goldHomeVisible = false
        }
    }

    LaunchedEffect(reload) {
        loading = true
        try {
            catalog = FootballRepository.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "تعذر تحديث معلومات القنوات من خادم العطا."
        } finally {
            loading = false
        }
    }

    fun playChannel(channel: FootballChannel) {
        if (busy) return
        busy = true
        selected = channel
        session?.onDismiss("next_channel")
        session = null
        error = ""
        goldHomeVisible = false
        scope.launch {
            try {
                val sources = when (channel.format) {
                    "hls", "dash" -> NativeChannelPlayback.fromPublishedChannel(channel)
                    "ytv" -> {
                        if (token.isBlank()) {
                            error = "القناة رقم " + channel.streamUrl +
                                " تحتاج مصدر بث مباشر من خادم العطا. رقم القناة وحده ليس رابط فيديو."
                            return@launch
                        }
                        NativeChannelPlayback.load(token, channel.id)
                    }
                    else -> {
                        error = "صيغة القناة غير مدعومة في مشغّل العطا TV الداخلي."
                        return@launch
                    }
                }
                onBeforePlayback()
                session = NativePlaybackSession(channel, sources, engineName = "ExoPlayer2")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AttaApiException) {
                error = if (channel.format == "ytv") tvPlaybackFailureMessage(channel, failure)
                else "لم يتوفر مصدر بث متوافق لهذه القناة (HTTP ${failure.statusCode})."
            } catch (_: Exception) {
                error = "تعذّر تجهيز بث القناة داخل العطا. تحقّق من مصدر البث وحاول مجددًا."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(requestedChannelId, catalog, loading, token) {
        val id = requestedChannelId ?: return@LaunchedEffect
        if (loading) return@LaunchedEffect
        val channel = catalog.channels.firstOrNull {
            it.id == id && it.enabled && it.format in setOf("ytv", "hls", "dash")
        }
        onRequestedChannelConsumed()
        goldHomeVisible = false
        if (channel == null) {
            error = "القناة المختارة غير موجودة أو لم تعد مفعلة في قائمة العطا TV."
        } else {
            playChannel(channel)
        }
    }

    // Leave the original gold landing page intact when opening this tab.
    if (goldHomeVisible) {
        AttaTvGoldHome(
            onOnlineVideos = { openChannelChooser() },
            onExit = onExit,
            modifier = modifier
        )
    } else {
        Column(
            modifier.fillMaxSize().background(PremiumBackground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    session?.onDismiss("gold_home")
                    session = null
                    selected = null
                    error = ""
                    goldHomeVisible = true
                }) { Text("الرئيسية", color = PremiumGoldLight) }
                Spacer(Modifier.width(5.dp))
                SiteIcon("monitor", tint = PremiumGoldLight, size = 27.dp)
                Spacer(Modifier.width(8.dp))
                Text("AL-ATTA TV", color = PremiumGoldLight,
                    fontSize = 21.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, PremiumBorder)
            ) {
                Column {
                    if (session == null) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (selected == null) "اختر قناة من AL-ATTA TV لعرضها هنا"
                                else "القناة: " + selected!!.name,
                                color = PremiumGoldLight,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("يتم تشغيل القناة داخل العطا بملء الشاشة", color = PremiumGoldLight)
                        }
                    }
                    selected?.let { channel ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(channel.name, color = PremiumGoldLight, fontSize = 14.sp,
                                modifier = Modifier.weight(1f))
                            if (session != null) {
                                TextButton(onClick = {
                                    session?.onDismiss("stop")
                                    session = null
                                }) { Text("إيقاف", color = PremiumGoldLight) }
                            }
                        }
                    }
                }
            }

            if (session?.terminal == true) {
                Text(session?.lastError ?: "تعذّر تشغيل رابط البث.",
                    color = Color(0xFFFFB4A9), fontSize = 13.sp)
            }
            if (loading || busy) LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(), color = PremiumGold)
            if (error.isNotBlank()) {
                Text(error, color = Color(0xFFFFB4A9), fontSize = 13.sp)
            }
            selected?.let { channel ->
                OutlinedButton(
                    onClick = { playChannel(channel) },
                    enabled = !busy && !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("إعادة محاولة تشغيل القناة", color = PremiumGoldLight) }
            }
            Button(
                onClick = { openChannelChooser() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("اختيار قناة من AL-ATTA TV") }
            Text(
                "اختيار القنوات والأرقام في AL-ATTA TV. تشغيل الروابط المباشرة داخل العطا Media.",
                color = PremiumMuted, fontSize = 12.sp
            )
        }
    }

    // TV video is displayed within AL-ATTA Media, covering the app chrome.
    // Closing the dialog disposes and releases the native player immediately.
    session?.let { playback ->
        Dialog(
            onDismissRequest = {
                playback.onDismiss("back")
                session = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                LegacyInlineStreamPlayer(playback, modifier = Modifier.fillMaxSize())
                TextButton(
                    onClick = {
                        playback.onDismiss("close")
                        session = null
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Text("إغلاق البث", color = PremiumGoldLight, fontSize = 16.sp)
                }
                if (playback.terminal) {
                    Column(
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth().background(Color(0xE6121212))
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(playback.lastError ?: "تعذر تشغيل مصدر القناة", color = Color(0xFFFFB4A9))
                        TextButton(onClick = { playChannel(playback.channel) }, enabled = !busy) {
                            Text("إعادة المحاولة بمصدر جديد", color = PremiumGoldLight)
                        }
                    }
                }
            }
        }
    }
}
