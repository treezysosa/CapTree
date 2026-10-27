package studio.retro.ai.media

import android.graphics.*
import android.media.ExifInterface
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

object PhotoEngine {
    fun read(file: File, limit: Int = 2048): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or damaged image. Import JPG, PNG, or WebP." }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = 1; while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > limit) inSampleSize *= 2 }
        val image = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "Cannot decode image." }
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) { 2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f); 5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f); 7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f) }
        }
        return if (matrix.isIdentity) image else Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true).also { image.recycle() }
    }
    suspend fun enhance(source: Bitmap, amount: Float, denoise: Boolean, sharpen: Boolean, progress: suspend (Int) -> Unit): Bitmap {
        val w = source.width; val h = source.height
        val src = IntArray(w * h); source.getPixels(src, 0, w, 0, 0, w, h)
        val histogram = IntArray(256); val sums = DoubleArray(3)
        src.forEach { c -> val r=c ushr 16 and 255; val g=c ushr 8 and 255; val b=c and 255; histogram[(r*54+g*183+b*19) shr 8]++; sums[0]+=r; sums[1]+=g; sums[2]+=b }
        fun percentile(fraction: Float): Int { var n=0; for(i in histogram.indices) { n+=histogram[i]; if(n>=src.size*fraction)return i }; return 255 }
        val low=percentile(0.015f); val high=percentile(0.985f); val range=(high-low).coerceAtLeast(32)
        val gray=sums.average(); val balance=sums.map { (gray/it.coerceAtLeast(1.0)).coerceIn(0.8,1.2) }
        val out=IntArray(src.size)
        for(y in 0 until h) {
            currentCoroutineContext().ensureActive()
            for(x in 0 until w) {
                val c=src[y*w+x]; val channels=intArrayOf(c ushr 16 and 255,c ushr 8 and 255,c and 255)
                val colors=IntArray(3)
                for(k in 0..2) {
                    val shift=16-k*8; var v=channels[k].toFloat()
                    if((denoise || sharpen) && x in 1 until w-1 && y in 1 until h-1) {
                        var sum=0f; var weight=0f
                        for(dy in -1..1) for(dx in -1..1) { val n=src[(y+dy)*w+x+dx] ushr shift and 255; val wt=if(abs(n-v)<28)1f else 0.12f; sum+=n*wt; weight+=wt }
                        val blur=sum/weight
                        v=if(denoise) v*0.35f+blur*0.65f else v
                        if(sharpen) v+=(v-blur)*0.7f
                    }
                    val corrected=((v*balance[k]-low)*255/range).toFloat().coerceIn(0f,255f)
                    colors[k]=(channels[k]+(corrected-channels[k])*amount).roundToInt().coerceIn(0,255)
                }
                val lum=colors.average().toFloat()
                for(k in 0..2) colors[k]=(lum+(colors[k]-lum)*(1+0.12f*amount)).roundToInt().coerceIn(0,255)
                out[y*w+x]=(c and -0x1000000) or (colors[0] shl 16) or (colors[1] shl 8) or colors[2]
            }
            if(y%32==0)progress(y*100/h)
        }
        return Bitmap.createBitmap(out,w,h,Bitmap.Config.ARGB_8888)
    }
    fun decorate(bitmap: Bitmap, caption: String, sticker: String): Bitmap {
        if(caption.isBlank() && sticker.isBlank()) return bitmap
        val out=bitmap.copy(Bitmap.Config.ARGB_8888,true); val canvas=Canvas(out)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=out.width/14f; typeface=Typeface.DEFAULT_BOLD; setShadowLayer(3f,1f,2f,Color.BLACK) }
        if(caption.isNotBlank()) { paint.textAlign=Paint.Align.CENTER; canvas.drawText(caption.take(48),out.width/2f,out.height-paint.textSize,paint) }
        if(sticker.isNotBlank()) { paint.textAlign=Paint.Align.LEFT; paint.textSize=out.width/7f; canvas.drawText(sticker,10f,paint.textSize+10,paint) }
        return out
    }
}
