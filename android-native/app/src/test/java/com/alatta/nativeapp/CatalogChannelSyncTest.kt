package com.alatta.nativeapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogChannelSyncTest {
    private fun channel(id: String = "saved-one", stream: String = "9001", enabled: Boolean = true) =
        FootballChannel(id, "اسمي المحفوظ", "قسمي", "YTV PRO", YtvPlayer.APP_PAGE, stream, "ytv", enabled)
    private fun link(id: String = "saved-one", source: String = "Test Sports 1", category: String = "101", last: String = "9001") =
        CatalogChannelLink(id, category, source, last)
    private fun list(vararg rows: Pair<String, String>) = YacineCatalogProbe.Result(YacineCatalogProbe.State.LIST,
        "categories/101/channels", "synthetic", entries = rows.map { YacineCatalogProbe.Entry(it.second, it.first) })
    private class Fake(var catalog: FootballCatalog, var links: List<CatalogChannelLink>) {
        val requests = mutableListOf<String>()
        val writes = mutableListOf<Pair<Long, FootballChannel>>()
        val remembered = mutableListOf<List<CatalogChannelLink>>()
        var reply: (String) -> YacineCatalogProbe.Result = { error("Unexpected request") }
        var failSaveAt = 0
        var denied = false
        var cancel = false
        val sync get() = CatalogChannelSync(
            load = { if (denied) error("403"); catalog },
            fetch = { requests.add(it); if (cancel) throw CancellationException(); reply(it) },
            save = { current, next ->
                writes.add(current.revision to next)
                if (failSaveAt == writes.size) error("409 conflict")
                check(current.revision == catalog.revision)
                catalog = current.copy(revision = current.revision + 1,
                    channels = current.channels.map { if (it.id == next.id) next else it })
                catalog
            },
            rememberLinks = { links = it; remembered.add(it.toList()); true }
        )
    }

    @Test fun bindUpdatesOnlyTheChosenExistingChannelAndKeepsMetadata() = runBlocking {
        val original = channel()
        val unrelated = channel("other", "9901").copy(format = "external", streamUrl = "https://publisher.example/watch")
        val f = Fake(FootballCatalog(7, listOf(original, unrelated)), emptyList())
        f.reply = { list("Test Sports 1" to "9007199254740993", "Other Sports" to "9003") }
        val out = f.sync.bind(f.links, original.id, "101", YacineCatalogProbe.Entry("9007199254740993", "Test Sports 1"))
        assertTrue(out.linked)
        assertEquals(1, out.changed)
        assertEquals(listOf(original.copy(streamUrl = "9007199254740993"), unrelated), f.catalog.channels)
        assertEquals(listOf(link(last = "9007199254740993")), f.links)
        assertEquals(7L, f.writes.single().first)
    }

    @Test fun bindingRejectsChangedPreviewAmbiguityMissingChannelsAndWrongFormat() = runBlocking {
        val f = Fake(FootballCatalog(1, listOf(channel())), emptyList())
        for (reply in listOf(list("Test Sports 1" to "9002"),
            list("Test Sports 1" to "9001", " test  SPORTS 1 " to "9002"), list("Test Sports 10" to "9001"))) {
            f.reply = { reply }
            assertFalse(f.sync.bind(f.links, "saved-one", "101", YacineCatalogProbe.Entry("9001", "Test Sports 1")).linked)
        }
        f.catalog = f.catalog.copy(channels = listOf(channel().copy(format = "hls")))
        assertFalse(f.sync.bind(f.links, "saved-one", "101", YacineCatalogProbe.Entry("9001", "Test Sports 1")).linked)
        assertTrue(f.writes.isEmpty())
        assertTrue(f.links.isEmpty())
    }

    @Test fun unchangedIdOnlyChecksAndNeverWritesOrImportsOtherChannels() = runBlocking {
        val f = Fake(FootballCatalog(1, listOf(channel())), listOf(link()))
        f.reply = { list("Test Sports 1" to "9001", "Test Sports 2" to "9002") }
        val out = f.sync.refresh(f.links)
        assertEquals(1, out.checked)
        assertEquals(0, out.changed)
        assertEquals(1, f.catalog.channels.size)
        assertTrue(f.writes.isEmpty())
        assertTrue(f.remembered.isEmpty())
    }

    @Test fun refreshSharesOneReadPerCategoryAndChainsCurrentRevisions() = runBlocking {
        val match = FootballMatch("match", "Home", "Away", "League", 1700000000000L, "scheduled", "saved-one", null, null)
        val f = Fake(FootballCatalog(10, listOf(channel(), channel("two", "9002")), listOf(match)),
            listOf(link(), link("two", "Test Sports 2", last = "9002")))
        f.reply = { list("TEST  SPORTS 1" to "9101", "Test Sports 2" to "9223372036854775807") }
        val out = f.sync.refresh(f.links)
        assertEquals(2, out.changed)
        assertEquals(listOf("101"), f.requests)
        assertEquals(listOf(10L, 11L), f.writes.map { it.first })
        assertEquals(listOf("9101", "9223372036854775807"), f.catalog.channels.map { it.streamUrl })
        assertEquals(listOf(match), f.catalog.matches)
        assertEquals(listOf("9101", "9223372036854775807"), f.links.map { it.lastPublishedId })
    }

    @Test fun sameNameInDifferentQualityCategoriesNeverCrossesBindings() = runBlocking {
        val f = Fake(FootballCatalog(2, listOf(channel(), channel("two", "9002"))),
            listOf(link(), link("two", category = "102", last = "9002")))
        f.reply = { category -> if (category == "101") list("Test Sports 1" to "9101") else list("Test Sports 1" to "9201") }
        assertEquals(2, f.sync.refresh(f.links).changed)
        assertEquals(listOf("9101", "9201"), f.catalog.channels.map { it.streamUrl })
    }

    @Test fun errorsEmptyMissingAmbiguousAndInvalidIdsKeepTheLastNumber() = runBlocking {
        val replies = listOf(YacineCatalogProbe.State.HTTP_ERROR, YacineCatalogProbe.State.NETWORK_ERROR,
            YacineCatalogProbe.State.INVALID, YacineCatalogProbe.State.UNREADABLE, YacineCatalogProbe.State.EMPTY)
            .map { YacineCatalogProbe.Result(it, "categories/101/channels", "failure") } + listOf(
            list("Test Sports 10" to "9101"),
            list("Test Sports 1" to "9101", "test sports 1" to "9102"),
            list("Test Sports 1" to "9223372036854775808"))
        for (reply in replies) {
            val f = Fake(FootballCatalog(2, listOf(channel())), listOf(link()))
            f.reply = { reply }
            assertEquals(0, f.sync.refresh(f.links).changed)
            assertEquals("9001", f.catalog.channels.single().streamUrl)
            assertEquals("9001", f.links.single().lastPublishedId)
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun manualEditsDisabledDeletedOrNonYtvRecordsNeverTriggerARequestOrWrite() = runBlocking {
        for (channels in listOf(emptyList(), listOf(channel(stream = "9901")), listOf(channel(enabled = false)),
            listOf(channel().copy(format = "external")))) {
            val f = Fake(FootballCatalog(1, channels), listOf(link()))
            f.sync.refresh(f.links)
            assertTrue(f.requests.isEmpty())
            assertTrue(f.writes.isEmpty())
            assertEquals(channels, f.catalog.channels)
        }
    }

    @Test fun conflictStopsWithoutRetryAndKeepsPreviouslyCommittedProgress() = runBlocking {
        val f = Fake(FootballCatalog(1, listOf(channel(), channel("two", "9002"))),
            listOf(link(), link("two", "Test Sports 2", last = "9002")))
        f.reply = { list("Test Sports 1" to "9101", "Test Sports 2" to "9102") }
        f.failSaveAt = 2
        val out = f.sync.refresh(f.links)
        assertEquals(1, out.changed)
        assertEquals(listOf("9101", "9002"), f.links.map { it.lastPublishedId })
        assertEquals(listOf("9101", "9002"), f.catalog.channels.map { it.streamUrl })
        assertEquals(2, f.writes.size)
        assertEquals(1, f.remembered.size)
    }

    @Test fun ownerAuthorizationIsVerifiedBeforeSourceAccessAndCancellationPropagates() = runBlocking {
        val f = Fake(FootballCatalog(1, listOf(channel())), listOf(link()))
        f.denied = true
        assertEquals(0, f.sync.refresh(f.links).changed)
        assertTrue(f.requests.isEmpty())
        f.denied = false; f.cancel = true
        var cancelled = false
        try { f.sync.refresh(f.links) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(f.writes.isEmpty())
    }
}
