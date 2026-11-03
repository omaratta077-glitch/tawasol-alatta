package com.alatta.nativeapp

import android.graphics.Color as AndroidColor
import android.os.PowerManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** مشغّل مستقل يستخدم YouTube IFrame API الرسمي فقط. لا يفتح صفحة العطا أو جسر YouTube القديم. */
internal object AttaFreshYouTubeHtml {
    fun create(id: String): String {
        require(Regex("[A-Za-z0-9_-]{11}").matches(id))
        // Stable app-owned origin, independent from the Cloudflare website.
        val origin = "https://appassets.androidplatform.net"
        return """
<!doctype html><html lang="ar" dir="rtl"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>
html,body,#player{margin:0;width:100%;height:100%;background:#000;overflow:hidden}
iframe{width:100%!important;height:100%!important;display:block;border:0}
</style></head><body><div id="player"></div>
<script>
'use strict';
(function(){
 const emit=function(event){console.log('ALATTA_YT:'+event)};
 window.__attaPlayer=null;
 window.__attaCurrentId='$id';
 window.__attaPendingId=null;
 window.attaSwitchVideo=function(nextId){
   if(!/^[A-Za-z0-9_-]{11}$/.test(nextId) || nextId===window.__attaCurrentId)return;
   window.__attaCurrentId=nextId;
   const player=window.__attaPlayer;
   if(!player){window.__attaPendingId=nextId;return;}
   try{player.loadVideoById(nextId)}catch(_){emit('ERROR:PLAYER_SWITCH')}
 };
 window.attaCommand=function(cmd){
   const p=window.__attaPlayer;
   if(!p)return;
   try{if(cmd==='play')p.playVideo();if(cmd==='pause')p.pauseVideo()}catch(_){}
 };
 window.onYouTubeIframeAPIReady=function(){
   try {
     window.__attaPlayer=new YT.Player('player',{
       videoId:'$id',
       width:'100%',height:'100%',
       playerVars:{playsinline:1,controls:1,rel:0,autoplay:0,fs:1,origin:'$origin',enablejsapi:1},
       events:{
         onReady:function(){emit('FRAME_LOADED');if(window.__attaPendingId){const next=window.__attaPendingId;window.__attaPendingId=null;try{window.__attaPlayer.loadVideoById(next)}catch(_){emit('ERROR:PLAYER_SWITCH')}}},
         onStateChange:function(e){
           if(e.data===YT.PlayerState.PLAYING)emit('PLAYING');
           else if(e.data===YT.PlayerState.PAUSED)emit('PAUSED');
           else if(e.data===YT.PlayerState.ENDED)emit('ENDED');
         },
         onError:function(e){emit('PLAYER_ERROR:'+String(e.data))}
       }
     });
   }catch(e){emit('ERROR:PLAYER_INIT')}
 };
 var script=document.createElement('script');
 script.src='https://www.youtube.com/iframe_api';script.async=true;
 script.onerror=function(){emit('ERROR:API_LOAD')};
 document.head.appendChild(script);
})();
</script></body></html>
""".trimIndent()
    }
}

