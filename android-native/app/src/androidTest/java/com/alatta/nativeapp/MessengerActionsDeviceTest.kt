package com.alatta.nativeapp

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MessengerActionsDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun incomingAndOutgoingHistoryCardsRedialTheSameConversationWithTheRightType() {
        var audio = 0; var video = 0
        val messages = listOf("audio", "video").flatMap { type -> listOf(false, true).map { outgoing ->
            JSONObject().put("id", "$type-$outgoing").put("from", if (outgoing) "owner" else "friend")
                .put("to", if (outgoing) "friend" else "owner").put("mediaType", "call-event")
                .put("callType", type).put("callStatus", "completed").put("ts", 1000L)
        } }
        compose.setContent { MaterialTheme { Thread(messages, onAudio = { audio++ }, onVideo = { video++ }) } }
        repeat(2) { compose.onAllNodesWithText("مكالمة صوتية • انتهت المكالمة")[it].performClick() }
        repeat(2) { compose.onAllNodesWithText("مكالمة فيديو • انتهت المكالمة")[it].performClick() }
        compose.runOnIdle { assertEquals(2, audio); assertEquals(2, video) }
    }
    @Test fun roomInvitationCopiesTheCodeAndTheLongPressMenuCopiesTheWholeMessage() {
        val text = "انضم لغرفة المشاهدة في العطا. الكود: ABC"
        val message = JSONObject().put("id", "invite").put("from", "friend").put("to", "owner").put("text", text).put("ts", 1000L)
        compose.setContent { MaterialTheme { Thread(listOf(message)) } }
        compose.onNodeWithText("نسخ كود الغرفة • ABC").performClick()
        fun copied() = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()
        compose.runOnIdle { assertEquals("ABC", copied()) }
        compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText("نسخ", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(text, copied()) }
    }
}

@androidx.compose.runtime.Composable
private fun Thread(messages: List<JSONObject>, onAudio: () -> Unit = {}, onVideo: () -> Unit = {}) {
    MessengerThread(JSONObject().put("username", "friend").put("fullName", "صاحبي"), "friend", "owner", messages,
        "", false, {}, {}, {}, {}, {}, {}, {}, onAudio, onVideo, { _, _ -> }, {}, {})
}
