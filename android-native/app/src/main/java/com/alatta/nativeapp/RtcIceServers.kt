package com.alatta.nativeapp

import org.json.JSONObject
import org.webrtc.PeerConnection

/** يحلل بيانات اعتماد Cloudflare TURN قصيرة العمر نفسها المستخدمة في المكالمات الأصلية. */
internal fun parseRtcIceServers(credentials: JSONObject): List<PeerConnection.IceServer> {
    val fallback = fallbackRtcIceServers()
    val servers = credentials.optJSONArray("iceServers") ?: return fallback
    val parsed = ArrayList<PeerConnection.IceServer>()
    for (index in 0 until servers.length()) {
        val item = servers.optJSONObject(index) ?: continue
        val urls = when (val value = item.opt("urls")) {
            is String -> listOf(value)
            is org.json.JSONArray -> (0 until value.length())
                .mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
            else -> emptyList()
        }
        if (urls.isEmpty()) continue
        val builder = PeerConnection.IceServer.builder(urls)
        val username = item.optString("username")
        val password = item.optString("credential")
        if (username.isNotBlank()) builder.setUsername(username)
        if (password.isNotBlank()) builder.setPassword(password)
        parsed += builder.createIceServer()
    }
    return parsed.ifEmpty { fallback }
}

internal fun fallbackRtcIceServers() = listOf(
    PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
)