@Composable
internal fun AttaFreshYouTubePlayer(
    id: String,
    onMiniDrag: ((Float, Float) -> Unit)? = null,
    portrait: Boolean = false,
    videoIsShort: Boolean = false,
    playbackCommand: Int = 0,
    commandShouldPlay: Boolean = true,
    onPlaybackChanged: ((Boolean) -> Unit)? = null
) {
    val safeId = id.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return
    val context = LocalContext.current
    val composeView = LocalView.current
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    val changed by rememberUpdatedState(onPlaybackChanged)
    val chrome = remember(context, composeView) {
        AttaYoutubeFullscreenChrome(context, hostWindow = {
            (composeView.parent as? DialogWindowProvider)?.window
        }) { event ->
            when {
                event == "FRAME_LOADED" -> { ready = true; error = "" }
                event == "PLAYING" -> changed?.invoke(true)
                event == "PAUSED" || event == "ENDED" -> changed?.invoke(false)
                event.startsWith("PLAYER_ERROR:") -> {
                    ready = true
                    val code = event.substringAfter(':')
                    error = when(code) {
                        "101", "150" -> "صاحب الفيديو يمنع تضمينه داخل التطبيقات."
                        "153" -> "تعذّر التحقق من مصدر YouTube. جرّب مرة أخرى."
                        else -> "فشل YouTube في تشغيل المقطع ($code)."
                    }
                }
                event == "ERROR:API_LOAD" || event == "ERROR:PLAYER_INIT" || event == "ERROR:PLAYER_SWITCH" -> error = "تعذّر تجهيز مشغّل YouTube الرسمي."
            }
        }
    }
    // Preserve the same WebView across consecutive YouTube reels to avoid recreating its engine.
    val web = remember(context) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
            webChromeClient = chrome
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, issue: WebResourceError?) {
                    if (request?.isForMainFrame == true) error = "تعذّر تحميل مشغّل YouTube."
                }
            }
        }
    }
    val host = remember(context) {
        MiniPlayerTouchHost(context).apply {
            verticalOnlyDrag = portrait
            addView(web, FrameLayout.LayoutParams(-1,-1))
        }
    }
    var loadedVideoId by remember(web) { mutableStateOf(safeId) }
    // Initialise the IFrame once. Later reel swipes use loadVideoById instead of a network reload.
    LaunchedEffect(web, reload) {
        loadedVideoId = safeId
        ready = false; error = ""
        web.loadDataWithBaseURL("https://appassets.androidplatform.net/", AttaFreshYouTubeHtml.create(safeId), "text/html", "UTF-8", null)
        kotlinx.coroutines.delay(18000)
        if (!ready && error.isBlank()) error = "استغرق تجهيز YouTube وقتًا أطول من المعتاد."
    }
    LaunchedEffect(web, safeId, ready) {
        if (ready && loadedVideoId != safeId) {
            loadedVideoId = safeId
            web.evaluateJavascript("window.attaSwitchVideo('$safeId');", null)
        }
    }
    LaunchedEffect(web, portrait, playbackCommand, ready) {
        if (portrait && playbackCommand > 0 && ready) {
            val method = if (commandShouldPlay) "play" else "pause"
            web.evaluateJavascript("window.attaCommand('$method');",null)
        }
    }
    DisposableEffect(web,chrome) {
        val activity=context.mediaActivity()
        val observer=LifecycleEventObserver { _,event ->
            when(event){
                Lifecycle.Event.ON_STOP -> {
                    val native = activity as? MainActivity
                    val pip = native?.isInPictureInPictureMode == true &&
                        activity.getSystemService(PowerManager::class.java)?.isInteractive == true
                    if(!pip){
                        web.evaluateJavascript("window.attaCommand('pause');",null)
                        web.onPause()
                    }
                }
                Lifecycle.Event.ON_START -> web.onResume()
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            chrome.release()
            (host.parent as? ViewGroup)?.removeView(host)
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.destroy()
        }
    }
    val compact=LocalConfiguration.current.screenHeightDp < 480
    BoxWithConstraints(if(portrait)Modifier.fillMaxSize() else Modifier.fillMaxWidth()){
        val surface = if(portrait) Modifier.fillMaxSize()
          else Modifier.fillMaxWidth().height(if(compact)200.dp else (maxWidth * 9f/16f).coerceAtLeast(200.dp))
        Box(surface.background(Color.Black),contentAlignment=Alignment.Center){
            AndroidView(
                factory={ (host.parent as? ViewGroup)?.removeView(host);host },
                update={ it.verticalOnlyDrag=portrait; it.onDrag=onMiniDrag },
                modifier=Modifier.fillMaxSize())
            if(!ready || error.isNotBlank()){
                Column(
                    Modifier.fillMaxSize().background(Color.Black).padding(16.dp),
                    verticalArrangement=Arrangement.Center,
                    horizontalAlignment=Alignment.CenterHorizontally
                ){
                    if(error.isBlank()){
                        CircularProgressIndicator(color=Color(0xFFE6B94E),modifier=Modifier.size(26.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("جاري تجهيز YouTube",color=Color(0xFFF6D776),fontSize=13.sp)
                    }else{
                        Text(error,color=Color(0xFFFFB0B0),textAlign=TextAlign.Center,fontSize=13.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick={reload++},border=BorderStroke(1.dp,Color(0xFFE6B94E)),
                            shape=RoundedCornerShape(12.dp)){
                            Text("إعادة المحاولة",color=Color(0xFFF6D776))
                        }
                    }
                }
            }
        }
    }
}
