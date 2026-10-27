package com.alatta.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.Assert.*

class CatalogLinkStoreDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @After fun cleanup() {
        CatalogLinkStore.write(context, "test-owner-a", emptyList())
        CatalogLinkStore.write(context, "test-owner-b", emptyList())
    }
    @Test fun linksPersistExactlyRemainAccountScopedAndCanBeRemovedWithoutAffectingOtherOwners() {
        val link = CatalogChannelLink("saved-test", "101", "قناة تجريبية", "9223372036854775807")
        assertTrue(CatalogLinkStore.write(context, "test-owner-a", listOf(link)))
        assertTrue(CatalogLinkStore.write(context, "test-owner-b", emptyList()))
        assertEquals(listOf(link), CatalogLinkStore.read(context, "test-owner-a"))
        assertTrue(CatalogLinkStore.read(context, "test-owner-b").isEmpty())
        assertTrue(CatalogLinkStore.write(context, "test-owner-b", listOf(link.copy(channelId = "other-test"))))
        assertTrue(CatalogLinkStore.write(context, "test-owner-a", emptyList()))
        assertTrue(CatalogLinkStore.read(context, "test-owner-a").isEmpty())
        assertEquals("other-test", CatalogLinkStore.read(context, "test-owner-b").single().channelId)
    }
    @Test fun corruptedOrDuplicateLinksAreNotUsed() {
        val prefs = context.getSharedPreferences("alatta_catalog_links_v1", Context.MODE_PRIVATE)
        for (value in listOf("broken", """[{"channel":"x","category":"101","name":"test","lastId":"0"}]""",
            """[{"channel":"x","category":"101","name":"test","lastId":"9001"},{"channel":"x","category":"101","name":"test","lastId":"9002"}]""")) {
            prefs.edit().putString("test-owner-a", value).commit()
            assertTrue(CatalogLinkStore.read(context, "test-owner-a").isEmpty())
        }
        assertFalse(CatalogLinkStore.write(context, "", emptyList()))
    }
}
