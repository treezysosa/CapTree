package studio.retro.ai.media

import android.graphics.*

/** Shared caption/sticker painter for GIF frames and MP4 overlays. */
object MediaDecoration {
    fun draw(canvas:Canvas,width:Int,height:Int,caption:String,sticker:String) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.DEFAULT_BOLD; textSize=(width*0.048f).coerceIn(10f,64f); textAlign=Paint.Align.CENTER }
        if(caption.isNotBlank()) {
            val all=ArrayList<String>()
            for(paragraph in caption.take(1000).lines()) {
                var line=""
                for(word in paragraph.split(Regex("\\s+")).filter { it.isNotBlank() }) {
                    val candidate=if(line.isEmpty())word else "$line $word"
                    if(paint.measureText(candidate)>width*0.9f && line.isNotEmpty()) { all+=line; line=word } else line=candidate
                }
                if(line.isNotEmpty())all+=line
            }
            val lines=all.take(4).map { raw -> var text=raw; while(text.length>1 && paint.measureText(text)>width*0.9f)text=text.dropLast(1); if(text!=raw)"$text…" else text }
            val step=paint.textSize*1.25f; val bottom=height-height*0.06f
            for((index,line) in lines.withIndex()) {
                val y=bottom-(lines.size-1-index)*step
                paint.style=Paint.Style.STROKE; paint.strokeWidth=(paint.textSize/6).coerceAtLeast(1f); paint.color=Color.BLACK
                canvas.drawText(line,width/2f,y,paint)
                paint.style=Paint.Style.FILL; paint.color=Color.WHITE; canvas.drawText(line,width/2f,y,paint)
            }
        }
        if(sticker.isNotBlank()) {
            paint.style=Paint.Style.FILL; paint.textAlign=Paint.Align.LEFT; paint.color=Color.WHITE; paint.textSize=minOf(width,height)*0.18f
            paint.setShadowLayer(2f,1f,1f,Color.BLACK); canvas.drawText(sticker,width*0.04f,height*0.04f+paint.textSize,paint)
        }
    }

    fun fit(source:Bitmap,width:Int,height:Int,caption:String="",sticker:String=""):Bitmap {
        val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(output); canvas.drawColor(Color.BLACK)
        val scale=minOf(width.toFloat()/source.width,height.toFloat()/source.height)
        val w=source.width*scale; val h=source.height*scale
        canvas.drawBitmap(source,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        draw(canvas,width,height,caption,sticker)
        return output
    }
}
