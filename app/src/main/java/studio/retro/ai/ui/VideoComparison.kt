package studio.retro.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import studio.retro.ai.media.LinkedPlayback
import studio.retro.ai.media.LinkedVideoPlayers
import java.io.File

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@Composable fun VideoComparison(original:String,enhanced:String) {
    val context=LocalContext.current; val lifecycle=LocalLifecycleOwner.current.lifecycle
    val players=remember(context,lifecycle,original,enhanced) { LinkedVideoPlayers(context,File(original),File(enhanced)) }
    var state by remember(players) { mutableStateOf(LinkedPlayback()) }
    DisposableEffect(players,lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP)players.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); players.release() }
    }
    LaunchedEffect(players) { while(isActive) { state=players.synchronize(); delay(100) } }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        PixelText("LINKED PREVIEW",size=8,color=LocalConsole.current.lcd)
        Text("Shared playhead · original audio",fontSize=11.sp,color=LocalConsole.current.accent)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text("ORIGINAL",fontSize=10.sp)
                AndroidView(factory={ PlayerView(it).apply { useController=false; player=players.before; keepScreenOn=false } },update={ it.player=players.before },modifier=Modifier.fillMaxWidth().height(200.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("ENHANCED",fontSize=10.sp)
                AndroidView(factory={ PlayerView(it).apply { useController=false; player=players.after; keepScreenOn=false } },update={ it.player=players.after },modifier=Modifier.fillMaxWidth().height(200.dp))
            }
        }
        if(state.error!=null)Text(state.error.orEmpty(),fontSize=12.sp,color=LocalConsole.current.pink) else {
            if(!state.ready)Text(if(state.playing)"Buffering linked previews…" else "Loading video previews…",fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                RaisedButton(if(state.playing)"PAUSE BOTH" else "PLAY BOTH",{ if(state.playing)players.pause() else players.play(); state=players.synchronize() },Modifier.weight(1f),enabled=state.durationMs>0)
                RaisedButton("RESTART",{ players.seek(0); state=players.synchronize() },Modifier.weight(1f),enabled=state.durationMs>0)
            }
            if(state.durationMs>0)RetroSlider("PLAYHEAD · ${"%.2f".format(state.positionMs/1000.0)} / ${"%.2f".format(state.durationMs/1000.0)}s",state.positionMs.toFloat(),0f..state.durationMs.toFloat()) { players.seek(it.toLong()); state=players.synchronize() }
        }
    }
}
