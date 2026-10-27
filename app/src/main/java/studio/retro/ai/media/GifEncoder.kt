package studio.retro.ai.media

import java.io.OutputStream

/** Streaming GIF89a writer. RGB332 palette; bounded LZW blocks keep memory constant. */
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
        out.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 4)); word((delayMs / 10).coerceIn(2, 65535)); out.write(0); out.write(0)
        out.write(0x2c); word(0); word(0); word(width); word(height); out.write(0); out.write(8)
        val block = ByteArray(255); var used = 0; var bits = 0; var bitCount = 0
        fun emitByte(value: Int) { block[used++] = value.toByte(); if (used == 255) { out.write(used); out.write(block, 0, used); used = 0 } }
        fun code(value: Int) { bits = bits or (value shl bitCount); bitCount += 9; while (bitCount >= 8) { emitByte(bits and 255); bits = bits ushr 8; bitCount -= 8 } }
        code(256)
        for (i in argb.indices) {
            if (i > 0 && i % 200 == 0) code(256)
            val c = argb[i]; code(((c ushr 16 and 255) shr 5 shl 5) or ((c ushr 8 and 255) shr 5 shl 2) or ((c and 255) shr 6))
        }
        code(257); if (bitCount > 0) emitByte(bits and 255)
        if (used > 0) { out.write(used); out.write(block, 0, used) }; out.write(0)
    }
    override fun close() { out.write(0x3b); out.flush() }
    private fun word(value: Int) { out.write(value and 255); out.write(value ushr 8 and 255) }
}
