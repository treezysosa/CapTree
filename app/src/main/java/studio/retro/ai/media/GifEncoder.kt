package studio.retro.ai.media

import java.io.OutputStream

/** Streaming GIF89a writer with classic/global or adaptive/local palettes and bounded LZW. */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int, loop: Boolean) : AutoCloseable {
    init {
        require(width in 1..65535 && height in 1..65535)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        word(width); word(height); out.write(0xf7); out.write(0); out.write(0)
        for (i in 0..255) { out.write(((i shr 5) and 7) * 255 / 7); out.write(((i shr 2) and 7) * 255 / 7); out.write((i and 3) * 255 / 3) }
        if (loop) { out.write(byteArrayOf(0x21, 0xff.toByte(), 11)); out.write("NETSCAPE2.0".toByteArray()); out.write(byteArrayOf(3, 1, 0, 0, 0)) }
    }
    fun frame(argb: IntArray, delayMs: Int) {
        require(argb.size == width * height)
        val indices=ByteArray(argb.size) { i -> val c=argb[i]; (((c ushr 16 and 255) shr 5 shl 5) or ((c ushr 8 and 255) shr 5 shl 2) or ((c and 255) shr 6)).toByte() }
        writeFrame(indices,delayMs,null)
    }
    fun frame(indexed:IndexedFrame,delayMs:Int) {
        require(indexed.indices.size==width*height && indexed.palette.size in 1..256)
        require(indexed.indices.all { (it.toInt() and 255)<indexed.palette.size })
        writeFrame(indexed.indices,delayMs,indexed.palette)
    }
    private fun writeFrame(indices:ByteArray,delayMs:Int,palette:IntArray?) {
        out.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 4)); word((delayMs / 10).coerceIn(2, 65535)); out.write(0); out.write(0)
        out.write(0x2c); word(0); word(0); word(width); word(height)
        if(palette==null)out.write(0) else {
            var bits=1; while((1 shl bits)<palette.size)bits++
            out.write(0x80 or (bits-1))
            for(i in 0 until (1 shl bits)) { val c=palette.getOrElse(i){0}; out.write(c ushr 16 and 255); out.write(c ushr 8 and 255); out.write(c and 255) }
        }
        out.write(8)
        val block = ByteArray(255); var used = 0; var bits = 0; var bitCount = 0
        fun emitByte(value: Int) { block[used++] = value.toByte(); if (used == 255) { out.write(used); out.write(block, 0, used); used = 0 } }
        fun code(value: Int) { bits = bits or (value shl bitCount); bitCount += 9; while (bitCount >= 8) { emitByte(bits and 255); bits = bits ushr 8; bitCount -= 8 } }
        code(256)
        // Reset below the 9→10-bit boundary. Dictionary memory is capped at 242 entries.
        val dictionary=HashMap<Int,Int>(256); var nextCode=258; var prefix=indices[0].toInt() and 255
        for(i in 1 until indices.size) {
            val symbol=indices[i].toInt() and 255; val key=(prefix shl 8) or symbol
            val match=dictionary[key]
            if(match!=null)prefix=match else {
                code(prefix)
                if(nextCode<500)dictionary[key]=nextCode++ else { code(256); dictionary.clear(); nextCode=258 }
                prefix=symbol
            }
        }
        code(prefix)
        code(257); if (bitCount > 0) emitByte(bits and 255)
        if (used > 0) { out.write(used); out.write(block, 0, used) }; out.write(0)
    }
    override fun close() { out.write(0x3b); out.flush() }
    private fun word(value: Int) { out.write(value and 255); out.write(value ushr 8 and 255) }
}
