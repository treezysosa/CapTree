package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.*

class SpeechPcmTest {
    private fun wave(rate:Int,channels:Int,seconds:Double=0.5,sample:(Int,Int)->Short):File=File.createTempFile("speech-test",".wav").also { file ->
        val frames=(seconds*rate).toInt(); val data=ByteBuffer.allocate(frames*channels*2).order(ByteOrder.LITTLE_ENDIAN)
        for(i in 0 until frames)repeat(channels) { channel -> data.putShort(sample(i,channel)) }
        file.outputStream().use { it.write(WavPcm16.header(rate,channels,data.capacity())); it.write(data.array()) }
    }
    private fun pcm(file:File):ShortArray { val b=ByteBuffer.wrap(file.readBytes(),44,(file.length()-44).toInt()).order(ByteOrder.LITTLE_ENDIAN); return ShortArray(b.remaining()/2) { b.short } }
    @Test fun stereoDownmixPreservesDurationAndRemovesOppositeChannels() {
        val source=wave(44100,2) { i,c -> ((if(c==0)1 else -1)*12000*sin(2*PI*440*i/44100)).toInt().toShort() }; val output=File.createTempFile("speech-mono",".wav")
        try {
            SpeechPcm.convert(source,output)
            assertEquals(SpeechPcm.Wave(16000,1,8000),SpeechPcm.inspect(output)); assertTrue(pcm(output).all { abs(it.toInt())<=1 })
        } finally { source.delete(); output.delete() }
    }
    @Test fun conversionPreservesSpeechBandAndAttenuatesAnAliasingTone() {
        fun amplitude(frequency:Double):Double {
            val source=wave(48000,1) { i,_ -> (14000*sin(2*PI*frequency*i/48000)).toInt().toShort() }; val output=File.createTempFile("speech-band",".wav")
            return try { SpeechPcm.convert(source,output); val samples=pcm(output).drop(100).dropLast(100); sqrt(samples.sumOf { it.toDouble()*it }/samples.size) } finally { source.delete(); output.delete() }
        }
        val speech=amplitude(1000.0); val ultrasonic=amplitude(12000.0)
        assertTrue(speech>9000); assertTrue("Downsampling must suppress out-of-band aliasing",ultrasonic<speech*0.1)
    }
    @Test fun eightKhzUpsamplingKeepsTheAudioClock() {
        val source=wave(8000,1,0.75) { _,_-> 2000 }; val output=File.createTempFile("speech-upsample",".wav")
        try { SpeechPcm.convert(source,output); assertEquals(12000,SpeechPcm.inspect(output).frames); assertTrue(pcm(output).all { abs(it-2000)<=1 }) } finally { source.delete(); output.delete() }
    }
    @Test fun cancellationRemovesPartialAudioAndMalformedHeadersAreRejected() {
        val source=wave(16000,1) { _,_-> 1000 }; val output=File.createTempFile("speech-cancel",".wav")
        try {
            try { SpeechPcm.convert(source,output,check={throw CancellationException()}); fail("Expected cancellation") } catch(expected:CancellationException) { assertFalse(output.exists()) }
            val bytes=source.readBytes(); bytes[34]=8; source.writeBytes(bytes)
            try { SpeechPcm.inspect(source); fail("Expected unsupported format rejection") } catch(expected:IllegalArgumentException) { /* rejected */ }
        } finally { source.delete(); output.delete() }
    }
}
