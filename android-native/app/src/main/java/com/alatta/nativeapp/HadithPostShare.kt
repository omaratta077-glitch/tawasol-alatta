package com.alatta.nativeapp

/** Keep every word and source when sharing a hadith or dhikr. The existing Worker limits posts
 * to 5,000 characters and silently slices longer input, so refuse rather than
 * publish truncated or misleading religious text.
 */
internal fun composeHadithPost(currentDraft: String, fullHadithWithSource: String): String? {
    val selected = fullHadithWithSource.trim()
    if (selected.isBlank()) return null
    val previous = currentDraft.trim()
    val composed = if (previous.isBlank()) selected else "$previous\n\n$selected"
    return composed.takeIf { it.length <= 5000 }
}
