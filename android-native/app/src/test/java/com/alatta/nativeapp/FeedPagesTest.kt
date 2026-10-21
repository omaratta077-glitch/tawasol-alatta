package com.alatta.nativeapp

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FeedPagesTest {
    @Test fun accountStillAppearsWhenPostsFailAndPostsStillAppearWhenAccountFails() = runBlocking {
        var accountShown = false
        try {
            loadAccountScreen({ path ->
                if (path == "/api/me") JSONObject().put("user", JSONObject().put("fullName", "Omar")) else throw IOException("Connection reset")
            }, "omar", { accountShown = it.getString("fullName") == "Omar" }, { fail("No fabricated posts") })
            fail()
        } catch (_: IOException) { }
        assertTrue(accountShown)
        var postsShown = false
        try {
            loadAccountScreen({ path ->
                if (path == "/api/me") throw IOException("Connection reset")
                JSONObject().put("paged", true).put("posts", JSONArray().put(JSONObject().put("id", "p1").put("author", "omar")))
            }, "omar", { fail() }, { postsShown = it.posts.size == 1 })
            fail()
        } catch (_: IOException) { }
        assertTrue(postsShown)
    }
    @Test fun nextPagesAreMergedWithoutDroppingOlderPostsOrDuplicatingIds() = runBlocking {
        val first = FeedPages.fetch({ path ->
            assertTrue(path.contains("limit=10") && path.contains("page=1"))
            JSONObject().put("paged", true).put("nextCursor", "abc.10").put("posts", JSONArray().put(JSONObject().put("id", "one")))
        })
        val next = FeedPages.fetch({ path ->
            assertTrue(path.contains("before=abc.10"))
            JSONObject().put("paged", true).put("nextCursor", JSONObject.NULL).put("posts", JSONArray()
                .put(JSONObject().put("id", "one")).put(JSONObject().put("id", "two")))
        }, first.nextCursor)
        assertEquals(listOf("one", "two"), FeedPages.merge(first.posts, next).map { it.getString("id") })
        assertNull(next.nextCursor)
    }
    @Test fun oldBackendFallbackCanLoadMoreAndRetainsThePreviousOwnPostWindow() = runBlocking {
        val first = FeedPages.fetch({ JSONObject().put("posts", JSONArray((1..10).map { JSONObject().put("id", "$it") })) })
        assertEquals("legacy:20", first.nextCursor)
        val calls = mutableListOf<String>()
        val own = FeedPages.fetch({ path ->
            calls += path
            JSONObject().put("posts", JSONArray().put(JSONObject().put("id", "own").put("author", "omar"))
                .put(JSONObject().put("id", "other").put("author", "alice")))
        }, author = "omar")
        assertEquals(2, calls.size)
        assertEquals("/api/posts?limit=50", calls.last())
        assertEquals(listOf("own"), own.posts.map { it.getString("id") })
    }
}
