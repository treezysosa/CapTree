package studio.retro.ai.media

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class CaptionOverlay(private val cues:List<CaptionCue>,private val sticker:String=""):BitmapOverlay() {
    private var width=1; private var height=1; private var cachedText:String?=null; private var cached:Bitmap?=null
    override fun configure(videoSize:Size) { width=videoSize.width; height=videoSize.height; cachedText=null }
    override fun getBitmap(presentationTimeUs:Long):Bitmap {
        val text=CaptionTrack.textAt(cues,presentationTimeUs/1000)
        if(cached==null || cachedText!=text) {
            cached?.recycle()
            cached=if(text.isBlank() && sticker.isBlank())Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888) else Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { MediaDecoration.draw(Canvas(it),width,height,text,sticker) }
            cachedText=text
        }
        return cached!!
    }
    override fun release() { try { super.release() } finally { cached?.recycle(); cached=null } }
}
