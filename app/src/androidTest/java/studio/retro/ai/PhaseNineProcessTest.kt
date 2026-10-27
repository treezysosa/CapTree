package studio.retro.ai

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.data.*
import studio.retro.ai.media.*
import java.io.File
import java.security.MessageDigest

/** Run in two independent instrumentations with an actual force-stop between them. */
@RunWith(AndroidJUnit4::class)
class PhaseNineProcessTest {
    private val app:StudioApplication get()=ApplicationProvider.getApplicationContext()
    private fun enabled()=assumeTrue("The process-recovery workflow supplies phase9_process=1.",InstrumentationRegistry.getArguments().getString("phase9_process")=="1")
    @Test fun prepareCheckpoint():Unit=runBlocking {
        enabled()
        val staged=File(app.filesDir,"outputs/phase9-input.mp4").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { input -> staged.outputStream().use { input.copyTo(it) } }
        val vm=withContext(Dispatchers.Main) { StudioViewModel(app) }
        try {
            val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",staged)
            withContext(Dispatchers.Main) { vm.open("clipper"); vm.import(listOf(uri),true) }
            withTimeout(20000) { vm.editor.first { it.inputs.isNotEmpty() } }
            withContext(Dispatchers.Main) {
                vm.edit { it.copy(captions=CaptionTrack.srt(listOf(CaptionCue(300,2200,"RECOVERED CAPTION")))) }
                vm.edit("valid-trim") { it.copy(end="1.5",seed="00123") }
                vm.edit("unfinished-trim") { it.copy(end="") }
                vm.undo()
                assertEquals("1.5",vm.editor.value.end); assertTrue(vm.canUndo.value); assertTrue(vm.canRedo.value)
                vm.flushAutosave()
            }.join()
            val source=vm.editor.value.inputs.single()
            val dao=app.database.sessions()
            val session=dao.observe().first().mapNotNull { dao.get(it.id) }.first { EditorCheckpoints.decode(it.payload).current.inputs==listOf(source) }
            assertEquals("1.5",EditorCheckpoints.decode(session.payload).current.end)
            File(app.filesDir,"phase9-recovery.json").writeText(JSONObject().put("id",session.id).put("source",source).put("sha256",MessageDigest.getInstance("SHA-256").digest(File(source).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }).toString())
        } finally { staged.delete() }
    }
    @Test fun verifyCheckpointAndExport():Unit=runBlocking {
        enabled()
        val marker=JSONObject(File(app.filesDir,"phase9-recovery.json").readText())
        val id=marker.getString("id"); val source=File(marker.getString("source"))
        assertEquals(marker.getString("sha256"),MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) })
        val vm=withContext(Dispatchers.Main) { StudioViewModel(app) }
        withContext(Dispatchers.Main) { vm.restoreDraft(id) }
        withTimeout(20000) { vm.editor.first { it.inputs==listOf(source.path) && it.end=="1.5" } }
        withContext(Dispatchers.Main) {
            assertEquals("00123",vm.editor.value.seed); assertTrue(vm.canUndo.value); assertTrue(vm.canRedo.value)
            assertFalse(vm.faceConfirmation.value); assertNull(vm.highlightConfirmation.value); assertNull(vm.active.value)
            vm.redo(); assertEquals("",vm.editor.value.end); vm.create()
        }
        withTimeout(10000) { vm.message.first { it.contains("valid trim times") } }
        withContext(Dispatchers.Main) {
            vm.message.value=""; vm.undo(); assertEquals("1.5",vm.editor.value.end)
            vm.undo(); assertEquals("2.4",vm.editor.value.end); assertEquals(listOf(CaptionCue(300,2200,"RECOVERED CAPTION")),CaptionTrack.parse(vm.editor.value.captions,2400))
            vm.redo(); assertEquals("1.5",vm.editor.value.end); assertEquals(listOf(CaptionCue(300,1500,"RECOVERED CAPTION")),CaptionTrack.parse(vm.editor.value.captions,1500))
        }
        val blank=withContext(Dispatchers.Main) { StudioViewModel(app) }
        withContext(Dispatchers.Main) { blank.clean() }
        withTimeout(20000) { blank.importing.first { !it } }; assertTrue(source.isFile)
        val before=app.database.projects().observe().first().map { it.id }.toSet()
        withContext(Dispatchers.Main) { vm.create() }
        val result=withTimeout(180000) { app.database.projects().observe().first { rows -> rows.any { it.id !in before && it.status in setOf("completed","failed") } }.first { it.id !in before && it.status in setOf("completed","failed") } }
        assertEquals(result.error,"completed",result.status); assertEquals("video/mp4",result.mime)
        val output=File(result.output); val duration=VideoEngine.duration(output); assertTrue(duration in 1250L..1800L)
        val reader=android.media.MediaExtractor()
        try { reader.setDataSource(output.path); assertTrue((0 until reader.trackCount).any { reader.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }) } finally { reader.release() }
        val diagnostics=File(app.getExternalFilesDir(null),"diagnostics").apply { mkdirs() }
        output.copyTo(File(diagnostics,"recovered-caption-export.mp4"),overwrite=true)
        File(diagnostics,"recovery-report.json").writeText(JSONObject().put("android_api",android.os.Build.VERSION.SDK_INT).put("force_stop_between_instrumentations",true).put("draft_id",id).put("undo_redo_verified",true).put("incomplete_trim_rejected",true).put("media_hash_preserved",true).put("export_duration_ms",duration).toString(2))
    }
}
