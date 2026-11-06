package com.alatta.nativeapp

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldPlaybackReceiverActivityTest {
    @Test fun channelNumberEntersMediaReceiverWithoutLaunchingExternalPlayer() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, GoldPlaybackReceiverActivity::class.java)
            .setAction(GoldPlaybackReceiverActivity.ACTION)
            .putExtra(GoldPlaybackReceiverActivity.EXTRA_SOURCE_NUMBER, "1424")
        val activity = Robolectric.buildActivity(GoldPlaybackReceiverActivity::class.java, intent).setup().get()
        assertNotNull(activity)
        // The bundled engine has not yet been ported: do not launch a fake
        // embedded player and do not fall back to a separately-installed APK.
        assertFalse(EmbeddedGoldEngine.isPackaged(activity))
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    @Test fun invalidNumberDoesNotStartAPlayer() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, GoldPlaybackReceiverActivity::class.java)
            .setAction(GoldPlaybackReceiverActivity.ACTION)
            .putExtra(GoldPlaybackReceiverActivity.EXTRA_SOURCE_NUMBER, "not-a-channel")
        val activity = Robolectric.buildActivity(GoldPlaybackReceiverActivity::class.java, intent).setup().get()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }
}
