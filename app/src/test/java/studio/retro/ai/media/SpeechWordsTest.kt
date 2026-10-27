package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test

class SpeechWordsTest {
    @Test fun actualWordTimesProduceReadableCuesAndPreserveSilence() {
        val words=SpeechWords.parse("""{"text":"one two three","result":[{"word":"one","start":0.1,"end":0.4},{"word":"two","start":0.4,"end":0.8},{"word":"three","start":1.5,"end":2.1}]}""",2000)
        assertEquals(listOf(CaptionCue(100,800,"one two"),CaptionCue(1500,2000,"three")),SpeechWords.captions(words))
    }
    @Test fun longSpeechSplitsIntoBoundedCaptionsWithoutInventingTimes() {
        val words=List(24) { SpeechWord(it*300L,it*300L+200,"word") }
        val cues=SpeechWords.captions(words)
        assertTrue(cues.size>=3); assertEquals(24,cues.sumOf { it.text.split(' ').size }); assertTrue(cues.all { it.endMs-it.startMs<=3500 && it.text.length<=42 })
        assertEquals(0L,cues.first().startMs); assertEquals(7100L,cues.last().endMs)
    }
    @Test fun missingWordTimingsAreAnErrorAndSilenceIsEmpty() {
        assertTrue(SpeechWords.parse("""{"text":""}""",1000).isEmpty())
        try { SpeechWords.parse("""{"text":"invented"}""",1000); fail("Do not fabricate timing") } catch(expected:IllegalArgumentException) { /* rejected */ }
    }
}
