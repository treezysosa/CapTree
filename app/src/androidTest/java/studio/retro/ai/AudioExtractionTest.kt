package studio.retro.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.media.AudioEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioExtractionTest {
    @Test fun selectedAacRangeBecomesARealBoundedPcmWav():Unit=runBlocking {
        val app:StudioApplication=ApplicationProvider.getApplicationContext()
        val input=File(app.cacheDir,"audio-${UUID.randomUUID()}.mp4"); val output=File(app.cacheDir,"selected-${UUID.randomUUID()}.wav")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { source -> input.outputStream().use { source.copyTo(it) } }
            var progress=0
            AudioEngine.extractWav(input,500,1500,output) { progress=it }
            val bytes=output.readBytes(); val header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF",bytes.copyOfRange(0,4).toString(Charsets.US_ASCII)); assertEquals("WAVE",bytes.copyOfRange(8,12).toString(Charsets.US_ASCII))
            val channels=header.getShort(22).toInt(); val rate=header.getInt(24); val size=header.getInt(40)
            assertEquals(bytes.size-44,size); assertEquals(16,header.getShort(34).toInt()); assertEquals(100,progress)
            val duration=size*1000L/(channels*rate*2)
            assertTrue("Selected WAV duration: $duration ms",duration in 970..1030)
            assertTrue(bytes.drop(44).any { it.toInt()!=0 })
        } finally { input.delete(); output.delete() }
    }
}
