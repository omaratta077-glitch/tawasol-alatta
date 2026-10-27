package com.alatta.nativeapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FootballScreenDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun channelsOpenFirstInRtlAtLargeFontAndKeepPlayFavoritesAndAllTwentyCardsAccessible() {
        val channels = (1..20).map { n -> FootballChannel("synthetic-$n", "beIN SPORTS $n", "beIN SPORTS (1080P)",
            "YTV PRO", YtvPlayer.APP_PAGE, (9000 + n).toString(), "ytv", true) }
        var played = ""
        compose.setContent {
            val density = LocalDensity.current.density
            var favorites by remember { mutableStateOf(emptySet<String>()) }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalDensity provides Density(density, 1.5f)) {
                MaterialTheme { FootballContent(FootballCatalog(1, channels.reversed()), "", favorites, emptyList(), false, "", false, false,
                    onReload = {}, onManage = {}, onPlay = { played = it.id }, onFavorite = { id ->
                        favorites = if (id in favorites) favorites - id else favorites + id
                    }) }
            }
        }
        compose.onAllNodesWithText("YTV", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("اختَر القناة", substring = true).assertCountEquals(0)
        compose.onNodeWithText("اليوم").assertDoesNotExist()
        val firstTab = compose.onNodeWithText("القنوات").fetchSemanticsNode().boundsInRoot
        val nextTab = compose.onNodeWithText("المباريات").fetchSemanticsNode().boundsInRoot
        assertTrue(firstTab.left >= nextTab.right)
        compose.onNodeWithContentDescription("تشغيل beIN SPORTS 1").performClick()
        compose.runOnIdle { assertEquals("synthetic-1", played) }
        compose.onNodeWithContentDescription("إضافة beIN SPORTS 1 للمفضلة").performClick()
        compose.onNodeWithText("المفضلة").performScrollTo().performClick()
        compose.onNodeWithContentDescription("تشغيل beIN SPORTS 1").assertIsDisplayed()
        compose.onNodeWithText("القنوات").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(19)
        compose.onNodeWithContentDescription("تشغيل beIN SPORTS 20").assertIsDisplayed()
        compose.onNodeWithText("المباريات").performScrollTo().performClick()
        compose.onNodeWithText("اليوم").assertIsDisplayed()
    }
}
