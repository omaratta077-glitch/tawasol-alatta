package com.alatta.nativeapp

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/**
 * Reels v2: renderer rebuilt independently from the media viewer used by live sports.
 * A single Media3 session is retained for uploaded video, and the official YouTube player
 * is supplied by the app, never reconstructed by this viewer.
 */
@Composable
internal fun AttaFreshReelsViewer(
    posts: List<JSONObject>,
    startId: String,
    onDismiss: () -> Unit,
    loadInteractions: PostInteractionsLoader? = null,
    sendReaction: PostReactionSender? = null,
    sendComment: PostCommentSender? = null,
    onShare: (suspend (String) -> Unit)? = null,
    onStats: (String, JSONObject) -> Unit = { _, _ -> },
    onOpenProfile: (String) -> Unit = {},
    youtubeShortContent: (@Composable (String, Int, Boolean, (Boolean) -> Unit, (Float, Float) -> Unit, Boolean) -> Unit)? = null,
    rejectedShortIds: Set<String> = emptySet(),
    nextReelsCursor: String? = null,
    onLoadMoreReels: (() -> Unit)? = null,
    onRefreshReels: (() -> Unit)? = null,
    onReelsNavigate: ((String) -> Unit)? = null,
    reelsLoading: Boolean = false
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val reels=remember(posts,rejectedShortIds){uploadedPostReels(posts,rejectedShortIds)}
    if(reels.isEmpty())return
    var activeKey by remember(startId) {
        mutableStateOf(reels.firstOrNull { it.post.optString("id")==startId }?.key ?: reels.first().key)
    }
    val found=reels.indexOfFirst{it.key==activeKey}
    val index=found.coerceAtLeast(0)
    val reel=reels[index]
    val post=reel.post
    val id=post.optString("id")
    val isUploaded=reel.videoUrl.isNotBlank()
    val youtubeId=reel.youtubeId
    val social=reel.social
    var controls by remember(reel.key){mutableStateOf(true)}
    var error by remember(reel.key){mutableStateOf("")}
    var sheet by remember(reel.key){mutableStateOf<String?>(null)}
    var reactBusy by remember(reel.key){mutableStateOf(false)}
    var reactions by remember(reel.key){mutableIntStateOf(post.optInt("reactionCount",post.optInt("likesCount",0)))}
    var comments by remember(reel.key){mutableIntStateOf(post.optInt("commentCount",0))}
    var myReaction by remember(reel.key){mutableStateOf(post.optString("myReaction"))}
    var youtubePlaying by remember(reel.key){mutableStateOf(false)}
    var youtubeCommand by remember(reel.key){mutableIntStateOf(0)}
    var youtubeCommandPlay by remember(reel.key){mutableStateOf(true)}
    var progress by remember(reel.key){mutableFloatStateOf(0f)}
    var duration by remember(reel.key){mutableLongStateOf(0L)}
    var playing by remember{mutableStateOf(false)}
    var aspect by remember(reel.key){mutableFloatStateOf(16f/9f)}
    val source by produceState<androidx.media3.datasource.DataSource.Factory?>(null,context){
        value=try{withContext(Dispatchers.IO){VideoTransport.factory(context,false,emptyMap())}}
        catch(e:Exception){null}
    }
    val player=remember(context,source){
        source?.let { ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(it))
            .setLoadControl(VideoTransport.loadControl(false))
            .build() }
    }
    DisposableEffect(player){
        val listener=object:Player.Listener{
            override fun onIsPlayingChanged(value:Boolean){playing=value}
            override fun onVideoSizeChanged(size:androidx.media3.common.VideoSize){
                if(size.width>0&&size.height>0)aspect=(size.width*size.pixelWidthHeightRatio/size.height).coerceIn(.4f,2.5f)
            }
            override fun onPlayerError(failure:androidx.media3.common.PlaybackException){
                error="تعذّر تشغيل الفيديو. اضغط إعادة المحاولة."
                controls=true
            }
        }
        player?.addListener(listener)
        val activity=context.mediaActivity()
        val lifecycle=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)player?.pause()}
        activity?.lifecycle?.addObserver(lifecycle)
        onDispose{
            activity?.lifecycle?.removeObserver(lifecycle)
            player?.removeListener(listener)
            player?.release()
        }
    }
    LaunchedEffect(reel.key,player){
        error=""
        youtubePlaying=false
        if(isUploaded && player!=null){
            player.pause()
            player.setMediaItem(MediaItem.fromUri(reel.videoUrl))
            player.prepare()
            player.playWhenReady=true
            player.play()
        }else{player?.pause();player?.clearMediaItems()}
        if(loadInteractions!=null)try{
            val stats=loadInteractions(id,"reactions",0)
            reactions=stats.optInt("reactionCount",reactions)
            comments=stats.optInt("commentCount",comments)
            myReaction=stats.optString("myReaction",myReaction)
            onStats(id,stats)
        }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
    }
    LaunchedEffect(player,reel.key){
        while(true){
            if(player!=null && isUploaded){
                duration=player.duration.coerceAtLeast(0L)
                progress=if(duration>0)(player.currentPosition.toFloat()/duration).coerceIn(0f,1f)else 0f
            }
            delay(400)
        }
    }
    LaunchedEffect(controls,reel.key,sheet){
        if(controls&&sheet==null){delay(3200);controls=false}
    }
    LaunchedEffect(index,reels.size,reelsLoading,nextReelsCursor){
        if(!reelsLoading && nextReelsCursor!=null && index >= reels.lastIndex-1)onLoadMoreReels?.invoke()
    }
    val change:(Int)->Unit={delta->
        val next=index+delta
        if(next in reels.indices){
            player?.pause()
            activeKey=reels[next].key
        }else if(!reelsLoading){
            if(delta<0)onRefreshReels?.invoke()
            else if(nextReelsCursor!=null)onLoadMoreReels?.invoke()
        }
    }
    val threshold=with(LocalDensity.current){84.dp.toPx()}
    var dragY by remember(reel.key){mutableFloatStateOf(0f)}
    var swipeConsumed by remember(reel.key){mutableStateOf(false)}
    val onWebDrag:(Float,Float)->Unit={_,dy->
        if(!swipeConsumed){
            dragY+=dy
            if(abs(dragY)>threshold){
                swipeConsumed=true
                change(if(dragY<0f)1 else -1)
            }
        }
    }
    val dismiss:()->Unit={player?.pause();onDismiss()}
    val react:()->Unit={
        if(sendReaction!=null && !reactBusy){
            scope.launch{
                reactBusy=true
                try{
                    val remove=myReaction=="like"
                    sendReaction(id,"like",remove)
                    myReaction=if(remove)"" else "like"
                    val data=loadInteractions?.invoke(id,"reactions",0)
                    if(data!=null){
                        reactions=data.optInt("reactionCount",reactions)
                        comments=data.optInt("commentCount",comments)
                        myReaction=data.optString("myReaction",myReaction)
                        onStats(id,data)
                    }
                }catch(cancelled:CancellationException){throw cancelled}
                catch(_:Exception){error="تعذّر تسجيل التفاعل."}
                finally{reactBusy=false}
            }
        }
    }
    Dialog(onDismissRequest={if(sheet!=null)sheet=null else dismiss()},
        properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,dismissOnClickOutside=true)){
        val view=LocalView.current
        val window=(view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window){
            window?.let{
                WindowCompat.setDecorFitsSystemWindows(it,false)
                it.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)
                WindowCompat.getInsetsController(it,view).apply{
                    systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.statusBars())
                    show(WindowInsetsCompat.Type.navigationBars())
                }
                it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose{window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        }
        BackHandler{if(sheet!=null)sheet=null else dismiss()}
        // Keep the entire navigation bar above the Android gesture/navigation area.
        Column(Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding()){
            Box(Modifier.weight(1f).fillMaxWidth()
                .pointerInput(reel.key){
                    run {
                        var movement=0f
                        detectVerticalDragGestures(onDragStart={movement=0f},
                            onDragCancel={movement=0f},
                            onDragEnd={if(abs(movement)>80.dp.toPx())change(if(movement<0)1 else -1)},
                            onVerticalDrag={event,amount->event.consume();movement+=amount})
                    }
                }){
                when{
                    isUploaded && player!=null -> {
                        BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                            // Crop vertical uploads; fit horizontal uploads without cropping content.
                            AndroidView(factory={ctx->PlayerView(ctx).apply{
                                this.player=player;useController=false
                            }},update={
                                it.player=player
                                it.resizeMode=if(aspect<1f)AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                  else AspectRatioFrameLayout.RESIZE_MODE_FIT
                            },modifier=Modifier.fillMaxSize())
                        }
                        Box(Modifier.fillMaxSize().clickable{controls=!controls})
                    }
                    youtubeId!=null && youtubeShortContent!=null -> {
                        BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                            val short=reel.youtubeIsShort
                            val maxH=if(short) maxHeight*.82f else maxHeight*.54f
                            val h=minOf(maxH,if(short)maxWidth*16f/9f else maxWidth*9f/16f)
                            val w=minOf(maxWidth,if(short)h*9f/16f else h*16f/9f)
                            Box(Modifier.width(w).height(h).background(Color.Black)){
                                // Keep the Compose player mounted when switching YouTube clips.
                                youtubeShortContent(youtubeId,youtubeCommand,youtubeCommandPlay,
                                    {youtubePlaying=it},onWebDrag,short)
                            }
                        }
                    }
                    social!=null -> key(reel.key){
                        SocialVideoPostEmbed(social,Modifier.fillMaxSize(),portrait=true,onSwipe=onWebDrag)
                    }
                    else -> Text("جاري تحميل الفيديو...",color=Color.White,
                        modifier=Modifier.align(Alignment.Center))
                }
                // The YouTube iframe consumes touch events. These empty side rails
                // give users a swipe surface outside the video on either side.
                if(youtubeId!=null){
                    val edgeSwipe = Modifier.fillMaxHeight(.72f).width(44.dp)
                        .pointerInput(reel.key){
                            var movement=0f
                            detectVerticalDragGestures(
                                onDragStart={movement=0f},
                                onDragCancel={movement=0f},
                                onDragEnd={if(abs(movement)>threshold) change(if(movement<0f)1 else -1)},
                                onVerticalDrag={event,dy->event.consume();movement+=dy}
                            )
                        }
                    Box(edgeSwipe.align(Alignment.CenterStart))
                    Box(edgeSwipe.align(Alignment.CenterEnd))
                }
                val author=post.optJSONObject("authorInfo")
                val name=author?.optString("fullName").orEmpty().ifBlank{post.optString("author")}
                val caption=visibleYoutubePostText(post.optString("text")).trim()
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(.76f)
                    .padding(start=14.dp,bottom=if(isUploaded&&controls)132.dp else 68.dp),
                    verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(name,color=Color(0xFFF5CC74),fontSize=17.sp,fontWeight=FontWeight.Bold)
                    if(caption.isNotBlank())Text(caption,color=Color.White,fontSize=14.sp,
                        maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                if(isUploaded && controls && player!=null){
                    Box(Modifier.align(Alignment.Center).size(64.dp)
                        .background(Color(0xA8000000),CircleShape)
                        .border(1.dp,Color(0xFFF1C76B),CircleShape)
                        .clickable{
                            if(player.isPlaying)player.pause() else {
                                if(player.playbackState==Player.STATE_ENDED)player.seekTo(0)
                                if(player.playerError!=null)player.prepare()
                                player.play()
                            }
                        },contentAlignment=Alignment.Center){
                        SiteIcon(if(playing)"pause" else "play",size=31.dp,tint=Color.White)
                    }
                }
                IconButton(onClick=dismiss,modifier=Modifier.align(AbsoluteAlignment.TopLeft)
                    .padding(10.dp).background(Color(0x99000000),CircleShape)){
                    SiteIcon("x",size=23.dp,tint=Color.White)
                }
                Column(Modifier.align(AbsoluteAlignment.CenterRight).padding(end=14.dp),
                    verticalArrangement=Arrangement.spacedBy(18.dp),
                    horizontalAlignment=Alignment.CenterHorizontally){
                    if(sendReaction!=null){
                        IconButton(onClick=react,enabled=!reactBusy){
                            SiteIcon("heart",size=28.dp,
                                tint=if(myReaction=="like")Color(0xFFF4CC71) else Color.White)
                        }
                        Text("$reactions",color=Color.White,fontSize=12.sp)
                    }
                    if(loadInteractions!=null && sendComment!=null){
                        IconButton(onClick={sheet="comments"}){SiteIcon("message-circle",size=27.dp,tint=Color.White)}
                        Text("$comments",color=Color.White,fontSize=12.sp)
                    }
                    if(onShare!=null)IconButton(onClick={
                        scope.launch{
                            try{onShare(id)}
                            catch(cancelled:CancellationException){throw cancelled}
                            catch(_:Exception){error="تعذّرت مشاركة المنشور."}
                        }
                    }){SiteIcon("send",size=26.dp,tint=Color.White)}
                }
                if(reelsLoading)CircularProgressIndicator(modifier=Modifier.align(Alignment.TopCenter)
                    .padding(top=22.dp).size(23.dp),color=Color(0xFFE6B94E))
                if(error.isNotBlank() && controls){
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom=72.dp),
                        horizontalAlignment=Alignment.CenterHorizontally){
                        Text(error,color=Color(0xFFFFAAAA),fontSize=12.sp)
                        if(isUploaded&&player!=null)TextButton(onClick={error="";player.prepare();player.play()}){
                            Text("إعادة المحاولة",color=Color(0xFFF6D776))
                        }
                    }
                }
                if(isUploaded && controls && player!=null && duration>0L){
                    Slider(value=progress,onValueChange={progress=it;player.seekTo((duration*it).toLong())},
                        modifier=Modifier.align(Alignment.BottomCenter).padding(horizontal=16.dp,vertical=24.dp),
                        colors=SliderDefaults.colors(thumbColor=Color(0xFFE6B94E),activeTrackColor=Color(0xFFE6B94E)))
                }
            }
            if(onReelsNavigate!=null){
                if(controls)PremiumBottomNav("ريلز",onReelsNavigate)
                else Box(Modifier.fillMaxWidth().height(24.dp).clickable{controls=true},
                    contentAlignment=Alignment.Center){
                    Box(Modifier.width(40.dp).height(3.dp).background(Color(0xFFF1CB6E),RoundedCornerShape(30.dp)))
                }
            }
        }
        if(sheet!=null && loadInteractions!=null && sendComment!=null)key(id,sheet){
            PostInteractionsSheet(id,sheet!!,loadInteractions,sendComment,
                onStats={postId,data->
                    reactions=data.optInt("reactionCount",reactions)
                    comments=data.optInt("commentCount",comments)
                    onStats(postId,data)
                },
                onOpenProfile={username->dismiss();onOpenProfile(username)},
                onDismiss={sheet=null})
        }
    }
}
