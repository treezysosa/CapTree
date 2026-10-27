package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import studio.retro.ai.EditorState
import studio.retro.ai.data.Preferences

class EditorRecipeTest {
    @Test fun unfinishedProjectFieldsRemainEditableAfterAResume() {
        val recipe=EditorState(tool="clipper").recipe(Preferences()).put("draft_start","0.").put("draft_end","").put("draft_seed","00123")
        val restored=EditorState.restore("clipper",recipe.toString())
        assertEquals("0.",restored.start); assertEquals("",restored.end); assertEquals("00123",restored.seed)
    }
    @Test fun savedSplitClipsRetainTheirOriginAndAdaptiveColorOptions() {
        val clips=Timeline.split(listOf(ClipEdit("/local/input.mp4",0,3000,3000)),0,1500)
        val state=EditorState(tool="clipper",inputs=listOf("/local/input.mp4"),video=true,clips=clips,gifPalette="Adaptive 64",gifDither=true)
        val restored=EditorState.restore("clipper",state.recipe(Preferences()).toString())
        assertEquals(clips,restored.clips); assertEquals(clips[0].originId,restored.clips[1].originId)
        assertEquals("Adaptive 64",restored.gifPalette); assertTrue(restored.gifDither)
    }
    @Test fun olderRecipesKeepTheirClassicColorsAndIndependentClipIdentities() {
        val old=JSONObject("""{"inputs":["/local/input.mp4"],"clips":[{"path":"/local/input.mp4","start":0,"end":1000,"duration":2000,"id":"a"},{"path":"/local/input.mp4","start":1000,"end":2000,"duration":2000,"id":"b"}]}""")
        val restored=EditorState.restore("clipper",old.toString())
        assertEquals("Classic 256",restored.gifPalette); assertFalse(restored.gifDither)
        assertEquals(listOf("a","b"),restored.clips.map { it.originId })
    }
    @Test fun captionSourcesSurviveSavingSoAnExpandedTrimCanRestoreHiddenText() {
        val source=ClipEdit("/local/input.mp4",0,3000,3000)
        val cues=listOf(CaptionCue(1000,2500,"restorable"))
        val state=EditorState(tool="clipper",clips=listOf(source.copy(endMs=800)),captionSourceClips=listOf(source),captionSourceCues=cues)
        val restored=EditorState.restore("clipper",state.recipe(Preferences()).toString())
        assertEquals(cues,CaptionTimeline.retime(restored.captionSourceCues,restored.captionSourceClips,listOf(source)))
    }
}
