package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class GifPaletteTest {
    private fun decoded(bytes:ByteArray,index:Int):Any {
        val io=Class.forName("javax.imageio.ImageIO")
        val stream=io.getMethod("createImageInputStream",Any::class.java).invoke(null,bytes.inputStream())
        val reader=io.getMethod("getImageReadersByFormatName",String::class.java).invoke(null,"gif") as Iterator<*>
        val imageReader=reader.next()!!; val type=Class.forName("javax.imageio.ImageReader")
        try {
            type.getMethod("setInput",Any::class.java).invoke(imageReader,stream)
            return type.getMethod("read",Int::class.javaPrimitiveType).invoke(imageReader,index)
        } finally { type.getMethod("dispose").invoke(imageReader); (stream as java.io.Closeable).close() }
    }
    private fun pixel(image:Any,x:Int,y:Int)=Class.forName("java.awt.image.BufferedImage").getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(image,x,y) as Int
    @Test fun localPalettesIndependentlyDecodeAndPreserveSubtleColorsInBothFrames() {
        val colors=intArrayOf(0xff899599.toInt(),0xffd9abb9.toInt()); val out=ByteArrayOutputStream()
        GifEncoder(out,48,32,true).use { encoder -> for(color in colors)encoder.frame(GifPalette.quantize(IntArray(48*32){color},48,32,64,true),100) }
        for(i in colors.indices)assertEquals(colors[i],pixel(decoded(out.toByteArray(),i),24,16))
    }
    @Test fun imagesWithinTheColorBudgetRemainExactIncludingAll256Grays() {
        val input=IntArray(256){i -> -0x1000000 or (i shl 16) or (i shl 8) or i}
        val frame=GifPalette.quantize(input,256,1,256,true)
        assertEquals(256,frame.palette.size)
        assertArrayEquals(input,IntArray(input.size){frame.palette[frame.indices[it].toInt() and 255]})
    }
    @Test fun adaptivePaletteImprovesPastelColorErrorAgainstTheClassicPalette() {
        val input=IntArray(128*64){i -> val x=i%128; val y=i/128; -0x1000000 or ((150+x*90/127) shl 16) or ((120+y*90/63) shl 8) or (135+(x+y)*90/190)}
        val frame=GifPalette.quantize(input,128,64,64)
        fun error(a:Int,b:Int):Long=(0..2).sumOf { val d=(a ushr (it*8) and 255)-(b ushr (it*8) and 255); (d*d).toLong() }
        val adaptive=input.indices.sumOf { error(input[it],frame.palette[frame.indices[it].toInt() and 255]) }
        val classic=input.sumOf { c -> val r=(c ushr 21 and 7)*255/7; val g=(c ushr 13 and 7)*255/7; val b=(c ushr 6 and 3)*255/3; error(c,-0x1000000 or (r shl 16) or (g shl 8) or b) }
        assertTrue("Adaptive $adaptive should improve on classic $classic",adaptive<classic/2)
        assertTrue(frame.palette.size<=64)
    }
    @Test fun ditheringImprovesAverageToneInSmallGradientBlocks() {
        val w=256; val h=64; val input=IntArray(w*h){p -> val x=p%w; -0x1000000 or (x shl 16) or (x shl 8) or x}
        fun error(dither:Boolean):Double {
            val frame=GifPalette.quantize(input,w,h,4,dither); var total=0.0
            for(y in 0 until h step 8)for(x in 0 until w step 8) {
                var actual=0; var expected=0
                for(dy in 0..7)for(dx in 0..7) { val p=(y+dy)*w+x+dx; actual+=frame.palette[frame.indices[p].toInt() and 255] and 255; expected+=input[p] and 255 }
                val difference=(actual-expected)/64.0; total+=difference*difference
            }
            return total
        }
        val plain=error(false); val dithered=error(true)
        assertTrue("Dithered block error $dithered should improve on $plain",dithered<plain*0.4)
    }
    @Test fun quantizationHonorsCancellation() {
        var calls=0
        try { GifPalette.quantize(IntArray(128*64){it},128,64,16) { if(++calls==2)throw java.util.concurrent.CancellationException() }; fail("Cancellation must propagate") } catch(expected:java.util.concurrent.CancellationException) { assertEquals(2,calls) }
    }
}
