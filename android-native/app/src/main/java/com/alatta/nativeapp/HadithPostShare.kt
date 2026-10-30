package com.alatta.nativeapp

/** احتفظ بالنص الديني ومصدره كاملين، بما في ذلك مجموعات الأذكار كاملة.
 * التزم بحد Worker المعتمد البالغ 50,000 حرف؛ ولا تحذف النهاية بصمت.
 */
internal fun composeHadithPost(currentDraft: String, fullHadithWithSource: String): String? {
    val selected = fullHadithWithSource.trim()
    if (selected.isBlank()) return null
    val previous = currentDraft.trim()
    val composed = if (previous.isBlank()) selected else "$previous\n\n$selected"
    return composed.takeIf { it.length <= 50000 }
}
