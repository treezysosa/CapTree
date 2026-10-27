package studio.retro.ai

import android.graphics.*
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.media.*
import studio.retro.ai.ui.GifView
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class PhaseFourMediaTest {
    private val app:StudioApplication get()=ApplicationProvider.getApplicationContext()
    private fun output(ext:String)=File(app.cacheDir,"phase4-${UUID.randomUUID()}.$ext")
    private fun fixture(name:String):File=output("mp4").also { file -> InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { source -> file.outputStream().use { source.copyTo(it) } } }
    private fun photo():File=output("png").also { file -> val bitmap=Bitmap.createBitmap(160,112,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(25,35,55)) }; file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }

    @Test fun styledImageMp4HasRealTextAndStickerPixelsAndAdjustedDuration():Unit=runBlocking {
        val first=photo(); val second=photo(); val mp4=output("mp4")
        try {
            withTimeout(90000) { VideoEngine.imagesToMp4(app,listOf(first,second),mp4,4,AnimationOptions(4,0.5f,160,"RETRO VIBES","★")) {} }
            assertTrue("Adjusted image animation duration",VideoEngine.duration(mp4) in 850L..1300L)
            val reader=MediaMetadataRetriever()
            try {
                reader.setDataSource(mp4.path); val bitmap=requireNotNull(reader.getFrameAtTime(200000,MediaMetadataRetriever.OPTION_CLOSEST))
                fun white(top:Int,bottom:Int):Int { var n=0; for(y in top until bottom)for(x in 0 until bitmap.width) { val c=bitmap.getPixel(x,y); if((c ushr 16 and 255)>180 && (c ushr 8 and 255)>180 && (c and 255)>180)n++ }; return n }
                assertTrue("Sticker is visibly encoded",white(0,bitmap.height/2)>8)
                assertTrue("Caption is visibly encoded",white(bitmap.height/2,bitmap.height)>15)
                bitmap.recycle()
            } finally { reader.release() }
        } finally { first.delete(); second.delete(); mp4.delete() }
    }

    @Test fun videoAnimationSpeedKeepsAudioAndVideoAligned():Unit=runBlocking {
        val input=fixture("fixture.mp4"); val mp4=output("mp4")
        try {
            withTimeout(90000) { VideoEngine.export(app,listOf(Segment(input,0,1600)),mp4,"Original",0f,emptyList(),animation=AnimationOptions(12,2f,160,"FAST","★")) {} }
            assertTrue("2x speed must shorten the 1.6-second range",VideoEngine.duration(mp4) in 650L..1100L)
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(mp4.path)
                val times=ArrayList<Long>()
                assertTrue((0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true })
                for(track in 0 until extractor.trackCount) {
                    extractor.selectTrack(track); extractor.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC); var last=0L
                    while(extractor.sampleTime>=0) { last=extractor.sampleTime; if(!extractor.advance())break }
                    times+=last; extractor.unselectTrack(track)
                }
                assertTrue("Speed-adjusted audio/video ends differ by at most 250 ms",times.max()-times.min()<250000)
            } finally { extractor.release() }
        } finally { input.delete(); mp4.delete() }
    }

    @Test fun videoOverlaysEncodeAndTimedCaptionsDisappear():Unit=runBlocking {
        val image=photo(); val input=output("mp4"); val mp4=output("mp4")
        try {
            withTimeout(90000) {
                VideoEngine.imagesToMp4(app,List(10){image},input,10,AnimationOptions(10,1f,160)) {}
                VideoEngine.export(app,listOf(Segment(input,0,950)),mp4,"Original",0f,listOf(CaptionCue(0,400,"RETRO VIBES")),animation=AnimationOptions(10,1f,160,sticker="★")) {}
            }
            val reader=MediaMetadataRetriever()
            try {
                reader.setDataSource(mp4.path)
                fun pixels(timeUs:Long):Pair<Int,Int> {
                    val bitmap=requireNotNull(reader.getFrameAtTime(timeUs,MediaMetadataRetriever.OPTION_CLOSEST))
                    var top=0; var bottom=0
                    for(y in 0 until bitmap.height)for(x in 0 until bitmap.width) {
                        val c=bitmap.getPixel(x,y)
                        if((c ushr 16 and 255)>180 && (c ushr 8 and 255)>180 && (c and 255)>180) { if(y<bitmap.height/2)top++ else bottom++ }
                    }
                    bitmap.recycle(); return top to bottom
                }
                val active=pixels(200000); val expired=pixels(700000)
                assertTrue("Video sticker is visibly encoded",active.first>8 && expired.first>8)
                assertTrue("Timed video caption is visibly encoded",active.second>15)
                assertEquals("Expired caption leaves the lower frame clear",0,expired.second)
            } finally { reader.release() }
        } finally { image.delete(); input.delete(); mp4.delete() }
    }

    @Test fun stabilizationReducesMeasuredJitterInKnownShakyFootage():Unit=runBlocking {
        val input=fixture("shaky.mp4"); val mp4=output("mp4")
        try {
            withTimeout(90000) {
                val profile=Stabilizer.analyze(input,1f) {}
                VideoEngine.export(app,listOf(Segment(input,0,2800)),mp4,"Original",0f,emptyList(),stabilization=profile) {}
            }
            val before=jitter(input); val after=jitter(mp4)
            assertTrue("Fixture must contain measurable camera jitter: $before",before>1.0)
            assertTrue("Stabilized RMS jitter $after must improve on $before",after<before*0.85)
        } finally { input.delete(); mp4.delete() }
    }

    private fun jitter(file:File):Double {
        val reader=MediaMetadataRetriever(); var previous:IntArray?=null; val movement=ArrayList<Double>()
        try {
            reader.setDataSource(file.path)
            for(t in 200L..2400L step 100) {
                val raw=requireNotNull(reader.getFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST))
                val image=Bitmap.createScaledBitmap(raw,96,72,true); val pixels=IntArray(96*72); image.getPixels(pixels,0,96,0,0,96,72)
                if(image!==raw)image.recycle(); raw.recycle()
                val gray=IntArray(pixels.size) { val c=pixels[it]; ((c ushr 16 and 255)*54+(c ushr 8 and 255)*183+(c and 255)*19) shr 8 }
                previous?.let { val match=CameraMotion.estimate(it,gray,96,72); if(match.reliable)movement+=(match.dx*match.dx+match.dy*match.dy).toDouble() }
                previous=gray
            }
        } finally { reader.release() }
        assertTrue("Enough reliable motion samples",movement.size>=12)
        return sqrt(movement.average())
    }

    @Test fun oneShotGifStopsAtTheLastFrameAndCanRestart() {
        val file=output("gif")
        try {
            file.outputStream().use { stream -> GifEncoder(stream,48,32,false).use { encoder -> encoder.frame(IntArray(48*32){Color.RED},100); encoder.frame(IntArray(48*32){Color.BLUE},100) } }
            val instrumentation=InstrumentationRegistry.getInstrumentation(); lateinit var view:GifView
            val bitmap=Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync { view=GifView(app,file.path); view.layout(0,0,48,32); view.control(0,true); view.draw(Canvas(bitmap)) }
            assertEquals(Color.RED,bitmap.getPixel(24,16))
            SystemClock.sleep(350)
            instrumentation.runOnMainSync { view.draw(Canvas(bitmap)) }; assertEquals(Color.BLUE,bitmap.getPixel(24,16))
            SystemClock.sleep(250)
            instrumentation.runOnMainSync { view.draw(Canvas(bitmap)) }; assertEquals(Color.BLUE,bitmap.getPixel(24,16))
            instrumentation.runOnMainSync { view.control(1,true); view.draw(Canvas(bitmap)) }; assertEquals(Color.RED,bitmap.getPixel(24,16))
            bitmap.recycle()
        } finally { file.delete() }
    }
}
