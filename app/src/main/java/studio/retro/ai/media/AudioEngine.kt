package studio.retro.ai.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

object AudioEngine {
    /** Decodes only the selected range to PCM WAV. No video frames leave the device. */
    suspend fun extractWav(input:File,startMs:Long,endMs:Long,output:File,progress:suspend(Int)->Unit)=withContext(Dispatchers.IO) {
        EditRules.validateTrim(startMs,endMs,VideoEngine.duration(input))
        require(endMs-startMs<=600000) { "Automatic subtitles support up to ten minutes per selected clip." }
        val extractor=MediaExtractor(); var codec:MediaCodec?=null
        try {
            extractor.setDataSource(input.path)
            val track=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true } ?: error("The selected video has no audio track.")
            extractor.selectTrack(track); val format=extractor.getTrackFormat(track)
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec=decoder
            decoder.configure(format,null,null,0); decoder.start(); extractor.seekTo(startMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info=MediaCodec.BufferInfo(); var inputDone=false; var outputDone=false; var floatPcm=false; var bytes=0; var lastPercent=-1
            RandomAccessFile(output,"rw").use { sink ->
                sink.setLength(0); sink.write(ByteArray(44))
                while(!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if(!inputDone) {
                        val index=decoder.dequeueInputBuffer(10000)
                        if(index>=0) {
                            val size=extractor.readSampleData(decoder.getInputBuffer(index)!!,0)
                            if(size<0 || extractor.sampleTime>=endMs*1000) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true }
                            else { decoder.queueInputBuffer(index,0,size,extractor.sampleTime,0); extractor.advance() }
                        }
                    }
                    val index=decoder.dequeueOutputBuffer(info,10000)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val decoded=decoder.outputFormat
                        val newRate=decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE); val newChannels=decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(bytes==0 || (rate==newRate && channels==newChannels)) { "The audio format changes inside this clip." }
                        rate=newRate; channels=newChannels
                        require(rate in 8000..192000 && channels in 1..8) { "Unsupported audio channel count or sample rate." }
                        val encoding=if(decoded.containsKey(MediaFormat.KEY_PCM_ENCODING))decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding in listOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT)) { "Unsupported decoded audio format." }
                        floatPcm=encoding==AudioFormat.ENCODING_PCM_FLOAT
                    }
                    if(index>=0) {
                        val step=if(floatPcm)4 else 2; val frames=info.size/(step*channels)
                        val from=ceil((startMs*1000-info.presentationTimeUs)*rate/1_000_000.0).toInt().coerceIn(0,frames)
                        val to=ceil((endMs*1000-info.presentationTimeUs)*rate/1_000_000.0).toInt().coerceIn(from,frames)
                        if(to>from) {
                            val buffer=decoder.getOutputBuffer(index)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset+from*step*channels); buffer.limit(info.offset+to*step*channels)
                            val data=ByteArray((to-from)*channels*2); val destination=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                            repeat((to-from)*channels) { destination.putShort(if(floatPcm)(buffer.float.coerceIn(-1f,1f)*32767).roundToInt().toShort() else buffer.short) }
                            bytes+=data.size; require(bytes<=64*1024*1024) { "Selected audio exceeds the 64 MB upload limit. Trim the clip." }; sink.write(data)
                        }
                        val percent=((info.presentationTimeUs/1000-startMs)*100/(endMs-startMs)).toInt().coerceIn(0,100)
                        if(percent!=lastPercent) { progress(percent); lastPercent=percent }
                        outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0 || info.presentationTimeUs>=endMs*1000
                        decoder.releaseOutputBuffer(index,false)
                    }
                }
                require(bytes>0) { "The selected range contains no decodable audio." }
                sink.seek(0); sink.write(WavPcm16.header(rate,channels,bytes)); progress(100)
            }
        } catch(t:Throwable) { output.delete(); throw t }
        finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
}
