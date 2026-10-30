package com.alatta.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.net.Uri
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.DisposableEffect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.caverock.androidsvg.SVG
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** مصحف Android واحد دون اتصال، مرسوم على Canvas مع عناصر تحكم ذهبية للقراءة. */
@Composable
internal fun QuranReaderScreen(modifier: Modifier = Modifier, onExit: () -> Unit,
    initialPage: Int = 1, readingRange: IntRange? = null,
    onPageChange: (Int) -> Unit = {}, onReadAll: () -> Unit = {},
    onSharePost: (MushafPostDraft) -> Unit = {}, onStartAudio: () -> Unit = {},
    allowOnlineFeatures: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("atta_native_mushaf", Context.MODE_PRIVATE) }
    var page by remember { mutableIntStateOf(initialPage.coerceIn(1,604)) }
    val scope = rememberCoroutineScope()
    var preparingShare by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var savedPages by remember { mutableStateOf(setOf(1)) }
    var shareFrom by remember { mutableStateOf(initialPage.toString()) }
    var shareTo by remember { mutableStateOf(initialPage.toString()) }
    var sharePart by remember { mutableStateOf("1") }
    LaunchedEffect(initialPage) { page = initialPage.coerceIn(1,604) }
    var pane by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var indexTab by remember { mutableStateOf("surah") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pageRetry by remember { mutableIntStateOf(0) }
    var selectedAyah by remember { mutableIntStateOf(0) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var playbackStatus by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var repeatRange by remember { mutableStateOf(false) }
    var rangeStart by remember { mutableIntStateOf(0) }
    var rangeEnd by remember { mutableIntStateOf(0) }
    var currentAyah by remember { mutableIntStateOf(0) }
    var selectedReciter by remember { mutableStateOf(QURAN_RECITERS[0]) }
    var rangeMode by remember { mutableStateOf("page") }
    var customEnd by remember { mutableStateOf("") }
    val audio = remember { AyahSequencePlayer() }
    DisposableEffect(Unit) { onDispose { audio.stop() } }
    val cream = Color(0xFFFFF9E8)
    val gold = Color(0xFF884300)
    var indexData by remember { mutableStateOf<MushafIndex?>(null) }
    LaunchedEffect(Unit) {
        try { indexData = withContext(Dispatchers.IO) { MushafIndexStore.load(context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = "تعذر تحميل فهرس المصحف: ${failure.message}" }
    }
    val index = indexData?.pages
    val ayahDatabase = indexData?.ayahs
    val chapters = indexData?.chapters.orEmpty()
    val juzStarts = remember { MUSHAF_JUZ_STARTS }
    val juz = (juzStarts.indexOfLast { it <= page } + 1).coerceAtLeast(1)
    // يتكوّن كل حزب من أربعة أرباع. اقرأ صفحات بدايتها الفعلية من الفهرس المضمّن.
    val hizbQuarterStarts = indexData?.quarterStarts.orEmpty()
    val hizb = if (hizbQuarterStarts.size == 240) {
        (((hizbQuarterStarts.count { it <= page } - 1).coerceAtLeast(0)) / 4 + 1).coerceIn(1, 60)
    } else ((juz - 1) * 2 + 1).coerceIn(1, 60)
    val surah = chapters.lastOrNull { it.third <= page }?.second ?: "الفاتحة"
    val bookmark = prefs.getInt("bookmark", -1)
    // تُحسب الحدود من أرقام الآيات الفعلية في الفهرس غير المتصل، لا من تقديرات الصفحات.
    val allAyahs = ayahDatabase
    val startAudio: (Int, Int) -> Unit = startAudio@{ start, end ->
        if (!allowOnlineFeatures) { playbackError = "التلاوة المتصلة متاحة بعد الموافقة على سياسة الخصوصية"; return@startAudio }
        onStartAudio()
        rangeStart = start
        rangeEnd = end
        playbackStatus = "جارٍ تجهيز التلاوة..."
        audio.playRange(
            ayahs = allAyahs, start = start, end = end,
            folder = selectedReciter.second, repeat = { repeatRange },
            onUpdate = { number, playing, status ->
                currentAyah = number; isPlaying = playing
                isPaused = false; playbackStatus = status
            }
        )
    }
    val changePage: (Int) -> Unit = { n ->
        page = n.coerceIn(readingRange ?: (1..604)); prefs.edit().putInt("page",page).apply(); error = null
        onPageChange(page)
        pane = null
    }
    val prepareShare: (MushafShareSelection) -> Unit = { selection ->
        if (!preparingShare && allowOnlineFeatures) scope.launch {
            preparingShare = true; error = null
            try {
                val draft = withContext(Dispatchers.IO) { prepareMushafPost(context, selection) }
                onSharePost(draft)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "تعذّر تجهيز مشاركة المصحف" }
            finally { preparingShare = false }
        }
    }
    LaunchedEffect(pane) {
        if (pane == "downloads" && !downloading) {
            val found = withContext(Dispatchers.IO) {
                (1..604).filter { MushafPageStore.isDownloaded(context, it) }.toSet()
            }
            savedPages = savedPages + found
        }
    }
    fun downloadMushaf() {
        if (downloading || !allowOnlineFeatures) return
        downloading = true; error = null
        downloadJob = scope.launch {
            try {
                for (number in 2..604) {
                    ensureActive()
                    withContext(Dispatchers.IO) { MushafPageStore.downloadPage(context, number) }
                    savedPages = savedPages + number
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "تعذر تنزيل المصحف. يمكنك المحاولة لاحقًا لاستكمال الصفحات." }
            finally { downloading = false; downloadJob = null }
        }
    }
    BackHandler { if (pane != null) pane = null else onExit() }
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Box(modifier.fillMaxSize().background(cream)) {
    Column(Modifier.fillMaxSize()) {
        // عنوان عربي مدمج بعرض ثابت؛ لتجنب التفاف كلمة «رجوع» عموديًا.
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onExit, modifier = Modifier.width(44.dp), contentPadding = PaddingValues(0.dp)) {
                Text("‹", color = gold, fontSize = 27.sp, maxLines = 1)
            }
            TextButton(onClick = { pane = "index" }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) {
                Text(surah, color = gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { indexTab = "surah"; pane = "search" }, modifier = Modifier.width(42.dp), contentPadding = PaddingValues(0.dp)) {
                Text("⌕", color = gold, fontSize = 22.sp)
            }
            TextButton(onClick = { pane = "index" }, modifier = Modifier.width(42.dp), contentPadding = PaddingValues(0.dp)) {
                Text("☷", color = gold, fontSize = 22.sp)
            }
            Text("الجزء $juz", color = gold, fontSize = 14.sp, maxLines = 1)
            TextButton(onClick = { prefs.edit().putInt("bookmark",page).apply() },
                modifier = Modifier.width(40.dp), contentPadding = PaddingValues(0.dp)) {
                Text(if (bookmark == page) "◆" else "♧", color = gold, maxLines = 1)
            }
            IconButton(enabled = allowOnlineFeatures, onClick = { shareFrom = page.toString(); shareTo = page.toString(); sharePart = juz.toString(); pane = "share" },
                modifier = Modifier.size(36.dp)) { SiteIcon("share", tint = gold, size = 19.dp) }
        }
        if (readingRange != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("صفحات ${readingRange.first}–${readingRange.last}", color = gold, fontSize = 11.sp,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onReadAll) { Text("المصحف كاملًا", color = gold, fontSize = 11.sp) }
        }
        key(page, pageRetry) {
            AndroidView(factory = {
                MushafPageView(context, page, changePage, { chosen ->
                    selectedAyah = chosen
                    playbackError = null
                }, allowOnlineFeatures) { error = it }
            }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
        error?.let { message ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(8.dp), color=Color(0xFF933C20), fontSize=12.sp)
                TextButton(onClick = { error = null; pageRetry++ }) { Text("إعادة التحميل", color = gold) }
            }
        }
        // تذييل قراءة مدمج بلا ملاحظات أو أسهم تنقل أو عناصر تحكم ضخمة.
        // يظل محرر الملاحظات متاحًا من نافذة الفهرس.
        Row(
            Modifier.fillMaxWidth().height(20.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("صفحة $page", color = gold, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            Text("الحزب $hizb", color = gold, fontSize = 12.sp)
        }
    }
    if (pane != null) Dialog(onDismissRequest = { pane = null }) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp),
            shape = RoundedCornerShape(16.dp),
            color = cream,
            contentColor = Color(0xFF221B14)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(when(pane) {"index", "search", "parts" -> "فهرس المصحف الشريف"; "downloads" -> "تنزيل المصحف";
                        "marks" -> "المرجعيات"; "notes" -> "الملاحظات"; "share" -> "مشاركة المصحف"; else -> "البحث" },
                        color = Color(0xFF221B14), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { pane = null }) { Text("إغلاق", color = gold) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("surah" to "السور ١١٤", "juz" to "الأجزاء ٣٠", "quarter" to "أرباع الأحزاب ٢٤٠", "page" to "الصفحات ٦٠٤").forEach { (id, title) ->
                        val selected = pane in listOf("index", "search", "parts") && indexTab == id
                        OutlinedButton(onClick = { indexTab = id; pane = "index"; query = "" },
                            shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if(selected) gold else Color(0xFFFFF8E9),
                                contentColor = if(selected) Color.White else gold), modifier = Modifier.height(34.dp)) {
                            Text(title, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    listOf("marks" to "المرجعيات", "notes" to "الملاحظات", "downloads" to "التنزيل", "share" to "مشاركة")
                        .filter { allowOnlineFeatures || it.first != "share" }.forEach { (id, name) ->
                        TextButton(onClick = { pane = id }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                            Text(name, maxLines = 1, fontSize = 12.sp, color = gold)
                        }
                    }
                }
                when (pane) {
                    "downloads" -> {
                        Text("المحفوظ على الجهاز: ${savedPages.size} من 604 صفحة", color = gold, fontSize = 12.sp)
                        Text("تحتاج الصفحات إلى الإنترنت لأول مرة، ثم تبقى محفوظة للقراءة دون اتصال.", color = gold, fontSize = 11.sp)
                        if (allowOnlineFeatures && savedPages.size < 604) TextButton(onClick = {
                            if (downloading) downloadJob?.cancel() else downloadMushaf()
                        }) { Text(if (downloading) "إيقاف التنزيل" else "تنزيل المصحف كاملًا", color = gold) }
                        if (downloading) LinearProgressIndicator(progress = { savedPages.size / 604f }, modifier = Modifier.fillMaxWidth(), color = gold)
                    }
                    "index", "search", "parts" -> {
                        OutlinedTextField(value = query, onValueChange = { query = it },
                            placeholder = { Text("ابحث عن اسم السورة أو رقم الجزء...", fontSize = 14.sp) }, singleLine = true,
                            shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF221B14)),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = gold, unfocusedBorderColor = Color(0xFFD7B893)))
                        val entries = when(indexTab) {
                            "juz" -> (1..30).map { Triple(it, "الجزء ${mushafArabicNumber(it)}", juzStarts[it-1]) }
                            "quarter" -> hizbQuarterStarts.mapIndexed { i, p -> Triple(i+1, "الربع ${mushafArabicNumber(i+1)}", p) }
                            "page" -> (1..604).map { Triple(it, "صفحة ${mushafArabicNumber(it)}", it) }
                            else -> chapters
                        }.filter { query.isBlank() || mushafIndexSearch(it.second).contains(mushafIndexSearch(query)) || it.first.toString().contains(mushafIndexSearch(query)) }
                        if(entries.isEmpty()) Text("لا توجد نتائج مطابقة.", color = gold, modifier = Modifier.padding(12.dp))
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(entries, key = { it.first }) { (n, name, p) ->
                                val detail = when(indexTab) {
                                    "juz" -> "فتح بداية الجزء"
                                    "quarter" -> "الحزب ${mushafArabicNumber((n+3)/4)} — الجزء ${mushafArabicNumber((n+7)/8)}"
                                    "page" -> "من ٦٠٤ صفحة"
                                    else -> "${mushafArabicNumber(indexData?.json?.getJSONArray("surahs")?.getJSONObject(n-1)?.optInt("numberOfAyahs") ?: 0)} آية"
                                }
                                Row(Modifier.fillMaxWidth().clickable { changePage(p) }.padding(horizontal = 5.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if(indexTab == "surah") "${mushafArabicNumber(n)}  $name" else name,
                                        Modifier.weight(1f), color = Color(0xFF38210F), fontSize = 15.sp)
                                    Text(detail, color = Color(0xFF966328), fontSize = 12.sp)
                                }
                                HorizontalDivider(color = Color(0xFFE4D4BE))
                            }
                        }
                    }
                    "share" -> Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val inputColors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF221B14), unfocusedTextColor = Color(0xFF221B14),
                            cursorColor = gold, focusedBorderColor = gold,
                            focusedLabelColor = gold, unfocusedLabelColor = gold)
                        Text("معاينة بصورة الصفحة وملف كامل للقراءة، ثم مراجعة المنشور في الرئيسية.", color = gold, fontSize = 12.sp)
                        OutlinedButton(onClick = { prepareShare(MushafShareSelection(page,page,"صفحة $page من القرآن الكريم")) },
                            enabled = !preparingShare, modifier = Modifier.fillMaxWidth()) { Text("مشاركة الصفحة الحالية $page", color = gold) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(shareFrom, { shareFrom = it.filter(Char::isDigit).take(3) },
                                label = { Text("من صفحة", color = gold) }, modifier = Modifier.weight(1f), singleLine = true, colors = inputColors)
                            OutlinedTextField(shareTo, { shareTo = it.filter(Char::isDigit).take(3) },
                                label = { Text("إلى صفحة", color = gold) }, modifier = Modifier.weight(1f), singleLine = true, colors = inputColors)
                        }
                        OutlinedButton(onClick = {
                            try { prepareShare(MushafShareSelection(shareFrom.toInt(),shareTo.toInt(),"ورد من القرآن الكريم • صفحات $shareFrom–$shareTo")) }
                            catch (e: Exception) { error = e.message ?: "اختر نطاقًا صحيحًا" }
                        }, enabled = !preparingShare, modifier = Modifier.fillMaxWidth()) { Text("مشاركة الصفحات المختارة", color = gold) }
                        OutlinedTextField(sharePart, { sharePart = it.filter(Char::isDigit).take(2) },
                            label = { Text("رقم الجزء 1–30", color = gold) }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = inputColors)
                        OutlinedButton(onClick = {
                            try { prepareShare(mushafPartSelection(sharePart.toInt())) }
                            catch (e: Exception) { error = e.message ?: "اختر جزءًا من 1 إلى 30" }
                        }, enabled = !preparingShare, modifier = Modifier.fillMaxWidth()) { Text("مشاركة الجزء كاملًا", color = gold) }
                        Text("حتى 30 صفحة في المنشور الواحد. لن يُنشر شيء قبل ضغطك على نشر.", color = gold, fontSize = 11.sp)
                    }
                    "marks" -> {
                        val saved = prefs.getInt("bookmark", -1)
                        Text(if (saved > 0) "العلامة المحفوظة: صفحة $saved" else "لا توجد مرجعيات محفوظة", color = Color(0xFF221B14))
                        if (saved > 0) TextButton(onClick = { changePage(saved) }) { Text("الانتقال إلى العلامة", color = gold) }
                        TextButton(onClick = { prefs.edit().putInt("bookmark", page).apply(); pane = null }) { Text("حفظ الصفحة الحالية", color = gold) }
                    }
                    "notes" -> {
                        LaunchedEffect(page) { note = prefs.getString("note_$page", "") ?: "" }
                        Text("ملاحظات الصفحة $page", color = Color(0xFF221B14))
                        OutlinedTextField(value = note, onValueChange = { note = it },
                            modifier = Modifier.fillMaxWidth(), minLines = 3,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF221B14)))
                        TextButton(onClick = { prefs.edit().putString("note_$page", note).apply(); pane = null }) { Text("حفظ الملاحظة", color = gold) }
                    }
                }
                error?.let { Text(it, color = Color(0xFF933C20), fontSize = 12.sp) }
                if (preparingShare) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = gold)
                    Text("جارٍ تجهيز الصفحات...", color = gold, fontSize = 12.sp)
                }
            }
        }
    }
    if (selectedAyah > 0) {
        val info = ayahDatabase?.optJSONObject(selectedAyah)
        val surahNumber = info?.optInt("surah", 0) ?: 0
        val verseNumber = info?.optInt("numberInSurah", 0) ?: 0
        val verseText = info?.optString("text", "") ?: ""
        val inSurah = remember(selectedAyah, ayahDatabase) {
            (1 until (ayahDatabase?.length() ?: 0)).filter { ayahDatabase?.optJSONObject(it)?.optInt("surah") == surahNumber }
        }
        val surahEnd = inSurah.lastOrNull() ?: selectedAyah
        val pageNumbers = remember(page, index) {
            val arr = index?.optJSONObject(page)?.optJSONArray("ayahs")
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.optInt(it) }
        }
        val pageEnd = pageNumbers.filter { it >= selectedAyah }.lastOrNull() ?: selectedAyah
        var reciterMenu by remember { mutableStateOf(false) }
        // استخدم طبقة فوق الصفحة بدلًا من نافذة تنبيه بملء الشاشة.
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 9.dp, vertical = 26.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFFFFF9E8),
            tonalElevation = 5.dp,
            shadowElevation = 8.dp
        ) {
            Column(Modifier.padding(12.dp).heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("التلاوة من الآية $verseNumber", color = gold, fontSize = 17.sp)
                    IconButton(onClick = { selectedAyah = 0 }, modifier = Modifier.size(36.dp)) {
                        Text("✕", color = gold, fontSize = 20.sp)
                    }
                }
                Text(verseText, color = Color(0xFF261B11), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    OutlinedButton(onClick = { reciterMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("♫  ${selectedReciter.first}  ▾", color = gold, maxLines = 1)
                    }
                    DropdownMenu(expanded = reciterMenu, onDismissRequest = { reciterMenu = false }) {
                        QURAN_RECITERS.forEach { reciter ->
                            DropdownMenuItem(text = { Text(reciter.first) }, onClick = {
                                selectedReciter = reciter; reciterMenu = false
                                audio.stop(); isPlaying = false
                            })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("page" to "إلى آخر الصفحة", "surah" to "إلى آخر السورة", "custom" to "إلى آية محددة").forEach { (mode, label) ->
                        FilterChip(selected = rangeMode == mode, onClick = { rangeMode = mode }, label = {
                            Text(label, fontSize = 12.sp, maxLines = 1)
                        })
                    }
                }
                if (rangeMode == "custom") {
                    OutlinedTextField(value = customEnd,
                        onValueChange = { customEnd = it.filter(Char::isDigit).take(3) },
                        label = { Text("إلى الآية رقم ($verseNumber – ${inSurah.size})") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                val customGlobalEnd = inSurah.firstOrNull {
                    ayahDatabase?.optJSONObject(it)?.optInt("numberInSurah") == customEnd.toIntOrNull()
                }
                val targetEnd = when (rangeMode) { "surah" -> surahEnd; "custom" -> customGlobalEnd ?: 0; else -> pageEnd }
                val valid = targetEnd in selectedAyah..surahEnd
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { repeatRange = !repeatRange }) {
                        Text("↻", color = if (repeatRange) Color(0xFFCE9928) else gold, fontSize = 24.sp)
                    }
                    IconButton(onClick = { audio.previous(); isPaused = false }) {
                        Text("◀◀", color = gold, fontSize = 18.sp)
                    }
                    IconButton(onClick = {
                        if (!isPlaying && !audio.isPaused()) {
                            if (valid) startAudio(selectedAyah, targetEnd)
                        } else if (audio.isPaused()) {
                            audio.resume(); isPaused = false
                        } else {
                            audio.pause(); isPaused = true
                        }
                    }, enabled = valid || isPlaying) {
                        Text(if (isPlaying && !isPaused) "⏸" else "▶", color = gold, fontSize = 27.sp)
                    }
                    IconButton(onClick = { audio.next(); isPaused = false }) {
                        Text("▶▶", color = gold, fontSize = 18.sp)
                    }
                    IconButton(onClick = { audio.stop(); isPlaying = false; isPaused = false; playbackStatus = "تم الإيقاف" }) {
                        Text("■", color = gold, fontSize = 22.sp)
                    }
                }
                if (!valid) Text("اختر رقم آية نهاية صحيحًا", color = Color(0xFF9A3324), fontSize = 12.sp)
                if (playbackStatus.isNotBlank()) Text(playbackStatus, color = gold, fontSize = 12.sp)
            }
        }

    }
    } // Box
    } // CompositionLocalProvider
}

