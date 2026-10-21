package com.alatta.nativeapp

import androidx.compose.ui.graphics.Color

/** Site tokens from cloudflare-site/public/style.css (:root --g13-*). */
internal object WebsiteStyle {
    val black = Color(0xFF030405)
    val panel = Color(0xFF080C0F)
    val panel2 = Color(0xFF0C1115)
    val goldDark = Color(0xFF7C5510)
    val gold = Color(0xFFD5A52E)
    val goldMid = Color(0xFFE9BB4A)
    val goldLight = Color(0xFFFFE28A)
    val goldPale = Color(0xFFFFF0B6)
    val line = goldMid.copy(alpha = .42f)
    val glow = goldMid.copy(alpha = .24f)
}
