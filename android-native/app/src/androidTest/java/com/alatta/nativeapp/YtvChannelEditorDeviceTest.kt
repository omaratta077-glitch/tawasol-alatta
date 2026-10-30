package com.alatta.nativeapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** اختبر نموذج الإدارة الفعلي بلا خادم أو مشغّل خارجي مثبّت. */
@RunWith(AndroidJUnit4::class)
class YtvChannelEditorDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Composable private fun Editor(onSave: (JSONObject) -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, 1.5f), LocalLayoutDirection provides LayoutDirection.Rtl) {
            MaterialTheme { FootballChannelEditor(null, false, "", onDismiss = {}, onSave = onSave) }
        }
    }

    @Test fun ytvSelectionSavesTheExactIdAndSharedCatalogMetadata() {
        val saved = AtomicReference<JSONObject>()
        compose.setContent { Editor { saved.set(it) } }
        compose.onNodeWithText("مشغّل القنوات").performScrollTo().performClick()
        compose.onNodeWithText("اسم القناة").performScrollTo().performTextInput("قناة الاختبار")
        compose.onNodeWithText("رقم القناة").performScrollTo().performTextInput("٤٢٩٤٩٦٧٢٩٦")
        compose.onAllNodesWithText("رابط البث أو المشاهدة HTTPS").assertCountEquals(0)
        compose.onNodeWithText("حفظ").performClick()
        compose.runOnIdle {
            val channel = requireNotNull(saved.get())
            assertEquals("ytv", channel.getString("format"))
            assertEquals("4294967296", channel.getString("streamUrl"))
            assertEquals("beIN SPORTS", channel.getString("provider"))
            assertEquals(YtvPlayer.APP_PAGE, channel.getString("sourceUrl"))
            assertTrue(channel.getBoolean("enabled"))
        }
    }

    @Test fun invalidIdIsNotSavedAndCanBeCorrectedWithoutLosingTheForm() {
        val saved = AtomicReference<JSONObject>()
        compose.setContent { Editor { saved.set(it) } }
        compose.onNodeWithText("مشغّل القنوات").performScrollTo().performClick()
        compose.onNodeWithText("اسم القناة").performScrollTo().performTextInput("قناة التصحيح")
        compose.onNodeWithText("رقم القناة").performScrollTo().performTextInput("12345678901234567890")
        compose.onNodeWithText("حفظ").performClick()
        compose.onNodeWithText("أدخل رقم قناة صحيحًا").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertNull(saved.get()) }
        compose.onNodeWithText("رقم القناة").performScrollTo().performTextReplacement("41")
        compose.onNodeWithText("حفظ").performClick()
        compose.runOnIdle {
            val channel = requireNotNull(saved.get())
            assertEquals("41", channel.getString("streamUrl"))
            assertEquals("قناة التصحيح", channel.getString("name"))
        }
    }
}
