package com.alatta.nativeapp

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttaLanguageTest {
    @Test fun languageCanBeChangedAndPersists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            AttaLanguage.choose(context, "en")
            assertEquals("en", AttaLanguage.code)
            assertEquals("Home", AttaLanguage.text("الرئيسية"))
            assertEquals("نص آية لا تتم ترجمته", AttaLanguage.text("نص آية لا تتم ترجمته"))
            AttaLanguage.initialize(context)
            assertEquals("en", AttaLanguage.code)
            AttaLanguage.toggle(context)
            assertEquals("ar", AttaLanguage.code)
            assertEquals("الرئيسية", AttaLanguage.text("الرئيسية"))
        } finally {
            AttaLanguage.choose(context, "ar")
        }
    }
}
