package com.alatta.nativeapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No live provider requests: exercise the real panel in the small area left below Sports controls. */
@RunWith(AndroidJUnit4::class)
class YacineProviderDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Composable private fun SmallPhone(content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, 1.8f), LocalLayoutDirection provides LayoutDirection.Rtl) {
            MaterialTheme { Box(Modifier.size(width = 320.dp, height = 180.dp)) { content() } }
        }
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithTag("yacine-provider-list").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun waitForResult() {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("تحديث") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun loadingFailureRetryAndLongCatalogRemainReachableWithLargeArabicText() {
        val paths = CopyOnWriteArrayList<String>()
        val attempts = AtomicInteger()
        val gate = CountDownLatch(1)
        val explanation = YacineFailure(YacineProblem.HTTP, YacineStage.CATEGORIES, 503).explanation
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            paths += path
            val response = when (path) {
                "/api/categories" -> if (attempts.incrementAndGet() == 1) {
                    check(gate.await(15, TimeUnit.SECONDS)) { "Loading state was not released by the test" }
                    503 to "{}"
                } else 200 to (1..30).joinToString(prefix = "{\"data\":[", postfix = "]}") { "{\"id\":$it,\"name\":\"قسم $it\"}" }
                "/api/categories/30/channels" -> 200 to """{"data":[{"id":41,"name":"قناة الاختبار"}]}"""
                else -> error("Unexpected request: $path")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(response.first).message("Fixture")
                .body(response.second.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val repository = YacineRepository(client = client)
            compose.setContent { SmallPhone { YacineProviderPanel("", Modifier.fillMaxSize(), onBeforePlayback = {}, repository = repository) } }
            compose.waitForIdle()
            assertEquals("Opening the page alone does not contact the provider", 0, paths.size)
            scrollTo("تحميل قنوات ياسين")
            compose.onNodeWithText("تحميل قنوات ياسين").performClick()
            compose.waitUntil(5_000) { paths.size == 1 }
            scrollTo("جارٍ الاتصال بمصدر القنوات…")
            gate.countDown()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("تعذر إكمال الربط مع مصدر ياسين").fetchSemanticsNodes().isNotEmpty() }
            scrollTo(explanation)
            scrollTo("إعادة المحاولة")
            compose.onNodeWithText("إعادة المحاولة").performClick()
            compose.waitUntil(5_000) { attempts.get() == 2 }
            waitForResult()
            scrollTo("قسم 30")
            compose.onNodeWithText("قسم 30").performClick()
            compose.waitUntil(5_000) { paths.contains("/api/categories/30/channels") }
            waitForResult()
            scrollTo("قناة الاختبار")
            scrollTo("رجوع للأقسام")
            compose.onNodeWithText("رجوع للأقسام").performClick()
            compose.waitUntil(5_000) { attempts.get() == 3 }
            waitForResult()
            scrollTo("قسم 1")
        } finally {
            gate.countDown()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun sportsRefreshStartsProviderRequestAndCanRefreshAnEmptyResult() {
        val refresh = mutableIntStateOf(0)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("{\"data\":[]}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val repository = YacineRepository(client = client)
            compose.setContent { SmallPhone {
                YacineProviderPanel("", Modifier.fillMaxSize(), refreshRequest = refresh.intValue, onBeforePlayback = {}, repository = repository)
            } }
            compose.waitForIdle()
            assertEquals(0, requests.get())
            compose.runOnIdle { refresh.intValue++ }
            compose.waitUntil(5_000) { requests.get() == 1 }
            waitForResult()
            val empty = "لم يرجع المصدر قنوات أو أقسامًا مطابقة حاليًا."
            scrollTo(empty)
            compose.runOnIdle { refresh.intValue++ }
            compose.waitUntil(5_000) { requests.get() == 2 }
            waitForResult()
            scrollTo(empty)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
