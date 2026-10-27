package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class CameraMotionTest {
    @Test fun measuredTranslationMatchesTheKnownShift() {
        val random=java.util.Random(73); val w=96; val h=72; val first=IntArray(w*h){random.nextInt(256)}
        val next=IntArray(w*h) { p -> val x=p%w-3; val y=p/w+2; if(x in 0 until w && y in 0 until h)first[y*w+x] else 0 }
        val result=CameraMotion.estimate(first,next,w,h)
        assertTrue(result.reliable); assertEquals(3,result.dx); assertEquals(-2,result.dy); assertEquals(0f,result.error,0.001f)
    }
    @Test fun smoothingReducesJitterAndDoesNotMixScenes() {
        val path=List(60){i -> CameraPoint(i*100L,(4*sin(i*2*Math.PI/5)).toFloat(),0f)}
        val correction=CameraMotion.corrections(path)
        val original=path.subList(5,55).sumOf { (it.x*it.x).toDouble() }
        val smoothed=(5 until 55).sumOf { val x=path[it].x+correction[it].x; (x*x).toDouble() }
        assertTrue(smoothed<original*0.1)
        val cut=listOf(CameraPoint(0,0f,0f,0),CameraPoint(100,100f,0f,1))
        assertEquals(0f,CameraMotion.corrections(cut).first().x,0.001f)
    }
    @Test fun flatImagesAreNotClaimedAsReliableMotion() { assertFalse(CameraMotion.estimate(IntArray(96*72){80},IntArray(96*72){80},96,72).reliable) }
}
