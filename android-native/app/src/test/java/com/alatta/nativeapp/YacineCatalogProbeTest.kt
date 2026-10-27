package com.alatta.nativeapp

import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YacineCatalogProbeTest {
    private val path = "categories/1/channels"
    @Test fun onlyFixedHttpsCatalogPathsAreBuiltWithoutAccountCredentials() {
        val root = YacineCatalogProbe.request(YacineCatalogProbe.Kind.CATEGORIES)
        assertEquals("https://def001.ycnapi.com/api/categories", root.url.toString())
        assertEquals("GET", root.method)
        assertEquals(listOf("Accept"), root.headers.names().toList())
        assertNull(root.body)
        assertEquals("https://def001.ycnapi.com/api/categories/41/channels",
            YacineCatalogProbe.request(YacineCatalogProbe.Kind.CHANNELS, "٠٠٤١").url.toString())
        assertEquals("https://def001.ycnapi.com/api/categories/41",
            YacineCatalogProbe.request(YacineCatalogProbe.Kind.CATEGORIES, "41").url.toString())
        for (bad in listOf("https://example.org", "../config", "0", "-1", "1?token=x", "9223372036854775808"))
            assertThrows(IllegalArgumentException::class.java) {
                YacineCatalogProbe.request(YacineCatalogProbe.Kind.CHANNELS, bad)
            }
    }
    @Test fun successfulListsKeepLongIdsExactAndRecognizeAnExplicitEmptyList() {
        val result = YacineCatalogProbe.parse("""{"data":[{"id":6946592526586825078,"name":"beIN SPORTS 1"},{"id":"9223372036854775807","name":"قناة أخرى"}]}""", 200, path)
        assertEquals(YacineCatalogProbe.State.LIST, result.state)
        assertEquals(listOf("6946592526586825078", "9223372036854775807"), result.entries.map { it.id })
        assertEquals(YacineCatalogProbe.State.EMPTY, YacineCatalogProbe.parse("""{"data":[]}""", 200, path).state)
    }
    @Test fun errorsHtmlEncodedBodiesAndUnexpectedSchemasNeverBecomeSuccessfulLists() {
        assertEquals(YacineCatalogProbe.State.HTTP_ERROR, YacineCatalogProbe.parse("""{"data":[]}""", 403, path).state)
        assertEquals(YacineCatalogProbe.State.HTTP_ERROR, YacineCatalogProbe.parse("", 302, path).state)
        for (text in listOf("<html>Access denied</html>", "ZW5jb2RlZC1yZXNwb25zZQ=="))
            assertNotEquals(YacineCatalogProbe.State.LIST, YacineCatalogProbe.parse(text, 200, path).state)
        for (text in listOf("{}", """{"success":false,"data":[]}""", """{"error":"denied","data":[]}""", """{"data":[]} trailing"""))
            assertEquals(YacineCatalogProbe.State.INVALID, YacineCatalogProbe.parse(text, 200, path).state)
    }
    @Test fun malformedAndDuplicateRecordsAreRejectedWithoutPartialResults() {
        for (text in listOf(
            """{"data":[{"id":1.0,"name":"bad"}]}""",
            """{"data":[{"id":9223372036854775808,"name":"overflow"}]}""",
            """{"data":[{"id":1,"name":"one"},{"id":"01","name":"duplicate"}]}""",
            """{"data":[{"id":1,"name":""}]}""",
            """{"data":[{"id":1,"name":"one"},null]}"""
        )) {
            val result = YacineCatalogProbe.parse(text, 200, path)
            assertEquals(text, YacineCatalogProbe.State.INVALID, result.state)
            assertTrue(result.entries.isEmpty())
        }
    }
    @Test fun responseReadIsBoundedAndRedirectsAreNotTreatedAsData() {
        fun response(code: Int, body: String) = Response.Builder()
            .request(YacineCatalogProbe.request(YacineCatalogProbe.Kind.CATEGORIES))
            .protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("t", "1790000000").body(body.toResponseBody()).build()
        response(200, "x".repeat(YacineCatalogProbe.MAX_BYTES + 1)).use {
            assertEquals(YacineCatalogProbe.State.TOO_LARGE, YacineCatalogProbe.read(it, path, 15).state)
        }
        response(200, """{"data":[]}""").use {
            val result = YacineCatalogProbe.read(it, path, 15)
            assertEquals(YacineCatalogProbe.State.EMPTY, result.state)
            assertEquals("application/json", result.contentType)
            assertTrue(result.hasTimeHeader)
        }
        response(302, """{"data":[]}""").use {
            assertEquals(YacineCatalogProbe.State.HTTP_ERROR, YacineCatalogProbe.read(it, path, 15).state)
        }
    }

    @Test fun serviceErrorsExposeOnlyABoundedMessageAndNeverBecomeCatalogData() {
        fun response(type: String, body: String) = Response.Builder()
            .request(YacineCatalogProbe.request(YacineCatalogProbe.Kind.CATEGORIES))
            .protocol(Protocol.HTTP_1_1).code(436).message("test")
            .header("Content-Type", type).body(body.toResponseBody()).build()
        response("application/json", """{"message":"Catalog unavailable","data":[{"id":1,"name":"not a catalog"}]}""").use {
            val result = YacineCatalogProbe.read(it, path, 1)
            assertEquals(YacineCatalogProbe.State.HTTP_ERROR, result.state)
            assertEquals("Catalog unavailable", result.serverMessage)
            assertTrue(result.entries.isEmpty())
            assertTrue(result.bytes > 0)
        }
        response("text/plain", "token=private-value https://example.invalid/?key=secret " + "x".repeat(9000)).use {
            val result = YacineCatalogProbe.read(it, path, 1)
            assertEquals(4096L, result.bytes)
            assertTrue(result.serverMessage.length <= 360)
            assertFalse(result.serverMessage.contains("private-value"))
            assertFalse(result.serverMessage.contains("example.invalid"))
        }
        response("text/html", "<html>not catalog data</html>").use {
            assertTrue(YacineCatalogProbe.read(it, path, 1).serverMessage.isEmpty())
        }
    }
}
