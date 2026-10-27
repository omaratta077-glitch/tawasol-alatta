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
    private val fixtureMask = "test-format-mask".toByteArray(Charsets.US_ASCII)
    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
        .bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
    private fun encodedResponse(body: String, time: String? = "1700000000", code: Int = 200): Response {
        val builder = Response.Builder().request(YacineCatalogProbe.request(YacineCatalogProbe.Kind.CATEGORIES))
            .protocol(Protocol.HTTP_1_1).code(code).message("synthetic fixture")
            .header("Content-Type", "application/json").body(body.toResponseBody())
        if (time != null) builder.header("t", time)
        return builder.build()
    }

    @Test fun syntheticResponseDecodesCategoriesWithOnlyExplicitLocalConfiguration() {
        val body = fixture("catalog-categories-synthetic.base64")
        encodedResponse(body).use {
            val result = YacineCatalogProbe.read(it, "categories", 1, responseMask = fixtureMask)
            assertEquals(YacineCatalogProbe.State.LIST, result.state)
            assertTrue(result.responseDecoded)
            assertEquals(1208L, result.bytes)
            assertEquals((101..113).map { id -> id.toString() }, result.entries.map { row -> row.id })
            assertEquals("قسم تجريبي 1", result.entries.first().name)
            assertEquals(20L, result.entries.first { row -> row.id == "107" }.childCount ?: -1L)
            assertEquals(0L, result.entries.first().childCount ?: -1L)
            assertEquals("1700000000", result.timeHeader)
            assertTrue(result.deviceTimeUsed.isEmpty())
            assertEquals(body, result.responseText)
            assertTrue(result.report().contains("اسم القسم | رقم القسم"))
            assertFalse(result.report().contains("test-format-mask"))
        }
        encodedResponse(body).use {
            val result = YacineCatalogProbe.read(it, "categories", 1)
            assertEquals(YacineCatalogProbe.State.UNREADABLE, result.state)
            assertTrue(result.entries.isEmpty())
            assertFalse(result.responseDecoded)
        }
    }

    @Test fun encodedChannelFixtureUsesItsFreshTimestampAndPreservesArabicAndLongIds() {
        val body = fixture("catalog-channels-synthetic.base64")
        encodedResponse(body, "1700000001").use {
            val result = YacineCatalogProbe.read(it, path, 1, responseMask = fixtureMask)
            assertEquals(YacineCatalogProbe.State.LIST, result.state)
            assertTrue(result.responseDecoded)
            assertEquals(listOf("9007199254740993", "9223372036854775807"), result.entries.map { row -> row.id })
            assertEquals("قناة تجريبية", result.entries.last().name)
            assertEquals("1700000001", result.timeHeader)
            assertTrue(result.report().contains("اسم القناة | رقم القناة"))
            assertFalse(result.report().contains("test-format-mask"))
        }
    }

    @Test fun absentTimeUsesOneRecordedDeviceTimestampAndNeverFabricatesAResponseHeader() {
        encodedResponse(fixture("catalog-channels-synthetic.base64"), null).use {
            val result = YacineCatalogProbe.read(it, path, 1, nowEpochSeconds = 1700000001L, responseMask = fixtureMask)
            assertEquals(YacineCatalogProbe.State.LIST, result.state)
            assertTrue(result.responseDecoded)
            assertFalse(result.hasTimeHeader)
            assertTrue(result.timeHeader.isEmpty())
            assertEquals("1700000001", result.deviceTimeUsed)
            assertFalse(result.report().contains("\nt: "))
        }
        encodedResponse(fixture("catalog-channels-synthetic.base64"), "invalid-time").use {
            val result = YacineCatalogProbe.read(it, path, 1, nowEpochSeconds = 1700000001L, responseMask = fixtureMask)
            assertEquals(YacineCatalogProbe.State.UNREADABLE, result.state)
            assertTrue(result.entries.isEmpty())
            assertTrue(result.deviceTimeUsed.isEmpty())
        }
    }

    @Test fun corruptedEncodedBodiesWrongTimeAndHttpErrorsCannotBecomeCatalogs() {
        val body = fixture("catalog-categories-synthetic.base64")
        encodedResponse(body, "0000000000").use {
            val result = YacineCatalogProbe.read(it, "categories", 1, responseMask = fixtureMask)
            assertNotEquals(YacineCatalogProbe.State.LIST, result.state)
            assertNotEquals(YacineCatalogProbe.State.EMPTY, result.state)
            assertTrue(result.entries.isEmpty())
        }
        val corrupted = java.util.Base64.getDecoder().decode(body)
        corrupted[0] = (corrupted[0].toInt() xor 0x80).toByte()
        for (bad in listOf(body + "!", java.util.Base64.getEncoder().encodeToString(corrupted))) encodedResponse(bad).use {
            val result = YacineCatalogProbe.read(it, "categories", 1, responseMask = fixtureMask)
            assertEquals(YacineCatalogProbe.State.UNREADABLE, result.state)
            assertFalse(result.responseDecoded)
            assertTrue(result.entries.isEmpty())
        }
        encodedResponse(body, code = 403).use {
            val result = YacineCatalogProbe.read(it, "categories", 1, responseMask = fixtureMask)
            assertEquals(YacineCatalogProbe.State.HTTP_ERROR, result.state)
            assertFalse(result.responseDecoded)
            assertTrue(result.entries.isEmpty())
            assertTrue(result.responseText.isEmpty())
        }
    }

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

    @Test fun copyingKeepsTheBodyAndTimestampFromTheSameResponseWithoutOtherHeaders() {
        fun response(body: String, time: String) = Response.Builder()
            .request(YacineCatalogProbe.request(YacineCatalogProbe.Kind.CATEGORIES))
            .protocol(Protocol.HTTP_1_1).code(200).message("test")
            .header("Content-Type", "application/json")
            .header("t", time).header("Set-Cookie", "session=private-cookie")
            .header("Authorization", "Bearer private-credential")
            .body(body.toResponseBody()).build()
        val firstBody = "ZW5jb2RlZC1maXJzdA=="
        val secondBody = "ZW5jb2RlZC1zZWNvbmQ="
        val first = response(firstBody, "1790000000").use { YacineCatalogProbe.read(it, path, 2) }
        val second = response(secondBody, "1790000001").use { YacineCatalogProbe.read(it, path, 3) }
        assertEquals(firstBody, first.responseText)
        assertEquals("1790000000", first.timeHeader)
        assertNotEquals(YacineCatalogProbe.State.LIST, first.state)
        assertEquals(secondBody, second.responseText)
        val report = second.report()
        assertTrue(report.contains("t: 1790000001"))
        assertTrue(report.contains("--- BEGIN RESPONSE BODY ---\n$secondBody\n--- END RESPONSE BODY ---"))
        for (absent in listOf(firstBody, "1790000000", "private-cookie", "private-credential", "Authorization", "Set-Cookie"))
            assertFalse(absent, report.contains(absent))
        assertFalse(second.responseTextTruncated)
    }

    @Test fun copyingBoundsSamplesAndDoesNotExportRawHttpErrorsOrOversizedBodies() {
        fun response(code: Int, body: String) = Response.Builder()
            .request(YacineCatalogProbe.request(YacineCatalogProbe.Kind.CATEGORIES))
            .protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Content-Type", "text/plain").header("t", "1790000000")
            .body(body.toResponseBody()).build()
        val body = "A".repeat(YacineCatalogProbe.MAX_REPORT_CHARS) + "not-in-the-report"
        response(200, body).use {
            val result = YacineCatalogProbe.read(it, path, 1)
            assertEquals(body.length.toLong(), result.bytes)
            assertEquals(YacineCatalogProbe.MAX_REPORT_CHARS, result.responseText.length)
            assertTrue(result.responseTextTruncated)
            assertTrue(result.report().contains("مقتطف غير مكتمل"))
            assertFalse(result.report().contains("not-in-the-report"))
        }
        response(403, "token=private-error-value").use {
            val result = YacineCatalogProbe.read(it, path, 1)
            assertEquals(YacineCatalogProbe.State.HTTP_ERROR, result.state)
            assertTrue(result.responseText.isEmpty())
            assertFalse(result.report().contains("private-error-value"))
            assertFalse(result.report().contains("BEGIN RESPONSE BODY"))
        }
        response(200, "x".repeat(YacineCatalogProbe.MAX_BYTES + 1)).use {
            val result = YacineCatalogProbe.read(it, path, 1)
            assertEquals(YacineCatalogProbe.State.TOO_LARGE, result.state)
            assertTrue(result.responseText.isEmpty())
        }
    }

    @Test fun missingOrUnexpectedTimeHeadersAreNotInventedOrCopiedAsCredentials() {
        for (time in listOf<String?>(null, "Bearer private-time-value", "123", "17900000000000")) {
            val builder = Response.Builder()
                .request(YacineCatalogProbe.request(YacineCatalogProbe.Kind.CATEGORIES))
                .protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body("{}".toResponseBody())
            if (time != null) builder.header("t", time)
            builder.build().use {
                val result = YacineCatalogProbe.read(it, path, 1)
                assertEquals(time != null, result.hasTimeHeader)
                assertTrue(result.timeHeader.isEmpty())
                assertFalse(result.report().contains("private-time-value"))
                assertFalse(result.report().contains("\nt: "))
            }
        }
    }
}
