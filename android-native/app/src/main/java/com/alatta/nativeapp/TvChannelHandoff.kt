package com.alatta.nativeapp

import android.content.Intent
import android.content.Context

/** The AL-ATTA TV -> AL-ATTA handoff transports only the catalog ID, never a URL or token. */
internal object TvChannelHandoff {
    const val ACTION = "com.alatta.nativeapp.action.OPEN_TV_CHANNEL"
    const val CHANNEL_ID = "com.alatta.nativeapp.extra.TV_CHANNEL_ID"
    private val allowed = Regex("[A-Za-z0-9._-]{1,80}")

    fun read(intent: Intent?): String? {
        if (intent?.action != ACTION) return null
        return intent.getStringExtra(CHANNEL_ID)?.takeIf { allowed.matches(it) }
    }

    /**
     * Compatibility with older AL-ATTA TV installs. They send a catalog ID to
     * MainActivity, not a playable YTV number. Send that ID to the SAME Gold
     * receiver as new TV builds; never navigate to the legacy sports/TV tab.
     */
    fun legacyReceiverIntent(context: Context, incoming: Intent?): Intent? {
        val catalogId = read(incoming) ?: return null
        return Intent(context, GoldPlaybackReceiverActivity::class.java)
            .setAction(GoldPlaybackReceiverActivity.ACTION_LEGACY_CATALOG)
            .putExtra(GoldPlaybackReceiverActivity.EXTRA_CATALOG_ID, catalogId)
    }

    /** Converts a list ID from old TV versions into the actual YTV engine number. */
    fun engineNumber(catalog: FootballCatalog, id: String): String? =
        catalog.channels.firstOrNull {
            it.id == id && it.enabled && it.format == "ytv"
        }?.streamUrl?.let(YtvPlayer::channelId)
}
