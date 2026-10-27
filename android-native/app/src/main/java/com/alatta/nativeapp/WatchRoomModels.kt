package com.alatta.nativeapp

import org.json.JSONObject
import java.util.Locale

internal fun watchRoomCode(value: String): String? = value.trim().replace(Regex("[\\s-]"), "")
    .uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-F0-9]{12}")) }
internal data class WatchMember(val peerId: String, val username: String, val name: String,
    val cameraOn: Boolean, val micOn: Boolean)
internal data class WatchRoom(val code: String, val myPeerId: String, val host: String,
    val channelId: String, val channelName: String, val members: List<WatchMember>) {
    companion object {
        fun parse(json: JSONObject): WatchRoom {
            val code = requireNotNull(watchRoomCode(json.getString("code")))
            val list = json.getJSONArray("members")
            require(list.length() in 1..6)
            val members = (0 until list.length()).map { index -> list.getJSONObject(index).let {
                WatchMember(it.getString("peerId"), it.getString("username"), it.getString("name").take(80),
                    it.optBoolean("cameraOn", true), it.optBoolean("micOn", true))
            } }
            require(members.map { it.peerId }.distinct().size == members.size)
            val mine = json.getString("myPeerId")
            require(members.count { it.peerId == mine } == 1)
            return WatchRoom(code, mine, json.getString("host"), json.optString("channelId"), json.optString("channelName"), members)
        }
    }
}
