package com.alatta.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** بيانات وصفية مشتركة للقراءة فقط؛ حمّلها في IO ثم أعد استخدامها للقارئ واختبار اللمس والتنزيلات. */
internal class MushafIndex(val json: JSONObject) {
    val pages: JSONArray = json.getJSONArray("pages")
    val ayahs: JSONArray = json.getJSONArray("ayahs")
    val quarterStarts: List<Int> = json.getJSONArray("quarterStart").let { starts ->
        (1 until starts.length()).map { starts.getInt(it) }
    }
    val chapters: List<Triple<Int, String, Int>> = buildList {
        val seen = mutableSetOf<Int>()
        for (page in 1..604) {
            val headers = pages.getJSONObject(page).optJSONArray("headers") ?: continue
            for (i in 0 until headers.length()) {
                val header = headers.getJSONObject(i)
                val number = header.optInt("number")
                if (number in 1..114 && seen.add(number)) add(Triple(number, header.optString("nameArabic"), page))
            }
        }
    }.sortedBy { it.first }
    init { require(pages.length() == 605 && chapters.size == 114 && quarterStarts.size == 240) }
}

/** تُخزّن التحميلات الناجحة فقط. وتتشارك طلبات القارئ والتنزيل المتزامنة عملية تحليل واحدة. */
internal class SingleLoadCache<T : Any> {
    @Volatile private var value: T? = null
    fun get(load: () -> T): T = value ?: synchronized(this) {
        value ?: load().also { value = it }
    }
}

internal object MushafIndexStore {
    private val cache = SingleLoadCache<MushafIndex>()
    fun load(context: Context): MushafIndex = cache.get {
        context.applicationContext.assets.open("mushaf/index.json").bufferedReader().use {
            MushafIndex(JSONObject(it.readText()))
        }
    }
}
