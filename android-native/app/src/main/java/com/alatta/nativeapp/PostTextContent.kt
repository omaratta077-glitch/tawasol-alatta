package com.alatta.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal fun needsPostReader(text: String) = text.length > 500 || text.count { it == '\n' } > 8
internal fun postTextPreview(text: String): String = text.take(320).let {
    if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
}.lineSequence().take(6).joinToString("\n").trimEnd()

/** A compact feed preview; the original complete text stays inside its own reader. */
@Composable internal fun PostTextContent(text: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    var reading by remember(text) { mutableStateOf(false) }
    val long = needsPostReader(text)
    if (text.isNotBlank()) Column(modifier.then(if (long) Modifier.clickable { reading = true } else Modifier)) {
        Text(if (long) postTextPreview(text) else text, color = color,
            maxLines = if (long) 6 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        if (long) TextButton(onClick = { reading = true }) { Text("قراءة المنشور كاملًا", color = PremiumGoldLight) }
    }
    if (reading) Dialog(onDismissRequest = { reading = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(PremiumBackground).safeDrawingPadding().padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("المنشور كاملًا", Modifier.weight(1f).padding(vertical = 12.dp), color = PremiumGoldLight, fontSize = 20.sp)
                TextButton(onClick = { reading = false }) { Text("إغلاق", color = PremiumGoldLight) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(text.split("\n\n")) { paragraph -> Text(paragraph, color = Color.White, fontSize = 18.sp) }
            }
        }
    }
}
