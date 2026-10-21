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

private val gold = Color(0xFFF6D776)

@Composable
internal fun KnowledgeScreen(section: String, query: String, modifier: Modifier = Modifier, onClearQuery: () -> Unit = {}) {
    if (section == "الأذكار") AzkarPrayerScreen(query, modifier)
    else HadithLibraryScreen(query, modifier, onClearQuery)
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
