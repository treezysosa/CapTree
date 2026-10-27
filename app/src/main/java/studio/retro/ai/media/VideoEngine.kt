package studio.retro.ai.media

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.effect.*
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

data class Segment(val file: File, val start: Long, val end: Long)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object VideoEngine {
    fun duration(file:File):Long {
        val reader=MediaMetadataRetriever()
        try { reader.setDataSource(file.path); return reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("Cannot read video duration.") } finally { reader.release() }
    }
    suspend fun export(context:Context, segments:List<Segment>, output:File, aspect:String, enhance:Float, captions:List<CaptionCue>, denoise:Boolean=false, sharpen:Boolean=false, progress:(Int?)->Unit) = withContext(Dispatchers.Main) {
        require(segments.isNotEmpty()) { "Import at least one video." }
        val effects=mutableListOf<Effect>()
        if(aspect!="Original") {
            val ratio=when(aspect){"9:16"->9f/16; "16:9"->16f/9; else->1f}
            effects+=Presentation.createForAspectRatio(ratio,Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        }
        if(enhance>0) { effects+=Brightness(0.06f*enhance); effects+=Contrast(0.15f*enhance); effects+=HslAdjustment.Builder().adjustSaturation(12f*enhance).build() }
        if(denoise || sharpen)effects+=DetailEffect(if(denoise)enhance else 0f,if(sharpen)enhance else 0f)
        if(captions.isNotEmpty()) {
            val overlay=object:TextOverlay() {
                override fun getText(presentationTimeUs:Long):android.text.SpannableString {
                    val time=presentationTimeUs/1000
                    val text=CaptionTrack.textAt(captions,time)
                    // Media3 1.6 TextOverlay cannot allocate a zero-width bitmap.
                    return android.text.SpannableString(text.ifBlank { " " }).apply { setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE),0,length,0); setSpan(android.text.style.BackgroundColorSpan(android.graphics.Color.argb(170,21,24,39)),0,length,0); setSpan(android.text.style.AbsoluteSizeSpan(28,true),0,length,0) }
                }
                override fun getOverlaySettings(presentationTimeUs:Long):OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(if(CaptionTrack.textAt(captions,presentationTimeUs/1000).isBlank())0f else 1f).setBackgroundFrameAnchor(0f,-0.82f).setOverlayFrameAnchor(0f,-1f).build()
            }
            effects+=OverlayEffect(listOf(overlay))
        }
        val items=segments.map { s ->
            EditRules.validateTrim(s.start,s.end,duration(s.file))
            val item=MediaItem.Builder().setUri(Uri.fromFile(s.file)).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.start).setEndPositionMs(s.end).build()).build()
            EditedMediaItem.Builder(item).build()
        }
        val composition=Composition.Builder(EditedMediaItemSequence(items)).setEffects(Effects(emptyList(),effects)).experimentalSetForceAudioTrack(true).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
        transform(context,composition,output,progress)
    }
    suspend fun imagesToMp4(context:Context, files:List<File>, output:File, fps:Int, progress:(Int?)->Unit) = withContext(Dispatchers.Main) {
        require(files.isNotEmpty())
        val items=files.map { EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(it)).setImageDurationMs(1000L/fps).build()).setFrameRate(fps).build() }
        transform(context,Composition.Builder(EditedMediaItemSequence(items)).build(),output,progress)
    }
    private suspend fun transform(context:Context,composition:Composition,output:File,progress:(Int?)->Unit) = suspendCancellableCoroutine<Unit> { continuation ->
        val handler=Handler(Looper.getMainLooper()); val holder=ProgressHolder()
        lateinit var poll:Runnable
        val transformer=Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(object:Transformer.Listener {
            override fun onCompleted(composition:Composition,exportResult:ExportResult) { handler.removeCallbacks(poll); if(continuation.isActive)continuation.resume(Unit) }
            override fun onError(composition:Composition,exportResult:ExportResult,exportException:ExportException) { handler.removeCallbacks(poll); if(continuation.isActive)continuation.resumeWithException(exportException) }
        }).build()
        poll=Runnable { if(continuation.isActive) { val state=transformer.getProgress(holder); progress(if(state==Transformer.PROGRESS_STATE_AVAILABLE)holder.progress else null); handler.postDelayed(poll,300) } }
        continuation.invokeOnCancellation { handler.post { handler.removeCallbacks(poll); transformer.cancel(); output.delete() } }
        try { transformer.start(composition,output.path); handler.post(poll) } catch(e:Exception) { handler.removeCallbacks(poll); if(continuation.isActive)continuation.resumeWithException(e) }
    }
    /** Histogram changes at one-second intervals: deterministic scene suggestions, not AI claims. */
    suspend fun scenes(file:File):List<Long> = withContext(Dispatchers.Default) {
        val result=mutableListOf<Long>(); var previous:FloatArray?=null
        val reader=MediaMetadataRetriever()
        try {
            reader.setDataSource(file.path)
            for(t in 0L until duration(file) step 1000L) {
                currentCoroutineContext().ensureActive()
                val b=if(android.os.Build.VERSION.SDK_INT>=27)reader.getScaledFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,64,64) else reader.getFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { source -> android.graphics.Bitmap.createScaledBitmap(source,64,64,true).also { if(it!==source)source.recycle() } }
                if(b==null)continue
                val hist=FloatArray(16); val pixels=IntArray(b.width*b.height); b.getPixels(pixels,0,b.width,0,0,b.width,b.height); b.recycle()
                pixels.forEach { val l=((it ushr 16 and 255)*54+(it ushr 8 and 255)*183+(it and 255)*19) shr 8; hist[l/16]+=1f/pixels.size }
                previous?.let { old -> if(hist.indices.sumOf { abs(hist[it]-old[it]).toDouble() }>0.65)result+=t }; previous=hist
            }
        } finally { reader.release() }
        result
    }
    /** Decode PCM and detect low-energy windows; requires an audio track. */
    suspend fun silence(file:File):List<Long> = withContext(Dispatchers.Default) {
        val extractor=MediaExtractor(); var codec:MediaCodec?=null
        try {
            extractor.setDataSource(file.path)
            val track=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true } ?: error("This video has no audio track.")
            extractor.selectTrack(track); val format=extractor.getTrackFormat(track); val decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec=decoder
            decoder.configure(format,null,null,0); decoder.start()
            val info=MediaCodec.BufferInfo(); var inputDone=false; var outputDone=false; val silent=mutableListOf<Long>(); var last=-1000L
            var pcmFloat=false
            while(!outputDone) {
                currentCoroutineContext().ensureActive()
                if(!inputDone) { val index=decoder.dequeueInputBuffer(10000); if(index>=0) { val size=extractor.readSampleData(decoder.getInputBuffer(index)!!,0); if(size<0) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true } else { decoder.queueInputBuffer(index,0,size,extractor.sampleTime,0); extractor.advance() } } }
                val index=decoder.dequeueOutputBuffer(info,10000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val f=decoder.outputFormat; pcmFloat=f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING)==android.media.AudioFormat.ENCODING_PCM_FLOAT }
                if(index>=0) {
                    val buffer=decoder.getOutputBuffer(index)!!.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN); buffer.position(info.offset); buffer.limit(info.offset+info.size)
                    var energy=0.0; var count=0
                    while(buffer.remaining()>=(if(pcmFloat)4 else 2)) { val sample=if(pcmFloat)buffer.float.toDouble() else buffer.short/32768.0; energy+=sample*sample; count++ }
                    val ms=info.presentationTimeUs/1000
                    if(count>0 && sqrt(energy/count)<0.012 && ms-last>=500) { silent+=ms; last=ms }
                    outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM !=0; decoder.releaseOutputBuffer(index,false)
                }
            }
            silent
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
}
