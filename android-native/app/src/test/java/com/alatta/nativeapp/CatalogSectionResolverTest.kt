package com.alatta.nativeapp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogSectionResolverTest {
    private fun catalog(vararg entries: Pair<String, String>) =
        YacineCatalogProbe.Result(
            YacineCatalogProbe.State.LIST,
            "categories",
            "synthetic",
            entries = entries.map { (name, id) -> YacineCatalogProbe.Entry(id, name) }
        )

    @Test fun direct1080SectionDoesNotFetchChildren() = runBlocking {
        val result = CatalogSectionResolver.discover(
            catalog("beIN SPORTS (720P)" to "3", "beIN SPORTS (1080P)" to "4")
        ) { error("Never fetch children for an unambiguous direct match") }
        assertEquals("4", result.section?.id)
    }

    @Test fun nestedQualityUnderBeinParentIsFoundWithoutHardcodedId() = runBlocking {
        val calls = mutableListOf<String>()
        val root = catalog("Movies" to "1", "beIN SPORTS" to "7")
        val result = CatalogSectionResolver.discover(root) { category ->
            calls += category
            catalog("beIN SPORTS (720P)" to "71", "1080P" to "72")
        }
        assertEquals("72", result.section?.id)
        assertEquals("beIN SPORTS (1080P)", result.section?.name)
        assertEquals(listOf("7"), calls)
    }

    @Test fun multiplePlausible1080SectionsMustNotBeGuessed() = runBlocking {
        val result = CatalogSectionResolver.discover(catalog("beIN SPORTS" to "7")) {
            catalog("1080P" to "71", "beIN SPORTS 1080P" to "72")
        }
        assertNull(result.section)
        assertTrue(result.explanation.contains("متعددة"))
    }

    @Test fun networkAndUnreadableResponsesShowRealReason() = runBlocking {
        val failure = YacineCatalogProbe.Result(YacineCatalogProbe.State.HTTP_ERROR,
            "categories", "failure", code = 404)
        val result = CatalogSectionResolver.discover(failure) { error("Should not fetch children") }
        assertNull(result.section)
        assertTrue(result.explanation.contains("HTTP 404"))
        val encoded = CatalogSectionResolver.discover(
            YacineCatalogProbe.Result(YacineCatalogProbe.State.UNREADABLE,
                "categories", "وصل رد مرمّز")
        ) { error("Should not fetch children") }
        assertNull(encoded.section)
        assertTrue(encoded.explanation.contains("إعداد قراءة"))
    }

    @Test fun neitherUnrelatedCategoriesNorPartialFailuresGuessASection() = runBlocking {
        val result = CatalogSectionResolver.discover(
            catalog("Movies" to "1", "beIN SPORTS" to "4")
        ) { YacineCatalogProbe.Result(YacineCatalogProbe.State.EMPTY, "categories/4", "empty") }
        assertNull(result.section)
    }
}
