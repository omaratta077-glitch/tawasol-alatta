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

    @Test fun reelsCombineUploadedVideosAndYouTubeWithoutDuplicatingPostsOrMixingIds() {
        val uploaded = org.json.JSONObject().put("id", "post1").put("video", "/media/video/one.mp4")
        val youtube = org.json.JSONObject().put("id", "post2").put("text", attachYoutubeToPost("فيديو", video))
        val both = org.json.JSONObject().put("id", "post3").put("video", "/media/video/three.mp4")
            .put("text", attachYoutubeToPost("فيديو ثالث", "abcdefghijk"))
        val reels = mixedPostReels(listOf(uploaded, youtube, both))
        assertEquals(4, reels.size)
        assertEquals(4, reels.map { it.key }.distinct().size)
        assertEquals(listOf("post1", "post2", "post3", "post3"), reels.map { it.post.optString("id") })
        assertEquals(listOf(false, true, false, true), reels.map { it.youtubeId != null })
        assertEquals(video, reels[1].youtubeId)
        assertEquals("abcdefghijk", reels[3].youtubeId)
        assertTrue(reels[0].videoUrl.endsWith("/media/video/one.mp4"))
    }

    @Test fun publicFacebookReelsJoinMixedFeedWithoutPrivateLinks() {
        val publicReel = "https://www.facebook.com/reel/123456789012345"
        assertEquals(publicReel, facebookReelUrl("فيديو رائع $publicReel"))
        assertEquals("https://www.facebook.com/watch/?v=12345", facebookReelUrl("https://m.facebook.com/watch/?v=12345"))
        assertEquals("https://fb.watch/abcDe12/", facebookReelUrl("https://fb.watch/abcDe12/"))
        for (url in listOf("https://evil.facebook.com/reel/123", "https://facebook.com.evil.test/reel/123",
            "http://www.facebook.com/reel/123", "https://www.facebook.com/profile.php?id=123")) {
            assertNull(url, facebookReelUrl(url))
        }
        assertTrue(facebookEmbedUrl(publicReel).startsWith("https://www.facebook.com/plugins/video.php?href="))
        val fb = org.json.JSONObject().put("id", "facebook-post").put("text", "فيديو من فيسبوك $publicReel")
        val yt = org.json.JSONObject().put("id", "yt-post").put("text", attachYoutubeToPost("من يوتيوب", video))
        val uploaded = org.json.JSONObject().put("id", "video-post").put("video", "/media/video/one.mp4")
        val feed = mixedPostReels(listOf(fb, yt, uploaded))
        assertEquals(3, feed.size)
        assertEquals(publicReel, feed[0].facebookUrl)
        assertEquals(video, feed[1].youtubeId)
        assertTrue(feed[2].videoUrl.endsWith("one.mp4"))
    }

    @Test fun ordinaryPostsAreLeftUntouched() {
        val ordinary = "منشور عادي بدون فيديو"
        assertNull(youtubePostId(ordinary))
        assertEquals(ordinary, visibleYoutubePostText(ordinary))
    }
}
