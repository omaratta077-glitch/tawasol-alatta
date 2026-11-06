package com.alatta.nativeapp

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmbeddedGoldEngineTest {
    @Test fun intentTargetsOnlyTheHostPackageAndPreservesProvenChannelExtras() {
        val intent = EmbeddedGoldEngine.channelIntent("com.alatta.nativeapp", "1424")
        assertEquals(ComponentName("com.alatta.nativeapp",
            "dev.google.ytvplayer.ui.splash.SplashActivity"), intent?.component)
        assertEquals(1424L, intent?.getLongExtra("id", 0))
        assertEquals(1, intent?.getIntExtra("type", -1))
    }

    @Test fun invalidChannelDoesNotLaunchAnything() {
        assertNull(EmbeddedGoldEngine.channelIntent("com.alatta.nativeapp", "bad"))
        assertNull(EmbeddedGoldEngine.channelIntent("com.alatta.nativeapp", "0"))
        assertNull(EmbeddedGoldEngine.channelIntent("", "1424"))
    }

    @Test fun noEngineInHostMeansNoFalsePositiveOrActivityLaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(EmbeddedGoldEngine.isPackaged(context))
        assertEquals(EmbeddedGoldEngine.Result.NOT_PACKAGED, EmbeddedGoldEngine.launch(context, "1424"))
    }
}
