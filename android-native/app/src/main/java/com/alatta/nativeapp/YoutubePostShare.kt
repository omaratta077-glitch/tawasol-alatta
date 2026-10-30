package com.alatta.nativeapp

/** احفظ في مرفق المنشور الداخلي معرّف فيديو YouTube ذي 11 حرفًا فقط.
 * لا تحفظ رابط مشاهدة ظاهرًا، ولا تنزّل فيديو ناشر آخر أو تعيد استضافته.
 * يستخدم كل من محرر المنشور والموجز مشغّل YouTube الرسمي المضمّن الحالي.
 */
private val YOUTUBE_POST_ID = Regex("[A-Za-z0-9_-]{11}")
private val YOUTUBE_POST_MARKER = Regex("""(?m)^\[\[alatta-youtube:([A-Za-z0-9_-]{11})\]\][ \t]*$""")

internal fun youtubePostId(text: String): String? =
    YOUTUBE_POST_MARKER.find(text)?.groupValues?.get(1)

internal fun visibleYoutubePostText(text: String): String =
    YOUTUBE_POST_MARKER.replace(text, "")
        .replace(Regex("\\n{3,}"), "\n\n").trim()

internal fun attachYoutubeToPost(text: String, videoId: String): String {
    require(YOUTUBE_POST_ID.matches(videoId)) { "معرّف فيديو يوتيوب غير صالح" }
    require(youtubePostId(text) == null) { "يوجد فيديو يوتيوب مرفق بالفعل" }
    val clean = text.trim()
    val value = (if (clean.isEmpty()) "" else "$clean\n\n") + "[[alatta-youtube:$videoId]]"
    require(value.length <= 5000) { "وصف المنشور طويل جدًا" }
    return value
}
