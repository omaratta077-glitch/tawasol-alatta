package com.alatta.nativeapp

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * Shared, device-local UI language. All navigation/API identifiers remain stable Arabic keys.
 * The same English UI catalog is used by the PWA, copied by Gradle into Android assets.
 * Never translate messages, posts, prayers, Quran text or other user/server content.
 */
internal object AttaLocale {
    private var loaded = false
    private var english: Map<String, String> = emptyMap()
    var language by mutableStateOf("ar")
        private set

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        language = if (context.getSharedPreferences("alatta_native", Context.MODE_PRIVATE)
                .getString("ui_language", "ar") == "en") "en" else "ar"
        english = try {
            val json = JSONObject(context.assets.open("legal/i18n/en.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() })
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun choose(context: Context, choice: String) {
        language = if (choice == "en") "en" else "ar"
        context.getSharedPreferences("alatta_native", Context.MODE_PRIVATE).edit()
            .putString("ui_language", language).apply()
    }

    fun tr(original: String): String {
        if (language != "en") return original
        english[original]?.let { return it }
        val trimmed = original.trim()
        return english[trimmed]?.let { original.takeWhile(Char::isWhitespace) + it +
            original.takeLastWhile(Char::isWhitespace) } ?: original
    }
}

@androidx.compose.runtime.Composable
internal fun AttaLanguageSwitcher(compact: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val current = AttaLocale.language
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = if (compact) 1.dp else 7.dp)) {
        listOf("ar" to "العربية", "en" to "English").forEach { (key, label) ->
            val selected = current == key
            Text(label, modifier = Modifier
                .background(if (selected) Color(0xFF6C4D1B) else Color(0xFF101315),
                    RoundedCornerShape(12.dp))
                .then(Modifier.clickable { AttaLocale.choose(context, key) })
                .padding(horizontal = if (compact) 10.dp else 14.dp,
                    vertical = if (compact) 5.dp else 9.dp),
                fontSize = if (compact) 11.sp else 14.sp,
                color = if (selected) Color(0xFFFFD477) else Color(0xFFBBBBBB))
        }
    }
}
