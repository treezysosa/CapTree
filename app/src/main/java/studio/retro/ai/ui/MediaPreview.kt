package studio.retro.ai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.view.View
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import coil.compose.AsyncImage
import java.io.File

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable fun MediaPreview(path:String,mime:String,modifier:Modifier=Modifier,seekPositionMs:Long?=null,rangeStartMs:Long=0,rangeEndMs:Long?=null,onPositionChanged:((Long)->Unit)?=null) {
    val context=LocalContext.current
    if(mime.startsWith("video")) {
        val player=remember(path,rangeStartMs,rangeEndMs) { ExoPlayer.Builder(context).build().apply {
            val item=MediaItem.Builder().setUri(android.net.Uri.fromFile(File(path)))
            if(rangeEndMs!=null && rangeEndMs>rangeStartMs)item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(rangeStartMs).setEndPositionMs(rangeEndMs).build())
            setMediaItem(item.build()); prepare()
        } }
        val positionCallback by rememberUpdatedState(onPositionChanged)
        LaunchedEffect(player,seekPositionMs) {
            seekPositionMs?.let { player.seekTo((it-rangeStartMs).coerceAtLeast(0)) }
            while(isActive && positionCallback!=null) {
                // Preparing/buffering can temporarily report zero, before the seek is applied.
                if(player.playbackState==Player.STATE_READY || player.playbackState==Player.STATE_ENDED)
                    positionCallback?.invoke(player.currentPosition+rangeStartMs)
                delay(100)
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        AndroidView(factory={ PlayerView(it).apply { this.player=player } },update={ it.player=player },modifier=modifier.fillMaxWidth().height(230.dp))
    } else if(mime=="image/gif") {
        var playing by remember(path) { mutableStateOf(true) }
        var command by remember(path) { mutableIntStateOf(0) }
        Column { AndroidView(factory={ GifView(it,path) },update={ it.control(command,playing) },modifier=modifier.fillMaxWidth().height(230.dp)); Row { RaisedButton("PLAY",{ playing=true; command++ },color=LocalConsole.current.pink); Spacer(Modifier.width(10.dp)); RaisedButton("STOP",{ playing=false; command++ }) } }
    } else {
        var zoom by remember(path) { mutableFloatStateOf(1f) }
        Column {
            Box(modifier.fillMaxWidth().height(230.dp).background(Screen).clipToBounds().pointerInput(path) { detectTransformGestures { _,_,scale,_ -> zoom=(zoom*scale).coerceIn(1f,5f) } }) { AsyncImage(File(path),"Photo preview",Modifier.fillMaxSize().graphicsLayer(scaleX=zoom,scaleY=zoom)) }
            RetroSlider("ZOOM ${"%.1f".format(zoom)}×",zoom,1f..5f) { zoom=it }
        }
    }
}
@Suppress("DEPRECATION")
internal class GifView(context:Context,path:String):View(context) {
    private val movie=File(path).inputStream().use { Movie.decodeStream(it) }
    private val loops=File(path).inputStream().use { stream -> val bytes=ByteArray(1024); val count=stream.read(bytes); count>0 && String(bytes,0,count,Charsets.ISO_8859_1).contains("NETSCAPE2.0") }
    private var position=0L; private var last=android.os.SystemClock.uptimeMillis()
    private var playing=true; private var command=-1
    fun control(sequence:Int,play:Boolean) {
        if(sequence!=command) { command=sequence; playing=play; position=0; last=android.os.SystemClock.uptimeMillis(); invalidate() }
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas); val m=movie ?: return
        val now=android.os.SystemClock.uptimeMillis(); if(playing)position+=now-last; last=now
        val duration=m.duration().coerceAtLeast(100)
        if(!loops && position>=duration) { position=duration-1L; playing=false }
        m.setTime(if(loops)(position%duration).toInt() else position.toInt())
        val scale=minOf(width.toFloat()/m.width(),height.toFloat()/m.height()); canvas.save(); canvas.translate((width-m.width()*scale)/2,(height-m.height()*scale)/2); canvas.scale(scale,scale); m.draw(canvas,0f,0f); canvas.restore()
        if(playing)postInvalidateDelayed(33)
    }
}
