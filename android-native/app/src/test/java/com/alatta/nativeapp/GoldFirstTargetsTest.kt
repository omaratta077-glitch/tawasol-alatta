package com.alatta.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])

class GoldFirstTargetsTest {
    @Test fun twoEntrypointsInOneGoldPackage() {
        val gold = GoldFirstTargets.goldPlayerIntent("com.ytv.pronew")
        val media = GoldFirstTargets.mediaIntent("com.ytv.pronew")
        assertEquals("com.ytv.pronew", gold.component!!.packageName)
        assertEquals("com.ytv.pronew", media.component!!.packageName)
        assertEquals(GoldFirstTargets.GOLD_ACTIVITY, gold.component!!.className)
        assertEquals(GoldFirstTargets.MEDIA_ACTIVITY, media.component!!.className)
    }
}
