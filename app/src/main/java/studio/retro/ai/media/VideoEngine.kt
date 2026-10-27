package studio.retro.ai.media

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.audio.SpeedChangingAudioProcessor
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
    suspend fun export(context:Context, segments:List<Segment>, output:File, aspect:String, enhance:Float, captions:List<CaptionCue>, denoise:Boolean=false, sharpen:Boolean=false, animation:AnimationOptions?=null, stabilization:StabilizationProfile?=null, progress:(Int?)->Unit) = withContext(Dispatchers.Main) {
        require(segments.isNotEmpty()) { "Import at least one video." }
        val effects=mutableListOf<Effect>()
        if(aspect!="Original") {
            val ratio=when(aspect){"9:16"->9f/16; "16:9"->16f/9; else->1f}
            effects+=Presentation.createForAspectRatio(ratio,Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        }
        if(animation!=null) {
            val dimensions=frameSize(segments.first().file)
            val (w,h)=EditRules.outputSize(dimensions.first,dimensions.second,animation.longestEdge)
            effects+=Presentation.createForWidthAndHeight((w/2*2).coerceAtLeast(2),(h/2*2).coerceAtLeast(2),Presentation.LAYOUT_SCALE_TO_FIT)
            effects+=FrameDropEffect.createDefaultFrameDropEffect(animation.fps.toFloat())
        }
        if(stabilization!=null)effects+=Stabilizer.effect(stabilization)
        if(enhance>0) { effects+=Brightness(0.06f*enhance); effects+=Contrast(0.15f*enhance); effects+=HslAdjustment.Builder().adjustSaturation(12f*enhance).build() }
        if(denoise || sharpen)effects+=DetailEffect(if(denoise)enhance else 0f,if(sharpen)enhance else 0f)
        val totalMs=segments.sumOf { it.end-it.start }/(animation?.speed ?: 1f)
        val allCaptions=captions+if(animation?.caption?.isNotBlank()==true)listOf(CaptionCue(0,totalMs.toLong().coerceAtLeast(1),animation.caption)) else emptyList()
        if(allCaptions.isNotEmpty() || animation?.sticker?.isNotBlank()==true)effects+=OverlayEffect(listOf(CaptionOverlay(allCaptions,animation?.sticker.orEmpty())))
        val items=segments.map { s ->
            EditRules.validateTrim(s.start,s.end,duration(s.file))
            val item=MediaItem.Builder().setUri(Uri.fromFile(s.file)).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.start).setEndPositionMs(s.end).build()).build()
            val builder=EditedMediaItem.Builder(item)
            if(animation!=null && animation.speed!=1f) {
                val speed=constantSpeed(animation.speed)
                builder.setEffects(Effects(listOf(SpeedChangingAudioProcessor(speed)),listOf(SpeedChangeEffect(speed))))
            }
            builder.build()
        }
        val composition=Composition.Builder(EditedMediaItemSequence(items)).setEffects(Effects(emptyList(),effects)).experimentalSetForceAudioTrack(true).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
        transform(context,composition,output,progress)
    }
    suspend fun imagesToMp4(context:Context, files:List<File>, output:File, fps:Int, animation:AnimationOptions=AnimationOptions(fps=fps), preparing:(Int)->Unit={}, progress:(Int?)->Unit) {
        require(files.isNotEmpty()); require(fps*animation.speed<=60) { "Image MP4 animation supports an effective frame rate up to 60 FPS. Lower FPS or speed." }
        val directory=File(context.cacheDir,"mp4-frames-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val normalized=withContext(Dispatchers.IO) {
                val first=PhotoEngine.read(files.first(),1920)
                val (rawW,rawH)=EditRules.outputSize(first.width,first.height,animation.longestEdge); first.recycle()
                val w=(rawW/2*2).coerceAtLeast(2); val h=(rawH/2*2).coerceAtLeast(2)
                files.mapIndexed { index,file ->
                    currentCoroutineContext().ensureActive()
                    val source=PhotoEngine.read(file,1920); val bitmap=MediaDecoration.fit(source,w,h,animation.caption,animation.sticker)
                    val frame=File(directory,"$index.png")
                    try { frame.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { source.recycle(); bitmap.recycle() }
                    preparing((index+1)*100/files.size)
                    frame
                }
            }
            withContext(Dispatchers.Main) {
                val duration=(1000.0/(fps*animation.speed)).roundToLong().coerceAtLeast(17)
                val rate=ceil(fps*animation.speed).toInt().coerceAtLeast(1)
                val items=normalized.map { EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(it)).setImageDurationMs(duration).build()).setFrameRate(rate).build() }
                transform(context,Composition.Builder(EditedMediaItemSequence(items)).build(),output,progress)
            }
        } finally { directory.deleteRecursively() }
    }
    private fun constantSpeed(value:Float)=object:SpeedProvider {
        override fun getSpeed(timeUs:Long)=value
        override fun getNextSpeedChangeTimeUs(timeUs:Long)=C.TIME_UNSET
    }
    private fun frameSize(file:File):Pair<Int,Int> {
        val reader=MediaMetadataRetriever()
        try {
            reader.setDataSource(file.path)
            val width=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: error("Cannot read video width.")
            val height=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: error("Cannot read video height.")
            val rotation=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            return if(rotation%180==0)width to height else height to width
        } finally { reader.release() }
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
    suspend fun scenes(file:File,startMs:Long=0,endMs:Long=-1):List<Long> = withContext(Dispatchers.Default) {
        val end=if(endMs<0)duration(file) else endMs
        EditRules.validateTrim(startMs,end,duration(file))
        val result=mutableListOf<Long>(); var previous:FloatArray?=null
        val reader=MediaMetadataRetriever()
        try {
            reader.setDataSource(file.path)
            for(t in startMs until end step 1000L) {
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
    suspend fun silence(file:File,startMs:Long=0,endMs:Long=-1):List<Long> = withContext(Dispatchers.Default) {
        val end=if(endMs<0)duration(file) else endMs
        EditRules.validateTrim(startMs,end,duration(file))
        val extractor=MediaExtractor(); var codec:MediaCodec?=null
        try {
            extractor.setDataSource(file.path)
            val track=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true } ?: error("This video has no audio track.")
            extractor.selectTrack(track); extractor.seekTo(startMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC); val format=extractor.getTrackFormat(track); val decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec=decoder
            decoder.configure(format,null,null,0); decoder.start()
            val info=MediaCodec.BufferInfo(); var inputDone=false; var outputDone=false; val silent=mutableListOf<Long>(); var last=-1000L
            var pcmFloat=false
            while(!outputDone) {
                currentCoroutineContext().ensureActive()
                if(!inputDone) { val index=decoder.dequeueInputBuffer(10000); if(index>=0) { val size=extractor.readSampleData(decoder.getInputBuffer(index)!!,0); if(size<0 || extractor.sampleTime>=end*1000) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true } else { decoder.queueInputBuffer(index,0,size,extractor.sampleTime,0); extractor.advance() } } }
                val index=decoder.dequeueOutputBuffer(info,10000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val f=decoder.outputFormat; pcmFloat=f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING)==android.media.AudioFormat.ENCODING_PCM_FLOAT }
                if(index>=0) {
                    val buffer=decoder.getOutputBuffer(index)!!.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN); buffer.position(info.offset); buffer.limit(info.offset+info.size)
                    var energy=0.0; var count=0
                    while(buffer.remaining()>=(if(pcmFloat)4 else 2)) { val sample=if(pcmFloat)buffer.float.toDouble() else buffer.short/32768.0; energy+=sample*sample; count++ }
                    val ms=info.presentationTimeUs/1000
                    if(count>0 && ms in startMs until end && sqrt(energy/count)<0.012 && ms-last>=500) { silent+=ms; last=ms }
                    outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM !=0 || ms>=end; decoder.releaseOutputBuffer(index,false)
                }
            }
            silent
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
}
