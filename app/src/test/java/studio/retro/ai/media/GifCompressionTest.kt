package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class GifCompressionTest {
    private fun decode(bytes:ByteArray):Any {
        val io=Class.forName("javax.imageio.ImageIO")
        return io.getMethod("read",java.io.InputStream::class.java).invoke(null,bytes.inputStream())
    }
    private fun rgb(image:Any,x:Int,y:Int):Int=Class.forName("java.awt.image.BufferedImage").getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(image,x,y) as Int
    @Test fun repetitivePixelsCompressAndIndependentlyDecode() {
        val out=ByteArrayOutputStream(); GifEncoder(out,256,256,false).use { it.frame(IntArray(256*256){0xffff0000.toInt()},100) }
        assertTrue("A flat 65k-pixel frame should compress below 8 KB",out.size()<8000)
        assertFalse(out.toString("ISO-8859-1").contains("NETSCAPE2.0"))
        val image=decode(out.toByteArray()); assertEquals(0xffff0000.toInt(),rgb(image,255,255))
    }
    @Test fun dictionaryResetsPreserveEveryQuantizedPixel() {
        val random=java.util.Random(42); val pixels=IntArray(137*83){-0x1000000 or random.nextInt(0x1000000)}
        val out=ByteArrayOutputStream(); GifEncoder(out,137,83,true).use { it.frame(pixels,120) }
        val image=decode(out.toByteArray())
        for(y in 0 until 83)for(x in 0 until 137) {
            val c=pixels[y*137+x]
            val r=(c ushr 21 and 7)*255/7; val g=(c ushr 13 and 7)*255/7; val b=(c ushr 6 and 3)*255/3
            assertEquals("Pixel $x,$y",-0x1000000 or (r shl 16) or (g shl 8) or b,rgb(image,x,y))
        }
    }
}
