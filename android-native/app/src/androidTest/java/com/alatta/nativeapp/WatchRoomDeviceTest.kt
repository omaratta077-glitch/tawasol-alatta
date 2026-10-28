package com.alatta.nativeapp

import android.content.ComponentName
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WatchRoomDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sixMembersAreVisibleAndTheCameraServiceIsNotExported() {
        val members = (0..5).map { WatchMember("peer-$it", "user$it", "صاحب $it", it % 2 == 0, true) }
        val room = WatchRoom("ABC", "peer-0", "user0", "saved-test", "Test Sports", members)
        compose.setContent { MaterialTheme { WatchVideoStage(room, null, Modifier.fillMaxWidth().height(240.dp)) } }
        for (member in members) compose.onNodeWithText(member.name).assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = context.packageManager.getServiceInfo(ComponentName(context, WatchRoomService::class.java), 0)
        assertFalse(service.exported)
    }
}
