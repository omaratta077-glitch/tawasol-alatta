package com.alatta.nativeapp

internal fun formatDhikrShare(group: DhikrGroup, entry: Dhikr? = null): String =
    (listOf(group.title) + (entry?.let { listOf(it) } ?: group.entries).map { item ->
        listOf(item.title, item.text, "التكرار: ${item.target}", item.source, item.note)
            .filter { it.isNotBlank() }.joinToString("\n\n")
    }).joinToString("\n\n")
