package studio.retro.ai.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.*
import java.io.File

/** Re-encoded thumbnails contain neither audio nor the source file's metadata. */
object HighlightFrames {
    suspend fun extract(source:File,startMs:Long,endMs:Long,directory:File,progress:suspend(Int)->Unit):List<Long> = withContext(Dispatchers.IO) {
        val times=SemanticHighlights.sampleTimes(endMs-startMs)
        require(startMs>=0)
        directory.mkdirs()
        val reader=MediaMetadataRetriever()
        try {
            reader.setDataSource(source.path)
            val duration=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("Cannot read video duration.")
            require(endMs<=duration) { "The selected trim extends beyond this video." }
            if(Build.VERSION.SDK_INT<27) {
                val width=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0
                val height=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 0
                require(width>0 && height>0 && width*height<=4_000_000) { "Android 8.0 frame sampling supports source videos up to four megapixels. Use a smaller video or Android 8.1+." }
            }
            times.forEachIndexed { index,time ->
                currentCoroutineContext().ensureActive()
                val frame=if(Build.VERSION.SDK_INT>=27)reader.getScaledFrameAtTime((startMs+time)*1000,MediaMetadataRetriever.OPTION_CLOSEST,256,256) else reader.getFrameAtTime((startMs+time)*1000,MediaMetadataRetriever.OPTION_CLOSEST)
                requireNotNull(frame) { "Cannot decode sampled frame ${index+1}." }
                try {
                    val edge=maxOf(frame.width,frame.height)
                    val small=if(edge>256)Bitmap.createScaledBitmap(frame,maxOf(1,frame.width*256/edge),maxOf(1,frame.height*256/edge),true) else frame
                    try {
                        val output=File(directory,"${index.toString().padStart(4,'0')}.jpg")
                        output.outputStream().use { require(small.compress(Bitmap.CompressFormat.JPEG,85,it)) { "Cannot encode sampled frame." } }
                        require(output.length() in 1..256L*1024) { "Sampled frame exceeds the upload limit." }
                    } finally { if(small!==frame)small.recycle() }
                } finally { frame.recycle() }
                progress((index+1)*100/times.size)
            }
            times
        } catch(t:Throwable) { directory.deleteRecursively(); throw t }
        finally { reader.release() }
    }
}
