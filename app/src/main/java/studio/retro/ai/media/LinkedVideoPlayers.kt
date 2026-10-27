package studio.retro.ai.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.abs

data class LinkedPlayback(val positionMs:Long=0,val durationMs:Long=0,val ready:Boolean=false,val playing:Boolean=false,val error:String?=null)

/** Main-thread playback pair. Original audio is the sole audio source. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class LinkedVideoPlayers(context:Context,original:File,enhanced:File) {
    private fun player()=ExoPlayer.Builder(context,DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build()
    val before=player(); val after=player()
    private var wanted=false; private var failure:String?=null; private var released=false
    init {
        val listener=object:Player.Listener {
            override fun onPlayerError(error:PlaybackException) { failure="This device cannot play both previews together. Choose Before or After for a single preview."; pause() }
        }
        before.addListener(listener); after.addListener(listener)
        before.volume=1f; after.volume=0f
        before.setMediaItem(MediaItem.fromUri(Uri.fromFile(original))); after.setMediaItem(MediaItem.fromUri(Uri.fromFile(enhanced)))
        before.prepare(); after.prepare()
    }
    private fun duration():Long=if(before.duration>0 && after.duration>0)minOf(before.duration,after.duration) else 0
    fun play() {
        if(released || failure!=null)return
        val duration=duration()
        if(duration>0 && before.currentPosition>=duration-30)seek(0)
        wanted=true
    }
    fun pause() { wanted=false; if(!released) { before.pause(); after.pause() } }
    fun seek(positionMs:Long) {
        if(released)return
        val target=positionMs.coerceIn(0,duration().coerceAtLeast(0))
        before.seekTo(target); after.seekTo(target)
    }
    fun synchronize():LinkedPlayback {
        if(released)return LinkedPlayback()
        val duration=duration(); val position=before.currentPosition.coerceAtLeast(0)
        val ready=duration>0 && before.playbackState in setOf(Player.STATE_READY,Player.STATE_ENDED) && after.playbackState in setOf(Player.STATE_READY,Player.STATE_ENDED)
        if(wanted && (before.playbackState==Player.STATE_ENDED || after.playbackState==Player.STATE_ENDED || duration>0 && position>=duration-10))pause()
        if(wanted && ready && failure==null) {
            if(abs(after.currentPosition-position)>100)after.seekTo(position)
            before.play(); after.play()
        } else { before.pause(); after.pause() }
        return LinkedPlayback(position.coerceAtMost(duration.coerceAtLeast(0)),duration,ready,wanted,failure)
    }
    fun release() {
        if(released)return
        pause(); released=true; before.release(); after.release()
    }
}
