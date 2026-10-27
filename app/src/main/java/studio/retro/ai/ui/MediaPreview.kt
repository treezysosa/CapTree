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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.io.File

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable fun MediaPreview(path:String,mime:String,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    if(mime.startsWith("video")) {
        val player=remember(path) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(path)))); prepare() } }
        DisposableEffect(player) { onDispose { player.release() } }
        AndroidView(factory={ PlayerView(it).apply { this.player=player } },update={ it.player=player },modifier=modifier.fillMaxWidth().height(230.dp))
    } else if(mime=="image/gif") {
        var playing by remember(path) { mutableStateOf(true) }
        Column { AndroidView(factory={ GifView(it,path) },update={ it.playing=playing; it.invalidate() },modifier=modifier.fillMaxWidth().height(230.dp)); Row { RaisedButton("PLAY",{ playing=true },color=LocalConsole.current.pink); Spacer(Modifier.width(10.dp)); RaisedButton("STOP",{ playing=false }) } }
    } else {
        var zoom by remember(path) { mutableFloatStateOf(1f) }
        Column {
            Box(modifier.fillMaxWidth().height(230.dp).background(Screen).clipToBounds().pointerInput(path) { detectTransformGestures { _,_,scale,_ -> zoom=(zoom*scale).coerceIn(1f,5f) } }) { AsyncImage(File(path),"Photo preview",Modifier.fillMaxSize().graphicsLayer(scaleX=zoom,scaleY=zoom)) }
            RetroSlider("ZOOM ${"%.1f".format(zoom)}×",zoom,1f..5f) { zoom=it }
        }
    }
}
@Suppress("DEPRECATION")
private class GifView(context:Context,path:String):View(context) {
    private val movie=File(path).inputStream().use { Movie.decodeStream(it) }
    private var position=0L; private var last=android.os.SystemClock.uptimeMillis()
    var playing=true
        set(value) { if(field!=value) { field=value; last=android.os.SystemClock.uptimeMillis() }; invalidate() }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas); val m=movie ?: return
        val now=android.os.SystemClock.uptimeMillis(); if(playing)position+=now-last; last=now
        val duration=m.duration().coerceAtLeast(100); m.setTime((position%duration).toInt())
        val scale=minOf(width.toFloat()/m.width(),height.toFloat()/m.height()); canvas.save(); canvas.translate((width-m.width()*scale)/2,(height-m.height()*scale)/2); canvas.scale(scale,scale); m.draw(canvas,0f,0f); canvas.restore()
        if(playing)postInvalidateDelayed(33)
    }
}
