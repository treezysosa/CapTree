package studio.retro.ai.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import studio.retro.ai.EditorState
import studio.retro.ai.media.*

class EditorCheckpointsTest {
    @Test fun unfinishedFieldsAndBothHistoryBranchesSurviveTheRoundTrip() {
        val clip=ClipEdit("/private/imports/movie.mp4",0,2000,2400)
        val before=EditorState(tool="clipper",inputs=listOf(clip.path),video=true,clips=listOf(clip),end="2",captions="unfinished SRT")
        val current=before.copy(end="",seed="000123",playheadMs=650,faceRestore=true)
        val future=before.copy(start="0.",end="1.500")
        val (json,_)=EditorCheckpoints.encode(EditorCheckpoint(current,HistorySnapshot(listOf(before),listOf(future))))
        val restored=EditorCheckpoints.decode(json)
        assertEquals("",restored.current.end); assertEquals("000123",restored.current.seed); assertEquals(650L,restored.current.playheadMs); assertEquals("unfinished SRT",restored.current.captions)
        val history=EditHistory<EditorState>(); history.restore(restored.history)
        assertEquals(before,history.undo(restored.current)?.copy(previewRequestMs=null))
        assertEquals(current,history.redo(before)?.copy(previewRequestMs=null))
        assertEquals(future,history.redo(current)?.copy(previewRequestMs=null))
    }
    @Test fun checkpointsExcludeConnectionSettingsAndTransientResults() {
        val current=EditorState(tool="photo",prompt="an original console",suggestions=listOf(300),semanticClips=listOf(SemanticRange(0,1000,0.5)),previewRequestMs=1000)
        val (json,_)=EditorCheckpoints.encode(EditorCheckpoint(current,HistorySnapshot()))
        for(key in listOf("backend","consent","face_upload_confirmed","highlight_upload_confirmed","workId","token","semanticClips"))assertFalse(key,json.contains("\"$key\""))
        val restored=EditorCheckpoints.decode(json).current
        assertTrue(restored.suggestions.isEmpty()); assertTrue(restored.semanticClips.isEmpty()); assertEquals(current.prompt,restored.prompt)
    }
    @Test fun largeHistoriesStayWithinTheDatabaseBudgetWhileKeepingCurrentText() {
        val base=EditorState(tool="photo",prompt="current")
        val history=List(30) { base.copy(prompt="step $it "+"界".repeat(20000)) }
        val (json,kept)=EditorCheckpoints.encode(EditorCheckpoint(base,HistorySnapshot(history,emptyList())))
        assertTrue(json.toByteArray().size<=EditorCheckpoints.MAX_BYTES); assertTrue(kept.historyLimited); assertEquals("current",kept.current.prompt); assertTrue(kept.history.past.size in 1..29)
        assertEquals(history.last(),kept.history.past.last())
        assertEquals(kept.history.past.map { it.prompt },EditorCheckpoints.decode(json).history.past.map { it.prompt })
    }
    @Test fun mediaReferencesIncludeUndoAndRedoOnlyImports() {
        val first=EditorState(tool="gif",inputs=listOf("/private/a.png")); val second=first.copy(inputs=listOf("/private/b.png")); val current=first.copy(inputs=listOf("/private/c.png"))
        val checkpoint=EditorCheckpoint(current,HistorySnapshot(listOf(first),listOf(second)))
        assertEquals(setOf("/private/a.png","/private/b.png","/private/c.png"),EditorCheckpoints.mediaPaths(EditorCheckpoints.media(checkpoint)))
    }
    @Test fun blankWorkspacesStayOutOfTheSaveMenuAndUnsupportedVersionsFail() {
        val empty=EditorCheckpoints.empty("clipper")
        assertFalse(EditorCheckpoints.meaningful(EditorCheckpoint(empty,HistorySnapshot())))
        assertTrue(EditorCheckpoints.meaningful(EditorCheckpoint(empty.copy(width=480),HistorySnapshot())))
        val (json,_)=EditorCheckpoints.encode(EditorCheckpoint(empty,HistorySnapshot()))
        try { EditorCheckpoints.decode(JSONObject(json).put("version",999).toString()); fail("Unknown versions must fail") } catch(expected:IllegalArgumentException) { /* rejected */ }
    }
}
