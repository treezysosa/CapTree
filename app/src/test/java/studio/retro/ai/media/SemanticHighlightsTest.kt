package studio.retro.ai.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import studio.retro.ai.EditorState
import studio.retro.ai.data.Preferences

class SemanticHighlightsTest {
    private fun result()=JSONObject().put("version",1).put("duration_ms",2000).put("metric","cosine_similarity").put("sample_count",2).put("model","openai/clip-vit-base-patch32").put("ranges",JSONArray().put(JSONObject().put("start_ms",500).put("end_ms",1500).put("score",0.25)))
    @Test fun samplesCoverOnlyTheSelectedTrimWithBoundedMemory() {
        for(duration in listOf(1000L,1001L,2400L,120000L)) {
            val times=SemanticHighlights.sampleTimes(duration)
            assertTrue(times.size in 1..24); assertEquals(times,times.distinct().sorted()); assertTrue(times.all { it>=0 && it<duration })
            assertTrue(times.first()<duration/2+1); assertTrue(times.last()>=duration/2)
        }
        assertEquals(listOf(500L,1500L),SemanticHighlights.sampleTimes(2000))
    }
    @Test fun measuredCosineScoresStayDistinctFromConfidencePercentages() {
        val parsed=SemanticHighlights.parse(result().toString(),2000)
        assertEquals(2,parsed.sampleCount); assertEquals(listOf(SemanticRange(500,1500,0.25)),parsed.ranges)
        val low=result().apply { getJSONArray("ranges").getJSONObject(0).put("score",-0.2) }
        assertEquals(-0.2,SemanticHighlights.parse(low.toString(),2000).ranges[0].score,0.0)
    }
    @Test fun mismatchedDurationsEmptyRangesAndOutOfBoundsResultsAreRejected() {
        val bad=listOf(result().put("duration_ms",3000),result().put("sample_count",25),result().put("ranges",JSONArray()),result().apply { getJSONArray("ranges").getJSONObject(0).put("end_ms",2001) },result().apply { getJSONArray("ranges").getJSONObject(0).put("score",1.1) })
        bad.forEach { try { SemanticHighlights.parse(it.toString(),2000); fail("Expected invalid result rejection") } catch(expected:IllegalArgumentException) { /* rejected */ } }
    }
    @Test fun promptAndRequestedLengthSurviveADraftWithoutPersistingTransientSuggestions() {
        val state=EditorState(tool="clipper",highlightPrompt="a person holding a guitar",highlightSeconds=10,semanticClips=listOf(SemanticRange(0,1000,0.3)))
        val restored=EditorState.restore("clipper",state.recipe(Preferences()).toString())
        assertEquals(state.highlightPrompt,restored.highlightPrompt); assertEquals(10,restored.highlightSeconds); assertTrue(restored.semanticClips.isEmpty())
    }
}