private class MushafPageView(
    context: Context,
    private val page: Int,
    private val navigate: (Int) -> Unit,
    private val selectAyah: (Int) -> Unit,
    private val allowNetwork: Boolean,
    private val reportError: (String) -> Unit
) : View(context) {
    companion object {
        // لا تستطيع حركات السحب السريعة إنشاء عدد غير محدود من خيوط تحليل SVG.
        private val executor = java.util.concurrent.ScheduledThreadPoolExecutor(2).apply {
            removeOnCancelPolicy = true
        }
    }
    private var task: java.util.concurrent.Future<*>? = null
    @Volatile private var detached = false
    @Volatile private var svg: SVG? = null
    @Volatile private var markers: org.json.JSONArray? = null
    private var displayedViewport: RectF? = null
    private var pageCoordinates: RectF? = null
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val dst = displayedViewport ?: return false
            val src = pageCoordinates ?: return false
            val points = markers ?: return false
            if (!dst.contains(e.x, e.y) || points.length() == 0) return false
            val x = src.left + (e.x - dst.left) * src.width() / dst.width()
            val y = src.top + (e.y - dst.top) * src.height() / dst.height()
            var nearest = 0
            var minDistance = Float.MAX_VALUE
            for (i in 0 until points.length()) {
                val m = points.optJSONArray(i) ?: continue
                val dx = (m.optDouble(1) - x).toFloat()
                val dy = (m.optDouble(2) - y).toFloat()
                val dist = dx * dx + dy * dy
                if (dist < minDistance) { minDistance = dist; nearest = m.optInt(0) }
            }
            if (nearest > 0) selectAyah(nearest)
            return nearest > 0
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            if (kotlin.math.abs(e2.x - start.x) < 80f || kotlin.math.abs(velocityX) < 350f) return false
            // اتجاه الكتاب العربي: اسحب إلى اليمين للانتقال إلى الصفحة التالية.
            if (e2.x > start.x) navigate(page + 1) else navigate(page - 1)
            return true
        }
    })
    init {
        setBackgroundColor(AndroidColor.rgb(253, 247, 230))
        startLoading()
    }
    private fun startLoading() {
        task = executor.submit {
            val result = runCatching {
                markers = MushafIndexStore.load(context).pages.getJSONObject(page).getJSONArray("markers")
                loadMushafDrawing(context, page, allowNetwork)
            }
            post {
                if (!detached) {
                    if (result.isSuccess) { svg = result.getOrNull(); invalidate() }
                    else reportError("تعذّر تحميل صفحة $page: ${result.exceptionOrNull()?.message.orEmpty()}")
                }
            }
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (detached) { detached = false; startLoading() }
    }
    override fun onDetachedFromWindow() {
        detached = true
        task?.cancel(true)
        svg = null
        super.onDetachedFromWindow()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = detector.onTouchEvent(event) || super.onTouchEvent(event)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawing = svg ?: return
        val viewport = drawing.documentViewBox ?: RectF(0f, 0f, drawing.documentWidth, drawing.documentHeight)
        if (viewport.width() <= 0f || viewport.height() <= 0f) return
        val inset = 2f * resources.displayMetrics.density
        val target = renderMushafDrawing(canvas, drawing, RectF(inset,inset,width-inset,height-inset))
        pageCoordinates = RectF(viewport)
        displayedViewport = target
    }
}

