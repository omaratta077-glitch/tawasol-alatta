package com.alatta.nativeapp

/** Preserve the complete religious text and source, including whole dhikr groups.
 * Match the Worker's validated 50,000-character limit; never silently truncate.
 */
internal fun composeHadithPost(currentDraft: String, fullHadithWithSource: String): String? {
    val selected = fullHadithWithSource.trim()
    if (selected.isBlank()) return null
    val previous = currentDraft.trim()
    val composed = if (previous.isBlank()) selected else "$previous\n\n$selected"
    return composed.takeIf { it.length <= 50000 }
}
