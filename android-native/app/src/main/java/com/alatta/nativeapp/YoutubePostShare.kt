package com.alatta.nativeapp

/** تحتفظ مشاركات YouTube بمعرّف الفيديو فقط، من دون تنزيل أو إعادة استضافة المحتوى. */
private val YOUTUBE_POST_ID = Regex("[A-Za-z0-9_-]{11}")
private val YOUTUBE_POST_MARKER =
    Regex("""(?m)^\[\[alatta-youtube(-short)?:([A-Za-z0-9_-]{11})\]\][ \t]*$""")

internal fun youtubePostId(text: String): String? =
    YOUTUBE_POST_MARKER.find(text)?.groupValues?.get(2)

internal fun youtubeShortPostId(text: String): String? =
    YOUTUBE_POST_MARKER.find(text)?.takeIf { it.groupValues[1] == "-short" }?.groupValues?.get(2)

internal fun visibleYoutubePostText(text: String): String =
    YOUTUBE_POST_MARKER.replace(text, "")
        .replace(Regex("\\n{3,}"), "\n\n").trim()

/** عند اختيار "شورت" يوضع نوعه في المنشور دون المساس بمسار YouTube المعتاد. */
internal fun attachYoutubeToPost(text: String, videoId: String, isShort: Boolean = false): String {
    require(YOUTUBE_POST_ID.matches(videoId)) { "معرّف فيديو يوتيوب غير صالح" }
    require(youtubePostId(text) == null) { "يوجد فيديو يوتيوب مرفق بالفعل" }
    val clean = text.trim()
    val tag = if (isShort) "alatta-youtube-short" else "alatta-youtube"
    val value = (if (clean.isEmpty()) "" else "$clean\n\n") + "[[$tag:$videoId]]"
    require(value.length <= 5000) { "وصف المنشور طويل جدًا" }
    return value
}
