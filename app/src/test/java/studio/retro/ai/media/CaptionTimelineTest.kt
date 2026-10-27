package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test

class CaptionTimelineTest {
    private val one=ClipEdit("one",1000,5000,8000,"one")
    private val two=ClipEdit("two",0,3000,5000,"two")
    @Test fun reorderAndRemovalKeepTextWithTheSourceFootage() {
        val cues=listOf(CaptionCue(500,1500,"first"),CaptionCue(4500,5500,"second"))
        assertEquals(listOf(CaptionCue(500,1500,"second"),CaptionCue(3500,4500,"first")),CaptionTimeline.retime(cues,listOf(one,two),listOf(two,one)))
        assertEquals(listOf(CaptionCue(500,1500,"second")),CaptionTimeline.retime(cues,listOf(one,two),listOf(two)))
    }
    @Test fun trimClampsIntersectingTextAndShiftsLaterClips() {
        val cues=listOf(CaptionCue(0,2000,"edge"),CaptionCue(2500,3900,"cut"),CaptionCue(4100,4600,"later"))
        val trimmed=one.copy(startMs=2000,endMs=3500)
        assertEquals(listOf(CaptionCue(0,1000,"edge"),CaptionCue(1600,2100,"later")),CaptionTimeline.retime(cues,listOf(one,two),listOf(trimmed,two)))
    }
    @Test fun splitKeepsContinuousCuesAndMovingItsChildMovesOnlyItsPart() {
        val cues=listOf(CaptionCue(500,2500,"crossing"))
        val split=Timeline.split(listOf(one),0,2500)
        assertEquals(one.originId,split[1].originId)
        assertEquals(cues,CaptionTimeline.retime(cues,listOf(one),split))
        assertEquals(listOf(CaptionCue(0,1000,"crossing"),CaptionCue(3000,4000,"crossing")),CaptionTimeline.retime(cues,listOf(one),split.reversed()))
    }
    @Test fun identicalPathsImportedSeparatelyDoNotBorrowCaptions() {
        val other=one.copy(id="another",originId="another")
        assertEquals(emptyList<CaptionCue>(),CaptionTimeline.retime(listOf(CaptionCue(0,1000,"one only")),listOf(one,other),listOf(other)))
    }
    @Test fun aCueAcrossTwoClipsIsSplitAndFollowsEachClip() {
        val cues=listOf(CaptionCue(3500,4500,"bridge"))
        assertEquals(listOf(CaptionCue(0,500,"bridge"),CaptionCue(6500,7000,"bridge")),CaptionTimeline.retime(cues,listOf(one,two),listOf(two,one)))
    }
}