internal fun loadMushafDrawing(context: Context, page: Int, allowNetwork: Boolean = true): SVG {
    require(page in 1..604)
    return MushafPageStore.openPage(context,page,allowNetwork)
        .use { SVG.getFromInputStream(it) }
}

/** مسار Canvas واحد يحافظ على تناسب الأبعاد للقارئ ومعاينة الصورة وتصدير PDF. */
internal fun renderMushafDrawing(canvas: Canvas, drawing: SVG, bounds: RectF): RectF {
    val source = drawing.documentViewBox ?: RectF(0f,0f,drawing.documentWidth,drawing.documentHeight)
    require(source.width() > 0f && source.height() > 0f)
    val scale = minOf(bounds.width().coerceAtLeast(1f)/source.width(), bounds.height().coerceAtLeast(1f)/source.height())
    val width = source.width()*scale; val height = source.height()*scale
    val left = bounds.centerX()-width/2; val top = bounds.centerY()-height/2
    val target = RectF(left,top,left+width,top+height)
    drawing.renderToCanvas(canvas,target)
    return target
}


/** يشغّل الآيات المرقمة بالتتابع بمشغّل واحد؛ بلا خدمة خلفية أو ملفات صوت مضمّنة. */
private val QURAN_RECITERS = listOf(
    "مشاري العفاسي" to "Alafasy_128kbps",
    "محمود خليل الحصري" to "Husary_128kbps",
    "محمد صديق المنشاوي" to "Minshawy_Murattal_128kbps",
    "عبدالباسط عبدالصمد" to "Abdul_Basit_Murattal_64kbps",
    "محمد أيوب" to "Muhammad_Ayyoub_128kbps",
    "عبدالرحمن السديس" to "Abdurrahmaan_As-Sudais_64kbps",
    "ماهر المعيقلي" to "MaherAlMuaiqly128kbps",
    "فارس عباد" to "Fares_Abbad_64kbps",
    "أبو بكر الشاطري" to "Abu_Bakr_Ash-Shaatree_128kbps"
)

