package studio.retro.ai.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Bounded-memory conversion of AudioEngine's canonical PCM16 WAV to mono 16 kHz. */
object SpeechPcm {
    data class Wave(val rate:Int,val channels:Int,val frames:Int)
    fun inspect(file:File):Wave=RandomAccessFile(file,"r").use { input ->
        require(file.length() in 46..64L*1024*1024+44) { "Invalid decoded audio size." }
        val bytes=ByteArray(44); input.readFully(bytes); val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun text(offset:Int,count:Int)=String(bytes,offset,count,Charsets.US_ASCII)
        require(text(0,4)=="RIFF" && text(8,8)=="WAVEfmt " && b.getInt(16)==16 && b.getShort(20).toInt()==1 && b.getShort(34).toInt()==16 && text(36,4)=="data") { "Use canonical 16-bit PCM WAV audio." }
        val rate=b.getInt(24); val channels=b.getShort(22).toInt(); val size=b.getInt(40)
        require(rate in 8000..192000 && channels in 1..8 && size>0 && size%(channels*2)==0 && file.length()==44L+size && b.getInt(4).toLong()+8==file.length() && b.getInt(28)==rate*channels*2 && b.getShort(32).toInt()==channels*2) { "Invalid PCM WAV header or sample count." }
        Wave(rate,channels,size/(channels*2))
    }
    fun convert(source:File,output:File,check:()->Unit={},progress:(Int)->Unit={}) {
        val wave=inspect(source)
        require(wave.frames.toLong()*1000/wave.rate<=120000) { "Offline subtitles support a selected trim of up to two minutes." }
        val count=(wave.frames.toLong()*16000/wave.rate).toInt()
        require(count>0)
        // A fractional-delay low-pass kernel prevents high-frequency aliasing when downsampling.
        val cutoff=0.45*minOf(1.0,16000.0/wave.rate)
        val kernels=Array(256) { phase ->
            val fraction=phase/256.0
            DoubleArray(33) { tap ->
                val x=tap-16-fraction
                val sinc=if(abs(x)<1e-12)2*cutoff else sin(2*PI*cutoff*x)/(PI*x)
                sinc*(0.5+0.5*cos(PI*x/17))
            }.also { values -> val sum=values.sum(); for(i in values.indices)values[i]/=sum }
        }
        try {
            RandomAccessFile(source,"r").use { input ->
                val blocks=IntArray(3) { -1 }; val cache=arrayOfNulls<DoubleArray>(3)
                fun sample(index:Int):Double {
                    val safe=index.coerceIn(0,wave.frames-1); val block=safe/4096
                    val slot=block%3
                    val samples=if(blocks[slot]==block)cache[slot]!! else run {
                        val start=block*4096; val frames=minOf(4096,wave.frames-start)
                        val raw=ByteArray(frames*wave.channels*2); input.seek(44L+start.toLong()*wave.channels*2); input.readFully(raw)
                        val data=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                        DoubleArray(frames) { var sum=0.0; repeat(wave.channels) { sum+=data.short.toDouble() }; sum/wave.channels }.also { cache[slot]=it; blocks[slot]=block }
                    }
                    return samples[safe%4096]
                }
                output.outputStream().buffered().use { sink ->
                    sink.write(WavPcm16.header(16000,1,count*2))
                    val buffer=ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
                    for(index in 0 until count) {
                        if(index%4096==0) { check(); progress((index.toLong()*100/count).toInt()) }
                        val position=index.toDouble()*wave.rate/16000; val center=position.toInt()
                        val weights=kernels[((position-center)*256).toInt().coerceIn(0,255)]
                        var value=0.0
                        for(tap in weights.indices)value+=sample(center+tap-16)*weights[tap]
                        buffer.putShort(value.roundToInt().coerceIn(-32768,32767).toShort())
                        if(!buffer.hasRemaining()) { sink.write(buffer.array()); buffer.clear() }
                    }
                    if(buffer.position()>0)sink.write(buffer.array(),0,buffer.position())
                }
            }
            check(); progress(100)
        } catch(t:Throwable) { output.delete(); throw t }
    }
}
