package com.alatta.nativeapp

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YoutubePostShareTest {
    private val video = "dQw4w9WgXcQ"

    @Test fun videoPostsStoreNoVisibleYouTubeLink() {
        val saved = attachYoutubeToPost("وصف عربي", video)
        assertEquals(video, youtubePostId(saved))
        assertEquals("وصف عربي", visibleYoutubePostText(saved))
        assertFalse(saved.contains("youtube.com"))
        assertEquals("", visibleYoutubePostText(attachYoutubeToPost("", video)))
    }

    @Test fun invalidVideoIdsCannotBePosted() {
        for (id in listOf("", "abc", "https://youtube.com/watch?v=dQw4w9WgXcQ", "a/b-c_dEF12")) {
            assertThrows(IllegalArgumentException::class.java) {
                attachYoutubeToPost("منشور", id)
            }
        }
    }

    @Test fun reelsContainUploadedVideosAndYouTubeOfAnyDuration() {
        val uploaded = org.json.JSONObject().put("id", "post1").put("video", "/media/video/one.mp4")
        val youtube = org.json.JSONObject().put("id", "post2").put("text", attachYoutubeToPost("فيديو", video))
        val shortText = attachYoutubeToPost("شورت قرآني", "abcdefghijk", isShort = true)
        val short = org.json.JSONObject().put("id", "post3").put("text", shortText)
        val both = org.json.JSONObject().put("id", "post4").put("video", "/media/video/four.mp4")
            .put("text", attachYoutubeToPost("شورت مصاحب", video, isShort = true))
        val reels = uploadedPostReels(listOf(uploaded, youtube, short, both, uploaded))
        assertEquals(5, reels.size)
        assertEquals(listOf("post1", "post2", "post3", "post4", "post4"), reels.map { it.post.optString("id") })
        assertEquals(5, reels.map { it.key }.distinct().size)
        assertEquals(listOf(null, video, "abcdefghijk", null, video), reels.map { it.youtubeId })
        assertTrue(reels[0].videoUrl.endsWith("/media/video/one.mp4"))
        assertNull(youtubeShortPostId(youtube.optString("text")))
        assertEquals("abcdefghijk", youtubeShortPostId(shortText))
        assertEquals("شورت قرآني", visibleYoutubePostText(shortText))
        assertEquals(video, youtubePostId(youtube.optString("text")))
        val filtered = uploadedPostReels(listOf(uploaded, youtube, short, both),
            rejectedShortIds = setOf("abcdefghijk"))
        assertEquals(listOf("post1", "post2", "post4", "post4"),
            filtered.map { it.post.optString("id") })
        assertEquals(listOf(null, video, null, video), filtered.map { it.youtubeId })
    }

    @Test fun ordinaryPostsAreLeftUntouched() {
        val ordinary = "منشور عادي بدون فيديو"
        assertNull(youtubePostId(ordinary))
        assertEquals(ordinary, visibleYoutubePostText(ordinary))
    }
}
