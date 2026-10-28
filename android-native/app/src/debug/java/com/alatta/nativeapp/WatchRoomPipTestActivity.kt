package com.alatta.nativeapp

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** Debug-only system PiP exercise: no accounts, camera, microphone, channel source or network. */
class WatchRoomPipTestActivity : ComponentActivity(), WatchRoomPipStateHost {
    private var pip by mutableStateOf(false)
    @Volatile internal var lastFailure: WatchRoomPip.Failure? = null
    @Volatile internal var launchFinished = false
    @Volatile internal var playerLaunched = false
    @Volatile internal var pipWidth = 0
    @Volatile internal var pipHeight = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creator = intent.getBooleanExtra("creator", true)
        val alone = intent.getBooleanExtra("alone", false)
        val owner = WatchMember("fixture-owner", "owner", "منشئ الغرفة", true, true)
        val guest = WatchMember("fixture-guest", "guest", "الصديق", true, true)
        WatchRoomController.room = WatchRoom("ABC", if (creator) owner.peerId else guest.peerId, "owner", "", "اختبار",
            if (alone) listOf(owner) else listOf(owner, guest))
        WatchRoomController.active = true
        WatchRoomController.ready = true
        setContent { MaterialTheme {
            val room = WatchRoomController.room
            if (pip && room != null) WatchVideoStage(room, null, Modifier.fillMaxSize().onSizeChanged {
                pipWidth = it.width; pipHeight = it.height
            }) else Column(Modifier.fillMaxSize()) {
                if (room != null) WatchVideoStage(room, null, Modifier.fillMaxWidth().height(240.dp))
                Button(onClick = { launchFixturePlayer() }) { Text("تشغيل قناة الاختبار") }
            }
        } }
    }
    internal fun launchFixturePlayer() {
        lifecycleScope.launch {
            try {
                lastFailure = WatchRoomPip.enterAndWait(this@WatchRoomPipTestActivity)
                if (lastFailure == null) {
                    startActivity(Intent(this@WatchRoomPipTestActivity, WatchRoomExternalTestActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
                    playerLaunched = true
                }
            } finally { launchFinished = true }
        }
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Deliberately leave Compose stale: the production handoff must refresh it after system confirmation.
    }
    override fun syncWatchRoomPipState() { pip = isInPictureInPictureMode }
    override fun onDestroy() {
        WatchRoomController.active = false; WatchRoomController.ready = false; WatchRoomController.room = null
        super.onDestroy()
    }
}

/** A separate task represents the external channel player and deliberately sends no leave hint. */
class WatchRoomExternalTestActivity : Activity() {
    companion object {
        @Volatile internal var current: WeakReference<WatchRoomExternalTestActivity>? = null
        @Volatile internal var visible = false
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        setContentView(TextView(this).apply { text = "مشغّل اختبار محلي" })
    }
    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { visible = false; super.onPause() }
    override fun onDestroy() { current = null; visible = false; super.onDestroy() }
}
