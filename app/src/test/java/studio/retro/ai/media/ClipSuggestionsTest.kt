package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test

class ClipSuggestionsTest {
    @Test fun activityAndQuietRegionsAreRankedWithinTheSelectedRange() {
        val suggestions=ClipSuggestions.rank(0,24000,listOf(17000,18000,20000),(0L until 10000 step 500).toList(),listOf(CaptionCue(16000,22000,"Speech")),true)
        assertTrue(suggestions.isNotEmpty()); assertTrue(suggestions.first().startMs>=12000)
        assertTrue(suggestions.all { it.startMs>=0 && it.endMs<=24000 && it.endMs>it.startMs })
        assertTrue(suggestions.first().reason.contains("scene changes"))
    }
    @Test fun shortClipsAndSilentVideoHaveHonestFallbacks() {
        val result=ClipSuggestions.rank(1000,2500,emptyList(),emptyList(),emptyList(),false)
        assertEquals(1,result.size); assertEquals(1000L,result[0].startMs); assertEquals(2500L,result[0].endMs)
        assertEquals("scene-only scan",result[0].reason)
    }
}
