package studio.retro.ai.media

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.media3.effect.MatrixTransformation
import kotlinx.coroutines.*
import java.io.File

data class StabilizationProfile(val corrections:List<CameraPoint>,val strength:Float,val width:Int=96,val height:Int=72)

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
object Stabilizer {
    suspend fun analyze(file:File,strength:Float,progress:suspend(Int)->Unit):StabilizationProfile=withContext(Dispatchers.Default) {
        require(strength in 0f..1f)
        val duration=VideoEngine.duration(file)
        require(duration<=120000) { "Translation stabilization supports videos up to two minutes. Trim a longer source in AI Clipper first." }
        val reader=MediaMetadataRetriever(); val path=ArrayList<CameraPoint>(); var previous:IntArray?=null
        var x=0f; var y=0f; var segment=0; var matches=0
        try {
            reader.setDataSource(file.path)
            for(time in 0L until duration step 100L) {
                currentCoroutineContext().ensureActive()
                val decoded=if(android.os.Build.VERSION.SDK_INT>=27)reader.getScaledFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST,96,72) else reader.getFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST)
                requireNotNull(decoded) { "Cannot decode this video for stabilization." }
                val scaled=Bitmap.createScaledBitmap(decoded,96,72,true)
                val pixels=IntArray(96*72); scaled.getPixels(pixels,0,96,0,0,96,72)
                val gray=IntArray(pixels.size) { i -> val c=pixels[i]; ((c ushr 16 and 255)*54+(c ushr 8 and 255)*183+(c and 255)*19) shr 8 }
                if(scaled!==decoded)scaled.recycle(); decoded.recycle()
                previous?.let { before ->
                    val match=CameraMotion.estimate(before,gray,96,72)
                    if(match.reliable) { x+=match.dx; y+=match.dy; matches++ } else { segment++; x=0f; y=0f }
                }
                path+=CameraPoint(time,x,y,segment); previous=gray
                progress((time*100/duration).toInt())
            }
        } finally { reader.release() }
        require(path.size>=3 && matches>=2) { "Not enough consistent image texture for translation stabilization." }
        progress(100)
        StabilizationProfile(CameraMotion.corrections(path,strength=strength),strength)
    }

    fun effect(profile:StabilizationProfile):MatrixTransformation=object:MatrixTransformation {
        override fun getMatrix(presentationTimeUs:Long):Matrix {
            val point=CameraMotion.at(profile.corrections,presentationTimeUs/1000)
            val zoom=1f+0.22f*profile.strength; val border=1f-1f/zoom
            val dx=(2*point.x/profile.width).coerceIn(-border,border)
            val dy=(-2*point.y/profile.height).coerceIn(-border,border)
            return Matrix().apply { setScale(zoom,zoom); postTranslate(dx*zoom,dy*zoom) }
        }
    }
}