private class AyahSequencePlayer {
    private var media: MediaPlayer? = null
    private var ayahs: org.json.JSONArray? = null
    private var start = 0
    private var end = 0
    private var current = 0
    private var folder = ""
    private var repeat: () -> Boolean = { false }
    private var notify: (Int, Boolean, String) -> Unit = { _, _, _ -> }
    private var generation = 0
    private var paused = false
    private val main = Handler(Looper.getMainLooper())

    fun isPaused() = paused
    fun playRange(ayahs: org.json.JSONArray?, start: Int, end: Int, folder: String,
                  repeat: () -> Boolean, onUpdate: (Int, Boolean, String) -> Unit) {
        stop()
        if (ayahs == null || start < 1 || end < start || end >= ayahs.length()) {
            onUpdate(start, false, "نطاق الآيات غير صحيح")
            return
        }
        this.ayahs = ayahs; this.start = start; this.end = end; this.current = start
        this.folder = folder; this.repeat = repeat; this.notify = onUpdate
        playCurrent()
    }
    private fun playCurrent() {
        media?.release(); media = null
        if (current > end) {
            if (repeat()) current = start else { notify(end, false, "اكتملت التلاوة"); return }
        }
        val info = ayahs?.optJSONObject(current)
        val surah = info?.optInt("surah", 0) ?: 0
        val ayah = info?.optInt("numberInSurah", 0) ?: 0
        if (surah !in 1..114 || ayah < 1) { notify(current, false, "بيانات الآية غير متاحة"); return }
        val url = "https://everyayah.com/data/$folder/%03d%03d.mp3".format(Locale.ROOT, surah, ayah)
        val token = ++generation
        paused = false
        try {
            val m = MediaPlayer()
            media = m
            m.setDataSource(url)
            m.setOnPreparedListener {
                if (token == generation) {
                    it.start(); notify(current, true, "جارٍ التشغيل")
                }
            }
            m.setOnCompletionListener {
                if (token == generation) { current++; main.post { if (token == generation) playCurrent() } }
            }
            m.setOnErrorListener { _, _, _ ->
                if (token == generation) {
                    notify(current, false, "تعذر تحميل الآية. تحقق من الإنترنت أو اختر قارئًا آخر.")
                    stopMediaOnly()
                }
                true
            }
            notify(current, true, "جارٍ تحميل الآية")
            m.prepareAsync()
        } catch (e: Exception) { notify(current, false, "تعذر بدء التلاوة: ${e.localizedMessage}"); stopMediaOnly() }
    }
    private fun stopMediaOnly() { generation++; media?.release(); media = null }
    fun pause() { if (media?.isPlaying == true) { media?.pause(); paused = true } }
    fun resume() { if (paused) { media?.start(); paused = false } }
    fun next() { if (current < end) { current++; playCurrent() } }
    fun previous() { if (current > start) { current--; playCurrent() } else { current = start; playCurrent() } }
    fun stop() { stopMediaOnly(); paused = false }
}

/** عرض الفهرس المشترك: يقبل الأرقام العربية واللاتينية في علامات التبويب الأربع. */
internal fun mushafArabicNumber(value: Int): String = value.toString().map { if(it in '0'..'9') "٠١٢٣٤٥٦٧٨٩"[it-'0'] else it }.joinToString("")
internal fun mushafIndexSearch(value: String): String = value.map {
    when(it) { in '٠'..'٩' -> '0' + (it-'٠'); in '۰'..'۹' -> '0' + (it-'۰'); 'أ','إ','آ','ٱ' -> 'ا'; else -> it }
}.joinToString("").replace(Regex("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED]"), "").trim()
