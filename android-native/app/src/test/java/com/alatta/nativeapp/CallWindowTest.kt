package com.alatta.nativeapp

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CallWindowTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun call() = PushCall("caller", "receiver", "call-window-162", "audio", System.currentTimeMillis(), "view", "عمر عطا")
    @Before fun setup() {
        context.getSharedPreferences("alatta_native", Context.MODE_PRIVATE).edit().clear()
            .putString("username", "receiver").putString("token", "session").commit()
        AttaPrivacy.accept(context)
        AttaPush.appVisible = false; AttaPush.foregroundRingCallId = ""; AttaPush.activeCallId = ""
        AttaPush.createChannel(context)
    }
    private fun texts(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
        (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
    @Test fun cardShowsRealNameAndBothActionsOnNarrowPhone() {
        var answered = 0; var rejected = 0
        val card = callWindowCard(context, call(), { answered++ }, { rejected++ })
        card.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        val labels = texts(card)
        assertTrue(labels.any { it.text.toString() == "عمر عطا" })
        val answer = labels.single { it.text.toString() == "رد" }
        val reject = labels.single { it.text.toString() == "رفض" }
        assertEquals(answer.width, reject.width)
        assertTrue(answer.width > 0)
        assertNotNull(answer.compoundDrawablesRelative[0])
        assertNotNull(reject.compoundDrawablesRelative[0])
        val cardBackground = card.background as GradientDrawable
        assertEquals(0xFFE0B84F.toInt(), cardBackground.color?.defaultColor)
        assertEquals(0xFFE0B84F.toInt(), cardBackground.strokeColor.defaultColor)
        answer.performClick(); reject.performClick()
        assertEquals(1, answered); assertEquals(1, rejected)
    }
    @Test fun serviceUsesOriginalDeadlineAndDoesNotRestartExpiredCall() {
        val incoming = call().copy(createdAt = System.currentTimeMillis() - 44_000)
        AttaPush.rememberIncoming(context, incoming)
        val controller = Robolectric.buildService(IncomingCallOverlayService::class.java,
            AttaPush.intentFor(context, incoming, "view"))
        controller.create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertNull(AttaPush.pendingIncoming(context))
        assertEquals(incoming.callId, context.getSharedPreferences("alatta_native", 0).getString("ended_call_alert_id", ""))
        controller.destroy()
    }
    @Test fun explicitDismissRemovesServiceWhenForegroundTakesOverWithoutEndingInvitation() {
        val incoming = call()
        AttaPush.rememberIncoming(context, incoming)
        val controller = Robolectric.buildService(IncomingCallOverlayService::class.java,
            AttaPush.intentFor(context, incoming, "view"))
        controller.create().startCommand(0, 1)
        AttaPush.dismiss(context, incoming.callId, ended = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(incoming.callId, AttaPush.pendingIncoming(context)?.callId)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }
}
