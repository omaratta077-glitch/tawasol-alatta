package com.alatta.nativeapp

import android.util.TypedValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LauncherAliasTest {
    @Test fun bothManifestIconNamesResolveToTheSameApprovedArtwork() {
        val context = RuntimeEnvironment.getApplication()
        val regular = TypedValue()
        val round = TypedValue()
        context.resources.getValue(R.mipmap.ic_launcher, regular, true)
        context.resources.getValue(R.mipmap.ic_launcher_round, round, true)
        assertEquals(regular.string.toString(), round.string.toString())
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher_round))
    }
}
