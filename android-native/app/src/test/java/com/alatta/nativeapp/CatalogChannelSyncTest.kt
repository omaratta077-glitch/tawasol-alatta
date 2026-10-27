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
        val batches = mutableListOf<List<FootballChannel>>()
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
            rememberLinks = { links = it; remembered.add(it.toList()); true },
            saveBatch = { current, rows ->
                batches.add(rows)
                if (failSaveAt != 0) error("409 conflict")
                check(current.revision == catalog.revision)
                val next = current.channels.toMutableList()
                rows.forEachIndexed { index, row ->
                    if (row.id.isBlank()) next.add(row.copy(id = "generated-$index"))
                    else next[next.indexOfFirst { it.id == row.id }] = row
                }
                require(next.map { it.streamUrl }.distinct().size == next.size)
                catalog = current.copy(revision = current.revision + 1, channels = next)
                catalog
            }
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

    @Test fun fullSectionAutomaticallyAdoptsExistingNamesAndImportsAllTwentyWithoutDuplicates() = runBlocking {
        val section = YacineCatalogProbe.Entry("101", "Test Sports (1080P)")
        val originals = listOf(channel().copy(name = "Test Sports1", category = section.name),
            channel("two", "9002").copy(name = "Test Sports 2", category = section.name))
        val f = Fake(FootballCatalog(7, originals), emptyList())
        f.reply = { list(*(1..20).map { "Test Sports $it" to (9100 + it).toString() }.toTypedArray()) }
        val first = f.sync.refreshSection(f.links) { section }
        assertEquals(20, first.checked)
        assertEquals(20, f.catalog.channels.size)
        assertEquals(listOf("saved-one", "two"), f.catalog.channels.take(2).map { it.id })
        assertEquals(originals[0].name, f.catalog.channels[0].name)
        assertEquals(20, f.links.size)
        assertEquals(1, f.batches.size)
        val again = f.sync.refreshSection(f.links) { section }
        assertEquals(0, again.changed)
        assertEquals(20, f.catalog.channels.size)
        assertEquals(1, f.batches.size)
    }

    @Test fun fullSectionCanRotateIdsInOneAtomicWriteAndRetainsDisabledChannelsAndMatches() = runBlocking {
        val section = YacineCatalogProbe.Entry("101", "Test Sports (1080P)")
        val a = channel().copy(name = "Test Sports 1", category = section.name)
        val b = channel("two", "9002").copy(name = "Test Sports 2", category = section.name)
        val match = FootballMatch("match", "Home", "Away", "Test", 1700000000000L, "scheduled", a.id, null, null)
        val f = Fake(FootballCatalog(4, listOf(a, b), listOf(match)), emptyList())
        f.reply = { list("Test Sports 1" to "9002", "Test Sports 2" to "9001") }
        assertEquals(2, f.sync.refreshSection(f.links) { section }.changed)
        assertEquals(listOf("9002", "9001"), f.catalog.channels.map { it.streamUrl })
        assertEquals(listOf(match), f.catalog.matches)
        assertEquals(5L, f.catalog.revision)
    }

    @Test fun invalidFullSectionOrInsufficientCapacityCannotImportAPartialList() = runBlocking {
        val section = YacineCatalogProbe.Entry("101", "Test Sports (1080P)")
        val f = Fake(FootballCatalog(1, listOf(channel())), emptyList())
        for (response in listOf(list("Test Sports 1" to "9101", "Test Sports1" to "9102"),
            YacineCatalogProbe.Result(YacineCatalogProbe.State.HTTP_ERROR, "", "failure"))) {
            f.reply = { response }; f.sync.refreshSection(f.links) { section }
            assertTrue(f.batches.isEmpty()); assertEquals(1, f.catalog.channels.size)
        }
        f.catalog = f.catalog.copy(channels = (1..40).map { channel("old-$it", (9500 + it).toString()) })
        f.reply = { list("New Sports" to "9201") }
        f.sync.refreshSection(f.links) { section }
        assertTrue(f.batches.isEmpty()); assertEquals(40, f.catalog.channels.size)
    }

    @Test fun fullSectionPreservesManualOverridesAndDisabledSavedRecords() = runBlocking {
        val section = YacineCatalogProbe.Entry("101", "Test Sports (1080P)")
        val a = channel(stream = "9901").copy(name = "Test Sports 1", category = section.name)
        val b = channel("two", "9002", enabled = false).copy(name = "Test Sports 2", category = section.name)
        val f = Fake(FootballCatalog(2, listOf(a, b)), listOf(link()))
        f.reply = { list("Test Sports 1" to "9101", "Test Sports 2" to "9102") }
        f.sync.refreshSection(f.links) { section }
        assertEquals(listOf(a, b), f.catalog.channels)
        assertEquals("9001", f.links.first { it.channelId == a.id }.lastPublishedId)
        assertTrue(f.batches.isEmpty())
    }

    @Test fun automaticSectionDiscoveryUsesNamesAndCurrentIdsRatherThanAnEmbeddedCategoryNumber() {
        val root = list("beIN SPORTS (720P)" to "107", "beIN SPORTS (1080P)" to "109")
        assertEquals("109", catalogDefaultSection(root)?.id)
        assertNull(catalogDefaultSection(root.copy(state = YacineCatalogProbe.State.HTTP_ERROR)))
        assertNull(catalogDefaultSection(list("beIN SPORTS (1080P)" to "109", "beIN SPORTS 1080" to "110")))
    }

    @Test fun automaticImportChecksOwnerFirstAndNeverWritesPartOfAConflictingBatch() = runBlocking {
        val f = Fake(FootballCatalog(1, listOf(channel())), emptyList())
        f.denied = true
        var discovered = false
        f.sync.refreshSection(f.links) { discovered = true; YacineCatalogProbe.Entry("101", "Test Sports (1080P)") }
        assertFalse(discovered); assertTrue(f.requests.isEmpty()); assertTrue(f.batches.isEmpty())
        f.denied = false; f.failSaveAt = 1
        f.reply = { list("New Sports 1" to "9101", "New Sports 2" to "9102") }
        f.sync.refreshSection(f.links) { YacineCatalogProbe.Entry("101", "Test Sports (1080P)") }
        assertEquals(1, f.catalog.channels.size); assertEquals(1L, f.catalog.revision)
        assertTrue(f.links.isEmpty()); assertTrue(f.remembered.isEmpty())
    }
}
