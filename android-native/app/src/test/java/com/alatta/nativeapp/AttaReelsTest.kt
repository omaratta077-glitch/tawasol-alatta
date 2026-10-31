package com.alatta.nativeapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AttaReelsTest {
    @Test fun acceptsPublicFacebookReelAndWatchLinksOnly() {
        assertEquals("https://www.facebook.com/reel/123456789",
            attaFacebookReelLink("شاهد https://www.facebook.com/reel/123456789/?utm_source=share"))
        assertEquals("https://www.facebook.com/watch/?v=123456789",
            attaFacebookReelLink("https://m.facebook.com/watch/?v=123456789&utm_source=test"))
        for (value in listOf("http://www.facebook.com/reel/123456",
            "https://www.facebook.com.evil.example/reel/123456",
            "https://evil.example/reel/123456",
            "https://www.facebook.com/reel/not-a-number",
            "https://user:password@www.facebook.com/reel/123456",
            "https://www.facebook.com:8080/reel/123456")) {
            assertNull(value, attaFacebookReelLink(value))
        }
    }

    @Test fun reelsReuseOnlyOriginalPostsWithoutDuplicates() {
        val withVideo = JSONObject().put("id", "a").put("video", "/media/video.mp4")
        val withLink = JSONObject().put("id", "b").put("text", "https://www.facebook.com/reel/12345678")
        val plain = JSONObject().put("id", "c").put("text", "hello")
        assertEquals(listOf("a", "b"),
            attaReelPosts(listOf(withVideo, withLink, plain, withVideo)).map { it.getString("id") })
    }
}
