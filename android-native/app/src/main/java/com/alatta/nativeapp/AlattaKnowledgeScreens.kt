package com.alatta.nativeapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun KnowledgeScreen(section: String, query: String, modifier: Modifier = Modifier,
    onClearQuery: () -> Unit = {}, onShareHadithPost: (String) -> Unit,
    onShareDhikrImagePost: (DhikrGroup, Dhikr) -> Unit = { _, _ -> }) {
    if (section == "الأذكار") AzkarPrayerScreen(modifier,
        onSharePost = onShareHadithPost, onShareImagePost = onShareDhikrImagePost)
    else HadithLibraryScreen(query, modifier, onClearQuery, onShareHadithPost)
}
