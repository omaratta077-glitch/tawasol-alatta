package com.alatta.nativeapp

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private data class KnowledgeEntry(val title: String, val body: String, val source: String = "")
private val gold = Color(0xFFF6D776)
private val panel = Color(0xFF111518)
private val hadithEntries = listOf(
    KnowledgeEntry("النية", "إنما الأعمال بالنيات، وإنما لكل امرئ ما نوى.", "رواه البخاري ومسلم"),
    KnowledgeEntry("حب الخير للآخرين", "لا يؤمن أحدكم حتى يحب لأخيه ما يحب لنفسه.", "رواه البخاري ومسلم"),
    KnowledgeEntry("الكلمة الطيبة", "الكلمة الطيبة صدقة.", "رواه البخاري ومسلم"),
    KnowledgeEntry("الرحمة", "من لا يَرحم لا يُرحم.", "رواه البخاري ومسلم")
)

@Composable
internal fun KnowledgeScreen(section: String, query: String, modifier: Modifier = Modifier) {
    if (section == "الأذكار") {
        AzkarPrayerScreen(query, modifier)
        return
    }
    val entries = hadithEntries
    Column(modifier.fillMaxSize().background(Color(0xFF040506)).padding(horizontal = 12.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(entries.filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }) { item ->
                Column(Modifier.fillMaxWidth().border(1.dp, Color(0xFF6B5429), RoundedCornerShape(14.dp))
                    .background(panel, RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(item.title, color = gold, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(item.body, color = Color.White, fontSize = 19.sp, lineHeight = 33.sp,
                        textAlign = TextAlign.Start)
                    if (item.source.isNotEmpty()) Text(item.source, color = Color(0xFFC9D0D4), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun FootballScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize().background(Color(0xFF040506))) {
        Text("كرة القدم • المباريات والنتائج والأخبار", color = gold, modifier = Modifier.padding(12.dp), fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { reload++ }) { Text("تحديث", color = gold) }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.filgoal.com/")))
            }) { Text("فتح المصدر الخارجي", color = gold) }
        }
        key(reload) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url ?: return true
                            if (url.scheme != "https") return true
                            return false
                        }
                    }
                    loadUrl("https://www.filgoal.com/")
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}
