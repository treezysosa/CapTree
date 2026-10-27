package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class EditRulesTest {
    @Test fun frameTimesRespectSpeedAndTrim() {
        assertEquals(listOf(1000L,1250L,1500L,1750L),EditRules.frameTimes(1000,2000,8,2f))
    }
    @Test(expected=IllegalArgumentException::class) fun hugeGifsAreRejectedBeforeDecoding() { EditRules.frameTimes(0,60000,24,1f) }
    @Test(expected=IllegalArgumentException::class) fun invalidTrimRejected() { EditRules.validateTrim(1000,500,4000) }
    @Test fun resizeKeepsAspectWithoutUpscaling() { assertEquals(320 to 180,EditRules.outputSize(1920,1080,320)); assertEquals(100 to 50,EditRules.outputSize(100,50,320)) }
    @Test fun gifContainsMultipleDecodableFramesAndTiming() {
        val out=ByteArrayOutputStream()
        GifEncoder(out,20,20,true).use { it.frame(IntArray(400){0xffff0000.toInt()},125); it.frame(IntArray(400){0xff0000ff.toInt()},250) }
        val input=ImageIO.createImageInputStream(out.toByteArray().inputStream())
        val reader=ImageIO.getImageReadersByFormatName("gif").next()
        try {
            reader.input=input
            assertEquals(2,reader.getNumImages(true))
            assertEquals(0xffff0000.toInt(),reader.read(0).getRGB(0,0))
            assertEquals(0xff0000ff.toInt(),reader.read(1).getRGB(19,19))
            val metadata=reader.getImageMetadata(1).getAsTree("javax_imageio_gif_image_1.0")
            var child=metadata.firstChild; var delay=""
            while(child!=null) { if(child.nodeName=="GraphicControlExtension")delay=child.attributes.getNamedItem("delayTime").nodeValue; child=child.nextSibling }
            assertEquals("25",delay)
        } finally { reader.dispose(); input.close() }
    }
}
