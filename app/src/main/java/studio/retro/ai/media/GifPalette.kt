package studio.retro.ai.media

import kotlin.math.roundToInt
import kotlin.math.sqrt

data class IndexedFrame(val palette:IntArray,val indices:ByteArray)

/** Weighted median-cut colors, cached nearest-color lookup, and optional error diffusion. */
object GifPalette {
    private data class Bin(val r:Int,val g:Int,val b:Int,val count:Int)
    private class Box(val bins:List<Bin>) {
        private val ranges=intArrayOf(bins.maxOf { it.r }-bins.minOf { it.r },bins.maxOf { it.g }-bins.minOf { it.g },bins.maxOf { it.b }-bins.minOf { it.b })
        val axis=(0..2).maxBy { ranges[it] }
        val weight=bins.sumOf { it.count.toLong() }
        val score=if(bins.size>1)ranges[axis]*sqrt(weight.toDouble()) else -1.0
        fun split():List<Box> {
            val ordered=bins.sortedBy { channel(it,axis) }; var total=0L; var middle=1
            for(i in 0 until ordered.lastIndex) { total+=ordered[i].count; middle=i+1; if(total*2>=weight)break }
            return listOf(Box(ordered.take(middle)),Box(ordered.drop(middle)))
        }
        fun color():Int {
            fun mean(channel:(Bin)->Int)=(bins.sumOf { channel(it).toLong()*it.count }+weight/2).div(weight).toInt()
            return -0x1000000 or (mean { it.r } shl 16) or (mean { it.g } shl 8) or mean { it.b }
        }
    }
    private fun channel(bin:Bin,axis:Int)=when(axis) { 0->bin.r; 1->bin.g; else->bin.b }
    private class Nearest(private val colors:IntArray) {
        private data class Node(val index:Int,val axis:Int,val left:Node?,val right:Node?)
        private fun component(index:Int,axis:Int)=(colors[index] ushr (16-axis*8)) and 255
        private fun build(indices:List<Int>,depth:Int):Node? {
            if(indices.isEmpty())return null
            val axis=depth%3; val sorted=indices.sortedBy { component(it,axis) }; val middle=sorted.size/2
            return Node(sorted[middle],axis,build(sorted.take(middle),depth+1),build(sorted.drop(middle+1),depth+1))
        }
        private val root=build(colors.indices.toList(),0)
        private val cache=IntArray(32768){-1}
        fun find(r:Int,g:Int,b:Int):Int {
            val key=((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            if(cache[key]>=0)return cache[key]
            var best=0; var distance=Int.MAX_VALUE
            fun search(node:Node?) {
                if(node==null)return
                val dr=r-component(node.index,0); val dg=g-component(node.index,1); val db=b-component(node.index,2)
                val error=dr*dr+dg*dg+db*db
                if(error<distance || error==distance && node.index<best) { best=node.index; distance=error }
                val delta=when(node.axis) { 0->dr; 1->dg; else->db }
                search(if(delta<0)node.left else node.right)
                if(delta*delta<=distance)search(if(delta<0)node.right else node.left)
            }
            search(root); cache[key]=best; return best
        }
    }

    fun quantize(argb:IntArray,width:Int,height:Int,maxColors:Int=256,dither:Boolean=false,check:()->Unit={}):IndexedFrame {
        require(width>0 && height>0 && argb.size.toLong()==width.toLong()*height && maxColors in 2..256)
        val exact=LinkedHashMap<Int,Int>()
        for(i in argb.indices) {
            if(i%4096==0)check()
            val color=-0x1000000 or (argb[i] and 0xffffff)
            if(color !in exact)exact[color]=exact.size
            if(exact.size>maxColors)break
        }
        if(exact.size<=maxColors) {
            val indices=ByteArray(argb.size) { i -> if(i%4096==0)check(); exact.getValue(-0x1000000 or (argb[i] and 0xffffff)).toByte() }
            return IndexedFrame(exact.keys.toIntArray(),indices)
        }
        val counts=IntArray(32768); val reds=LongArray(32768); val greens=LongArray(32768); val blues=LongArray(32768)
        for(i in argb.indices) {
            if(i%4096==0)check()
            val c=argb[i]; val r=c ushr 16 and 255; val g=c ushr 8 and 255; val b=c and 255
            val key=((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            counts[key]++; reds[key]+=r.toLong(); greens[key]+=g.toLong(); blues[key]+=b.toLong()
        }
        val bins=counts.indices.filter { counts[it]>0 }.map { i -> val n=counts[i]; Bin(((reds[i]+n/2)/n).toInt(),((greens[i]+n/2)/n).toInt(),((blues[i]+n/2)/n).toInt(),n) }
        val boxes=mutableListOf(Box(bins))
        while(boxes.size<maxColors) {
            check()
            val index=boxes.indices.maxBy { boxes[it].score }
            if(boxes[index].score<0)break
            val parts=boxes.removeAt(index).split(); boxes.addAll(parts)
        }
        val palette=boxes.map { it.color() }.toIntArray()
        val nearest=Nearest(palette); val indices=ByteArray(argb.size)
        var error=Array(3){FloatArray(width+2)}; var next=Array(3){FloatArray(width+2)}
        for(y in 0 until height) {
            check()
            for(x in 0 until width) {
                val at=y*width+x; val c=argb[at]
                val r=((c ushr 16 and 255)+(if(dither)error[0][x+1] else 0f)).roundToInt().coerceIn(0,255)
                val g=((c ushr 8 and 255)+(if(dither)error[1][x+1] else 0f)).roundToInt().coerceIn(0,255)
                val b=((c and 255)+(if(dither)error[2][x+1] else 0f)).roundToInt().coerceIn(0,255)
                val index=nearest.find(r,g,b); indices[at]=index.toByte()
                if(dither)for(axis in 0..2) {
                    val actual=when(axis) { 0->r; 1->g; else->b }; val selected=palette[index] ushr (16-axis*8) and 255
                    val delta=(actual-selected)/16f
                    error[axis][x+2]+=delta*7
                    next[axis][x]+=delta*3; next[axis][x+1]+=delta*5; next[axis][x+2]+=delta
                }
            }
            val swap=error; error=next; next=swap; next.forEach { it.fill(0f) }
        }
        return IndexedFrame(palette,indices)
    }
}
