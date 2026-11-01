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

    @Test fun reelsOnlyContainUploadedVideoPostsNotYoutubeLinks() {
        val uploaded = org.json.JSONObject().put("id", "post1").put("video", "/media/video/one.mp4")
        val youtube = org.json.JSONObject().put("id", "post2").put("text", attachYoutubeToPost("فيديو", video))
        val both = org.json.JSONObject().put("id", "post3").put("video", "/media/video/three.mp4")
            .put("text", attachYoutubeToPost("فيديو ثالث", "abcdefghijk"))
        val reels = uploadedPostReels(listOf(uploaded, youtube, both, uploaded))
        assertEquals(2, reels.size)
        assertEquals(listOf("post1", "post3"), reels.map { it.post.optString("id") })
        assertEquals(2, reels.map { it.key }.distinct().size)
        assertTrue(reels.all { it.videoUrl.isNotBlank() })
        assertTrue(reels[0].videoUrl.endsWith("/media/video/one.mp4"))
        assertEquals(video, youtubePostId(youtube.optString("text")))
    }

    @Test fun ordinaryPostsAreLeftUntouched() {
        val ordinary = "منشور عادي بدون فيديو"
        assertNull(youtubePostId(ordinary))
        assertEquals(ordinary, visibleYoutubePostText(ordinary))
    }
}
