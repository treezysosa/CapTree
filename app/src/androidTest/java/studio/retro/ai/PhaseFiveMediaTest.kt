package studio.retro.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.widget.LinearLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.media.*
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PhaseFiveMediaTest {
    private val app:StudioApplication get()=ApplicationProvider.getApplicationContext()
    private fun file(ext:String)=File(app.cacheDir,"phase5-${UUID.randomUUID()}.$ext")
    private fun photo(color:Int)=file("png").also { output ->
        val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }; bitmap.recycle()
    }
    @Test fun adaptiveGifDecodesWithExactPastelsInBothAnimatedFrames():Unit=runBlocking {
        val colors=intArrayOf(Color.rgb(137,149,153),Color.rgb(217,171,185))
        val first=photo(colors[0]); val second=photo(colors[1]); val output=file("gif")
        try {
            GifEngine.create(listOf(first,second),false,output,0,1000,4,1f,160,true,"","",palette="Adaptive 64",dither=true) {}
            val movie=requireNotNull(output.inputStream().use { Movie.decodeStream(it) })
            assertEquals(500,movie.duration())
            val bitmap=Bitmap.createBitmap(movie.width(),movie.height(),Bitmap.Config.ARGB_8888)
            for(index in colors.indices) {
                movie.setTime(if(index==0)0 else 300); movie.draw(Canvas(bitmap),0f,0f)
                val actual=bitmap.getPixel(bitmap.width/2,bitmap.height/2)
                for(shift in listOf(0,8,16))assertTrue("Adaptive channel remains accurate",abs((actual ushr shift and 255)-(colors[index] ushr shift and 255))<=1)
            }
            bitmap.recycle()
        } finally { first.delete(); second.delete(); output.delete() }
    }

    @Test fun trimHistoryRestoresCaptionsAndRejectsIncompleteTrimExport():Unit=runBlocking {
        val vm=withContext(Dispatchers.Main) { StudioViewModel(app) }
        val clip=ClipEdit("/local/test.mp4",0,2400,2400)
        val captions=CaptionTrack.srt(listOf(CaptionCue(300,2000,"keep with source")))
        withContext(Dispatchers.Main) {
            vm.open("clipper")
            vm.editor.value=EditorState(tool="clipper",inputs=listOf(clip.path),video=true,end="2.4",clips=listOf(clip),captions=captions)
            vm.edit("trim-end") { it.copy(end="1.5") }
            assertEquals(listOf(CaptionCue(300,1500,"keep with source")),CaptionTrack.parse(vm.editor.value.captions,1500))
            vm.undo(); assertEquals(2400L,vm.editor.value.clips[0].endMs); assertEquals(captions,vm.editor.value.captions)
            vm.redo(); assertEquals(1500L,vm.editor.value.clips[0].endMs)
            vm.edit("trim-end") { it.copy(end="2.4") }
            assertEquals(listOf(CaptionCue(300,2000,"keep with source")),CaptionTrack.parse(vm.editor.value.captions,2400))
            vm.edit("trim-end") { it.copy(end="0.2") }; assertTrue(vm.editor.value.captions.isEmpty())
            vm.edit("trim-end") { it.copy(end="1.5") }
            assertEquals(listOf(CaptionCue(300,1500,"keep with source")),CaptionTrack.parse(vm.editor.value.captions,1500))
            vm.seek(0)
            vm.edit("trim-end") { it.copy(end="") }; assertEquals(1500L,vm.editor.value.clips[0].endMs)
            vm.create()
        }
        withTimeout(10000) { vm.message.first { it.contains("valid trim times") } }
        withContext(Dispatchers.Main) { vm.undo(); assertEquals("1.5",vm.editor.value.end) }
    }

    @Test fun linkedPlayersDecodeSeekPlayAndPauseTheActualOriginalAndEnhancedVideo():Unit=runBlocking {
        val input=file("mp4"); val enhanced=file("mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { source -> input.outputStream().use { source.copyTo(it) } }
        try {
            withTimeout(90000) { VideoEngine.export(app,listOf(Segment(input,0,2000)),enhanced,"Original",0.6f,emptyList()) {} }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var holder:LinkedVideoPlayers?=null
                try {
                    scenario.onActivity { activity ->
                        val players=LinkedVideoPlayers(activity,input,enhanced); holder=players
                        val row=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL }
                        for(player in listOf(players.before,players.after))row.addView(PlayerView(activity).apply { this.player=player; useController=false },LinearLayout.LayoutParams(0,400,1f))
                        activity.setContentView(row)
                    }
                    val players=requireNotNull(holder)
                    suspend fun waitFor(predicate:(LinkedPlayback)->Boolean):LinkedPlayback=withTimeout(20000) {
                        while(true) {
                            val state=withContext(Dispatchers.Main) { players.synchronize() }
                            assertNull(state.error)
                            if(predicate(state))return@withTimeout state
                            delay(80)
                        }
                        @Suppress("UNREACHABLE_CODE") error("unreachable")
                    }
                    waitFor { it.ready }
                    withContext(Dispatchers.Main) { players.seek(500); players.play() }
                    val played=waitFor { it.positionMs>=1000 && it.ready }
                    assertTrue(played.durationMs in 1900L..2200L)
                    withContext(Dispatchers.Main) {
                        assertNotNull(players.before.videoFormat); assertNotNull(players.after.videoFormat)
                        assertTrue("Both decoded videos share the playhead",abs(players.before.currentPosition-players.after.currentPosition)<180)
                        assertEquals(1f,players.before.volume,0.001f); assertEquals(0f,players.after.volume,0.001f)
                        players.pause(); players.seek(700)
                    }
                    waitFor { it.ready && abs(it.positionMs-700)<80 }
                    val paused=withContext(Dispatchers.Main) { players.before.currentPosition }
                    delay(350)
                    withContext(Dispatchers.Main) { players.synchronize(); assertTrue("Paused playback stays still",abs(players.before.currentPosition-paused)<50) }
                } finally { withContext(Dispatchers.Main) { holder?.release() } }
            }
        } finally { input.delete(); enhanced.delete() }
    }
}
