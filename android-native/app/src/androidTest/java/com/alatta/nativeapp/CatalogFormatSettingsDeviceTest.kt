package com.alatta.nativeapp

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class CatalogFormatSettingsDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun prepare() { CatalogFormatSettings.clear(context) }
    @After fun cleanup() { CatalogFormatSettings.clear(context) }

    @Test fun ownerConfigurationRoundTripsThroughDeviceEncryptionAndCanBeRemoved() {
        val synthetic = "test-format-mask"
        assertNull(CatalogFormatSettings.read(context))
        assertFalse(CatalogFormatSettings.configured(context))
        assertTrue(CatalogFormatSettings.save(context, synthetic))
        assertTrue(CatalogFormatSettings.configured(context))
        assertEquals(synthetic, CatalogFormatSettings.read(context))
        val stored = context.getSharedPreferences("alatta_catalog_format_v1", Context.MODE_PRIVATE)
            .getString("encrypted", null)
        assertNotNull(stored)
        assertFalse(stored!!.contains(synthetic))
        assertEquals(44, Base64.decode(stored, Base64.NO_WRAP).size)
        assertTrue(CatalogFormatSettings.save(context, synthetic))
        assertNotEquals(stored, context.getSharedPreferences("alatta_catalog_format_v1", Context.MODE_PRIVATE)
            .getString("encrypted", null))
        assertTrue(CatalogFormatSettings.clear(context))
        assertFalse(CatalogFormatSettings.configured(context))
        assertNull(CatalogFormatSettings.read(context))
    }

    @Test fun invalidOrTamperedConfigurationCannotBeUsed() {
        assertFalse(CatalogFormatSettings.save(context, "short"))
        assertFalse(CatalogFormatSettings.save(context, "أ".repeat(16)))
        assertTrue(CatalogFormatSettings.save(context, "test-format-mask"))
        val prefs = context.getSharedPreferences("alatta_catalog_format_v1", Context.MODE_PRIVATE)
        val payload = Base64.decode(prefs.getString("encrypted", null), Base64.NO_WRAP)
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        prefs.edit().putString("encrypted", Base64.encodeToString(payload, Base64.NO_WRAP)).commit()
        assertNull(CatalogFormatSettings.read(context))
    }
}
