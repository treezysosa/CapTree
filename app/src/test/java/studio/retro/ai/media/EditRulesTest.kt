package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

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
        // Android's Kotlin compile classpath omits java.desktop. Reflectively use the
        // independent JDK ImageIO decoder when the test runs on the full desktop JVM.
        val imageIO=Class.forName("javax.imageio.ImageIO")
        val input=imageIO.getMethod("createImageInputStream",Any::class.java).invoke(null,out.toByteArray().inputStream())
        val readers=imageIO.getMethod("getImageReadersByFormatName",String::class.java).invoke(null,"gif") as Iterator<*>
        val reader=readers.next()!!
        val type=Class.forName("javax.imageio.ImageReader")
        val integer=Int::class.javaPrimitiveType!!
        fun read(index:Int):Any=type.getMethod("read",integer).invoke(reader,index)
        fun rgb(image:Any,x:Int,y:Int):Int=Class.forName("java.awt.image.BufferedImage").getMethod("getRGB",integer,integer).invoke(image,x,y) as Int
        try {
            type.getMethod("setInput",Any::class.java).invoke(reader,input)
            assertEquals(2,type.getMethod("getNumImages",Boolean::class.javaPrimitiveType!!).invoke(reader,true))
            assertEquals(0xffff0000.toInt(),rgb(read(0),0,0))
            assertEquals(0xff0000ff.toInt(),rgb(read(1),19,19))
            val raw=type.getMethod("getImageMetadata",integer).invoke(reader,1)
            val metadata=Class.forName("javax.imageio.metadata.IIOMetadata").getMethod("getAsTree",String::class.java).invoke(raw,"javax_imageio_gif_image_1.0") as org.w3c.dom.Node
            var child=metadata.firstChild; var delay=""
            while(child!=null) { if(child.nodeName=="GraphicControlExtension")delay=child.attributes.getNamedItem("delayTime").nodeValue; child=child.nextSibling }
            assertEquals("25",delay)
        } finally { type.getMethod("dispose").invoke(reader); Class.forName("javax.imageio.stream.ImageInputStream").getMethod("close").invoke(input) }
    }
}
