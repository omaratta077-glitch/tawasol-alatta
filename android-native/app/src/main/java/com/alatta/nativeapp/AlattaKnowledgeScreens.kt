package com.alatta.nativeapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun KnowledgeScreen(section: String, query: String, modifier: Modifier = Modifier,
    onClearQuery: () -> Unit = {}, onShareHadithPost: (String) -> Unit) {
    if (section == "الأذكار") AzkarPrayerScreen(query, modifier, onSharePost = onShareHadithPost)
    else HadithLibraryScreen(query, modifier, onClearQuery, onShareHadithPost)
}
