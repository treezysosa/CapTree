package studio.retro.ai.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavPcm16 {
    fun header(sampleRate:Int,channels:Int,dataBytes:Int):ByteArray {
        require(sampleRate in 8000..192000 && channels in 1..8 && dataBytes in 0..64*1024*1024 && dataBytes%(channels*2)==0)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36+dataBytes); put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16); putShort(1); putShort(channels.toShort()); putInt(sampleRate); putInt(sampleRate*channels*2)
            putShort((channels*2).toShort()); putShort(16); put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
        }.array()
    }
}
