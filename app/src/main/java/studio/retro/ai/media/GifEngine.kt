package studio.retro.ai.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

object GifEngine {
    suspend fun create(inputs:List<File>,video:Boolean,output:File,start:Long,end:Long,fps:Int,speed:Float,width:Int,loop:Boolean,caption:String,sticker:String,palette:String="Classic 256",dither:Boolean=false,progress:suspend(Int)->Unit) {
        require(inputs.isNotEmpty()) { "Import a video or images first." }
        require(palette in setOf("Classic 256","Adaptive 64","Adaptive 128","Adaptive 256")) { "Choose a supported GIF palette." }
        val times=if(video)EditRules.frameTimes(start,end,fps,speed) else inputs.indices.map { it.toLong() }
        require(times.size<=240) { "Maximum 240 GIF frames." }
        val reader=if(video)MediaMetadataRetriever().apply { setDataSource(inputs.first().path) } else null
        fun decode(index:Int):Bitmap = if(reader!=null) requireNotNull(reader.getFrameAtTime(times[index]*1000,MediaMetadataRetriever.OPTION_CLOSEST)) { "Cannot read video frame." } else PhotoEngine.read(inputs[index],1024)
        try {
            val first=decode(0); val (w,h)=EditRules.outputSize(first.width,first.height,width)
            output.outputStream().buffered().use { stream ->
                GifEncoder(stream,w,h,loop).use { encoder ->
                    for(i in times.indices) {
                        currentCoroutineContext().ensureActive()
                        val frame=if(i==0)first else decode(i)
                        val decorated=MediaDecoration.fit(frame,w,h,caption,sticker)
                        val pixels=IntArray(w*h); decorated.getPixels(pixels,0,w,0,0,w,h)
                        val delay=if(video)1000/fps else (1000f/fps/speed).toInt()
                        if(palette=="Classic 256")encoder.frame(pixels,delay) else {
                            val context=currentCoroutineContext()
                            encoder.frame(GifPalette.quantize(pixels,w,h,palette.substringAfterLast(' ').toInt(),dither) { context.ensureActive() },delay)
                        }
                        decorated.recycle(); frame.recycle()
                        progress((i+1)*100/times.size)
                    }
                }
            }
        } finally { reader?.release() }
    }
}
