package com.alatta.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
private fun HadithPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = PremiumPanel, shape = RoundedCornerShape(18.dp), border = BorderStroke(.7.dp, PremiumBorder)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun HadithLibraryScreen(query: String, modifier: Modifier = Modifier, onClearQuery: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("alatta_hadith", Context.MODE_PRIVATE) }
    var books by remember { mutableStateOf(emptyList<HadithBook>()) }
    var sections by remember { mutableStateOf(emptyList<HadithSection>()) }
    var bookId by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var reading by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var previousQuery by rememberSaveable { mutableStateOf(query) }
    var result by remember { mutableStateOf<HadithPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var diagnostic by remember { mutableStateOf("") }
    var repairRequested by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var fontSize by remember { mutableIntStateOf(preferences.getInt("font_size", 22).coerceIn(17, 30)) }
    var showSources by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf("") }
    var jumpError by remember { mutableStateOf("") }
    var bookmarkBook by remember { mutableStateOf(preferences.getString("bookmark_book", "").orEmpty()) }
    var bookmarkNumber by remember { mutableIntStateOf(preferences.getInt("bookmark_number", 0)) }
    var resumeNumber by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val book = books.firstOrNull { it.id == bookId }
    val showReadings = query.isNotBlank() || reading

    fun back() {
        when {
            query.isNotBlank() -> { onClearQuery(); page = 0 }
            reading -> { reading = false; section = 0; page = 0 }
            else -> { bookId = ""; section = 0; page = 0 }
        }
    }
    fun readAt(selectedBook: String, number: Int) {
        onClearQuery(); bookId = selectedBook; section = 0; reading = true
        page = (number - 1).coerceAtLeast(0) / HADITH_PAGE_SIZE
        resumeNumber = number
    }
    BackHandler(enabled = bookId.isNotBlank() || query.isNotBlank() || reading) { back() }
    LaunchedEffect(retry) {
        loading = true; error = ""; diagnostic = ""
        try {
            if (repairRequested) {
                HadithLibrary.repair(context)
                repairRequested = false
            }
            books = HadithLibrary.books(context)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            Log.e("AlattaHadith", "Library initialization failed on device", failure)
            // Keep private paths/session data out of the screenshot and clipboard.
            val origin = generateSequence(failure as Throwable?) { it.cause }.last()
            val kind = when (origin) {
                is android.database.sqlite.SQLiteException -> "SQLite"
                is java.io.IOException -> "STORAGE"
                is SecurityException -> "ACCESS"
                else -> "INIT"
            }
            diagnostic = "Hadith ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • $kind • ${origin.javaClass.simpleName}"
            error = (failure as? HadithPreparationException)?.message
                ?: "تعذر فتح مكتبة الأحاديث ($kind). جرّب إعادة تجهيز المكتبة."
        } finally { loading = false }
    }
    LaunchedEffect(query) {
        if (previousQuery != query) { page = 0; previousQuery = query }
    }
    LaunchedEffect(books, bookId, section, query, reading, page, retry) {
        if (books.isEmpty()) return@LaunchedEffect
        loading = true; error = ""; result = null
        try {
            if (showReadings) {
                if (query.isNotBlank()) delay(300)
                result = HadithLibrary.page(context, bookId, section, query, page)
                result?.let { if (it.page != page) page = it.page }
            } else if (book != null) sections = HadithLibrary.sections(context, bookId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "تعذر عرض الأحاديث. أعد المحاولة." }
        finally { loading = false }
    }
    LaunchedEffect(result?.entries?.firstOrNull()?.id, query, bookId, section) {
        val entries = result?.entries ?: return@LaunchedEffect
        val anchor = entries.indexOfFirst { it.number == resumeNumber }.coerceAtLeast(0)
        if (entries.isNotEmpty()) listState.scrollToItem(anchor)
        resumeNumber = 0
    }

    Column(modifier.fillMaxSize().background(PremiumBackground).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (bookId.isNotBlank() || query.isNotBlank() || reading) IconButton(onClick = { back() }) { SiteIcon("arrow-left", size = 22.dp) }
            Text(book?.title ?: "مكتبة الأحاديث", Modifier.weight(1f), color = PremiumGoldLight, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            TextButton(onClick = { showSources = true }) { Text("المصادر", color = PremiumGoldLight, fontSize = 12.sp) }
        }
        if (notice.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(notice, Modifier.weight(1f), color = PremiumGoldLight, fontSize = 12.sp)
            TextButton(onClick = { notice = "" }) { Text("تم", color = PremiumMuted) }
        }
        if (book != null) {
            Text(book.edition, color = PremiumMuted, fontSize = 11.sp)
            if (!showReadings) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { reading = true; section = 0; page = 0 }, modifier = Modifier.weight(1f)) { Text("قراءة كل الأحاديث", color = PremiumGoldLight) }
                TextButton(onClick = { jump = ""; jumpError = ""; showJump = true }, modifier = Modifier.weight(1f)) { Text("انتقال لرقم حديث", color = PremiumGoldLight) }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = PremiumGold)
        if (error.isNotBlank()) {
            Text(error, color = Color(0xFFFFB4A9), modifier = Modifier.padding(12.dp))
            if (diagnostic.isNotBlank()) Text(diagnostic, color = PremiumMuted, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { retry++ }) { Text("إعادة المحاولة", color = PremiumGoldLight) }
                TextButton(onClick = {
                    repairRequested = true
                    retry++
                }) { Text("إعادة تجهيز المكتبة", color = PremiumGoldLight) }
            }
            if (diagnostic.isNotBlank()) TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("تشخيص الأحاديث", diagnostic))
                notice = "تم نسخ تشخيص المشكلة"
            }) { Text("نسخ التشخيص", color = PremiumGoldLight, fontSize = 12.sp) }
        }
        when {
            books.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (error.isBlank()) Text("جارٍ تجهيز الكتب على الهاتف…", color = PremiumMuted)
            }
            showReadings -> {
                result?.let { data ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${data.total} حديثًا • ${if (query.isNotBlank()) "نتائج البحث" else if (section > 0) "مجموعة القراءة $section" else "كل الكتاب"}", Modifier.weight(1f), color = PremiumMuted, fontSize = 12.sp)
                        TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(17); preferences.edit().putInt("font_size", fontSize).apply() }, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(34.dp)) { Text("أ−", color = PremiumGold) }
                        TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(30); preferences.edit().putInt("font_size", fontSize).apply() }, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(34.dp)) { Text("أ+", color = PremiumGold) }
                    }
                }
                LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val data = result
                    if (!loading && data?.entries?.isEmpty() == true) item { Text("لا توجد نتائج. جرّب كلمة أخرى أو رقم الحديث من هذه النسخة.", color = PremiumMuted) }
                    items(data?.entries.orEmpty(), key = { it.id }) { entry ->
                        val sourceBook = books.first { it.id == entry.bookId }
                        HadithPanel {
                            Text("${sourceBook.title} • الحديث ${entry.number}", color = PremiumGoldLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            SelectionContainer { Text(entry.body, color = Color.White, fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.8f).sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()) }
                            Text(sourceBook.edition, color = PremiumMuted, fontSize = 11.sp)
                            val sharedText = "${entry.body}\n\n${sourceBook.title} • الحديث ${entry.number}\n${sourceBook.edition}"
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = {
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("حديث", sharedText))
                                    notice = "تم نسخ الحديث ومرجعه"
                                }) { Text("نسخ", color = PremiumGoldLight) }
                                TextButton(onClick = {
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sharedText), "مشاركة الحديث"))
                                }) { Text("مشاركة", color = PremiumGoldLight) }
                                TextButton(onClick = {
                                    bookmarkBook = entry.bookId; bookmarkNumber = entry.number
                                    preferences.edit().putString("bookmark_book", entry.bookId).putInt("bookmark_number", entry.number).apply()
                                    notice = "تم حفظ موضع القراءة"
                                }) { Text("حفظ الموضع", color = PremiumGoldLight, fontSize = 12.sp) }
                            }
                        }
                    }
                }
                result?.takeIf { it.pages > 0 }?.let { data ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !loading && data.page > 0, onClick = { page = 0 }, contentPadding = PaddingValues(4.dp)) { Text("الأولى", fontSize = 12.sp) }
                        TextButton(enabled = !loading && data.page > 0, onClick = { page = data.page - 1 }, contentPadding = PaddingValues(4.dp)) { Text("السابق", fontSize = 12.sp) }
                        Text("${data.page + 1}/${data.pages}", color = PremiumGoldLight, fontSize = 12.sp)
                        TextButton(enabled = !loading && data.page < data.pages - 1, onClick = { page = data.page + 1 }, contentPadding = PaddingValues(4.dp)) { Text("التالي", fontSize = 12.sp) }
                        TextButton(enabled = !loading && data.page < data.pages - 1, onClick = { page = data.pages - 1 }, contentPadding = PaddingValues(4.dp)) { Text("الأخيرة", fontSize = 12.sp) }
                    }
                }
            }
            book != null -> key(bookId) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("فهرس القراءة • مجموعات حتى 100 حديث، وليست أسماء أبواب الكتاب الأصلية", color = PremiumMuted, fontSize = 12.sp) }
                    items(sections, key = { it.id }) { part ->
                        OutlinedButton(onClick = { section = part.id; reading = true; page = 0 }, modifier = Modifier.fillMaxWidth()) {
                            Text(part.title, color = PremiumGoldLight, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                            Text("${part.count}", color = PremiumMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("النصوص العربية محفوظة دون إنترنت. ابحث بالكلمات أو برقم الحديث من الشريط العلوي.", color = PremiumMuted, fontSize = 13.sp) }
                val savedBook = books.firstOrNull { it.id == bookmarkBook }
                if (savedBook != null && bookmarkNumber in 1..savedBook.count) item {
                    Button(onClick = { readAt(savedBook.id, bookmarkNumber) }, modifier = Modifier.fillMaxWidth()) {
                        Text("متابعة ${savedBook.title} • الحديث $bookmarkNumber")
                    }
                }
                items(books, key = { it.id }) { collection ->
                    HadithPanel {
                        Text(collection.title, color = PremiumGoldLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(collection.author, color = Color.White, fontSize = 13.sp)
                        Text("${collection.count} حديثًا في النسخة المضمنة", color = PremiumMuted, fontSize = 12.sp)
                        Button(onClick = { bookId = collection.id; section = 0; reading = false; page = 0 }, modifier = Modifier.fillMaxWidth()) { Text("فتح الكتاب") }
                    }
                }
            }
        }
    }
    if (showJump && book != null) AlertDialog(onDismissRequest = { showJump = false }, title = { Text("الانتقال إلى حديث") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("أدخل رقمًا من 1 إلى ${book.count} حسب ترقيم النسخة المضمنة.", fontSize = 13.sp)
            OutlinedTextField(jump, onValueChange = { jump = it.take(8); jumpError = "" }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, label = { Text("رقم الحديث") })
            if (jumpError.isNotBlank()) Text(jumpError, color = Color(0xFFFFB4A9))
        }
    }, confirmButton = { TextButton(onClick = {
        val number = normalizeHadithSearch(jump).toIntOrNull()
        if (number != null && number in 1..book.count) { readAt(book.id, number); showJump = false }
        else jumpError = "اكتب رقم حديث صحيحًا من هذه النسخة"
    }) { Text("انتقال") } }, dismissButton = { TextButton(onClick = { showJump = false }) { Text("رجوع") } })
    if (showSources) AlertDialog(onDismissRequest = { showSources = false }, title = { Text("مصادر مكتبة الأحاديث") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("النصوص كاملة كما في النسخ المصدرية المبيّنة. تختلف طريقة جمع الروايات وترقيمها بين الطبعات. فهرس القراءة يقسّم النص إلى مجموعات للتصفح.", fontSize = 13.sp)
            books.forEach { collection ->
                Text(collection.title, fontWeight = FontWeight.Bold)
                Text(collection.source, fontSize = 12.sp)
                Text(collection.edition, fontSize = 12.sp)
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(collection.sourceUrl))) } }) { Text("عرض المصدر") }
            }
        }
    }, confirmButton = { TextButton(onClick = { showSources = false }) { Text("تم") } })
}
