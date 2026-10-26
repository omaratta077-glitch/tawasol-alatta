package com.alatta.stream

import org.json.JSONObject

data class Channel(
    val id: String,
    val name: String,
    val category: String,
    val streamUrl: String,
    val description: String = "",
    val enabled: Boolean = true,
    val origin: String = "local"
) {
    fun asJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("category", category)
        put("streamUrl", streamUrl)
        put("description", description)
        put("enabled", enabled)
        put("origin", origin)
    }
}
