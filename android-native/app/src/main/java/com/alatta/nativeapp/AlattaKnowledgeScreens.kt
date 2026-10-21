package com.alatta.nativeapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun KnowledgeScreen(section: String, query: String, modifier: Modifier = Modifier, onClearQuery: () -> Unit = {}) {
    if (section == "الأذكار") AzkarPrayerScreen(query, modifier)
    else HadithLibraryScreen(query, modifier, onClearQuery)
}
