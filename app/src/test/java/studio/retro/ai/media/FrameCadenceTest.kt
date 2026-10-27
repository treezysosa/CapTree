package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

class FrameCadenceTest {
    @Test fun anAlreadyMatchingStreamKeepsEveryFrameIncludingTheFirst() {
        val cadence=FrameCadence(10)
        assertTrue((0L..900000L step 100000L).all { cadence.keep(it) })
    }
    @Test fun thirtyFpsBecomesTwentyFourWithoutInventingFramesOrMovingTimestamps() {
        val input=List(30){(it*1000000.0/30).roundToLong()}; val cadence=FrameCadence(24)
        val kept=input.filter(cadence::keep)
        assertEquals(24,kept.size); assertEquals(0L,kept.first()); assertTrue(kept.zipWithNext().all { it.second>it.first })
    }
    @Test fun irregularGapsDoNotCreateCatchUpBurstsAndNewStreamsRestart() {
        val cadence=FrameCadence(8)
        assertTrue(cadence.keep(0)); assertTrue(cadence.keep(2000000)); assertFalse(cadence.keep(2001000)); assertTrue(cadence.keep(2125000))
        cadence.reset(); assertTrue(cadence.keep(0)); assertFalse(cadence.keep(1000))
    }
}
