package studio.retro.ai.media

import kotlin.math.abs

data class MotionMatch(val dx:Int,val dy:Int,val error:Float,val reliable:Boolean)
data class CameraPoint(val timeMs:Long,val x:Float,val y:Float,val segment:Int=0)

object CameraMotion {
    /** Robust small-translation search on edge pixels; rejects flat frames and scene cuts. */
    fun estimate(previous:IntArray,current:IntArray,width:Int,height:Int,radius:Int=6):MotionMatch {
        require(previous.size==width*height && current.size==previous.size && width>radius*2+8 && height>radius*2+8)
        val border=radius+2
        val candidates=ArrayList<Int>()
        for(y in border until height-border step 2)for(x in border until width-border step 2) {
            val p=y*width+x
            if(abs(previous[p+1]-previous[p-1])+abs(previous[p+width]-previous[p-width])>20)candidates+=p
        }
        if(candidates.size<20)return MotionMatch(0,0,255f,false)
        val step=(candidates.size/320).coerceAtLeast(1); val points=candidates.filterIndexed { index,_ -> index%step==0 }.take(320)
        var best=Float.MAX_VALUE; var bestError=255f; var dxBest=0; var dyBest=0
        for(dy in -radius..radius)for(dx in -radius..radius) {
            var error=0L
            for(p in points)error+=abs(previous[p]-current[p+dy*width+dx])
            val average=error.toFloat()/points.size; val cost=average+(abs(dx)+abs(dy))*0.01f
            if(cost<best) { best=cost; bestError=average; dxBest=dx; dyBest=dy }
        }
        return MotionMatch(dxBest,dyBest,bestError,bestError<30f)
    }

    fun corrections(path:List<CameraPoint>,radius:Int=4,strength:Float=1f):List<CameraPoint> {
        require(radius>=1 && strength in 0f..1f)
        return path.mapIndexed { index,p ->
            val nearby=path.subList((index-radius).coerceAtLeast(0),(index+radius+1).coerceAtMost(path.size)).filter { it.segment==p.segment }
            p.copy(x=(nearby.map { it.x }.average().toFloat()-p.x)*strength,y=(nearby.map { it.y }.average().toFloat()-p.y)*strength)
        }
    }

    fun at(points:List<CameraPoint>,timeMs:Long):CameraPoint {
        require(points.isNotEmpty())
        val found=points.binarySearch { it.timeMs.compareTo(timeMs) }
        if(found>=0)return points[found]
        val next=-found-1
        if(next==0)return points.first()
        if(next>=points.size)return points.last()
        val a=points[next-1]; val b=points[next]
        if(a.segment!=b.segment)return b.copy(x=0f,y=0f)
        val t=(timeMs-a.timeMs).toFloat()/(b.timeMs-a.timeMs).coerceAtLeast(1)
        return CameraPoint(timeMs,a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.segment)
    }
}
