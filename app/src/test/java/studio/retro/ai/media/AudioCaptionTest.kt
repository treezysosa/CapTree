package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioCaptionTest {
    @Test fun wavHeaderDescribesActualPcmBytes() {
        val header=WavPcm16.header(16000,1,32000)
        val reader=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF",header.copyOfRange(0,4).toString(Charsets.US_ASCII))
        assertEquals("WAVE",header.copyOfRange(8,12).toString(Charsets.US_ASCII))
        assertEquals(32036,reader.getInt(4)); assertEquals(1,reader.getShort(20).toInt())
        assertEquals(1,reader.getShort(22).toInt()); assertEquals(16000,reader.getInt(24))
        assertEquals(32000,reader.getInt(28)); assertEquals(16,reader.getShort(34).toInt()); assertEquals(32000,reader.getInt(40))
    }
    @Test fun replacingOneClipCaptionsPreservesBothSidesOfASpanningCue() {
        val existing=listOf(CaptionCue(0,9000,"original"))
        val result=CaptionTrack.replaceRange(existing,listOf(CaptionCue(3500,4500,"new")),3000,6000)
        assertEquals("original",CaptionTrack.textAt(result,2000)); assertEquals("new",CaptionTrack.textAt(result,4000))
        assertEquals("",CaptionTrack.textAt(result,5000)); assertEquals("original",CaptionTrack.textAt(result,7000))
    }
}
