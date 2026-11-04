package com.alatta.nativeapp

import android.content.Intent

/** The AL-ATTA TV -> AL-ATTA handoff transports only the catalog ID, never a URL or token. */
internal object TvChannelHandoff {
    const val ACTION = "com.alatta.nativeapp.action.OPEN_TV_CHANNEL"
    const val CHANNEL_ID = "com.alatta.nativeapp.extra.TV_CHANNEL_ID"
    private val allowed = Regex("[A-Za-z0-9._-]{1,80}")

    fun read(intent: Intent?): String? {
        if (intent?.action != ACTION) return null
        return intent.getStringExtra(CHANNEL_ID)?.takeIf { allowed.matches(it) }
    }
}
