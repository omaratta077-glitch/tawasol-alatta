package com.alatta.nativeapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.json.JSONObject

internal data class NativeCallSession(
    val peer: String,
    val type: String,
    val callId: String,
    val incoming: Boolean
)

@Composable
internal fun NativeCallScreen(
    session: NativeCallSession,
    token: String,
    myUsername: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val oldMode = remember { audioManager.mode }
    val oldSpeaker = remember { audioManager.isSpeakerphoneOn }

    val webView = remember(session.callId) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(5, 7, 9))
            keepScreenOn = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(object {
                @JavascriptInterface
                fun close() { activity?.runOnUiThread { onClose() } }
                @JavascriptInterface
                fun onStatus(message: String) { /* Status displayed inside trusted call document. */ }
                @JavascriptInterface
                fun setSpeaker(enabled: Boolean) {
                    activity?.runOnUiThread {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = enabled
                    }
                }
                @JavascriptInterface
                fun mediaReady() {
                    // Configure communication routing AFTER WebRTC has captured the microphone.
                    // Some Android devices report NotReadableError if audio mode is changed first.
                    activity?.runOnUiThread {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = session.type == "video"
                    }
                }
            }, "AttaBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    activity?.runOnUiThread {
                        val goodOrigin = request.origin.scheme == "https" &&
                            request.origin.host == "tawasol-alatta.omaratta077-4fd.workers.dev"
                        val approved = request.resources.filter { res ->
                            (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ||
                            (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
                                session.type == "video" &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                        }
                        if (goodOrigin && approved.isNotEmpty()) request.grant(approved.toTypedArray())
                        else request.deny()
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = true
            }
            val config = JSONObject().put("peer", session.peer).put("type", session.type)
                .put("callId", session.callId).put("incoming", session.incoming)
                .put("token", token).put("me", myUsername)
            val page = context.assets.open("atta-call.html").bufferedReader().use { it.readText() }
                .replace("__CONFIG_JSON__", config.toString().replace("</", "<\\/"))
            loadDataWithBaseURL("https://tawasol-alatta.omaratta077-4fd.workers.dev/", page,
                "text/html", "UTF-8", null)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.loadUrl("about:blank")
            webView.removeJavascriptInterface("AttaBridge")
            webView.destroy()
            audioManager.mode = oldMode
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = oldSpeaker
        }
    }
    BackHandler { webView.evaluateJavascript("window.__attaEndCall && window.__attaEndCall()", null) }
    Box(Modifier.fillMaxSize().background(Color(0xFF050709)), contentAlignment = Alignment.Center) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
    }
}
