package studio.retro.ai

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.data.Project
import studio.retro.ai.media.*
import studio.retro.ai.work.CreationWorker
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhaseSevenHighlightsTest {
    private val app:StudioApplication get()=ApplicationProvider.getApplicationContext()
    private fun video():File=File(app.filesDir,"imports/${UUID.randomUUID()}.mp4").also { file ->
        file.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { source -> file.outputStream().use { source.copyTo(it) } }
    }
    @Test fun nativeVideoSamplingProducesOnlyBoundedJpegsFromTheTrim():Unit=runBlocking {
        val source=video(); val folder=File(app.cacheDir,"samples-${UUID.randomUUID()}")
        try {
            val progress=ArrayList<Int>()
            val times=HighlightFrames.extract(source,600,1800,folder) { progress+=it }
            assertEquals(SemanticHighlights.sampleTimes(1200),times)
            assertEquals(times.size,folder.listFiles()!!.size); assertEquals(100,progress.last())
            folder.listFiles()!!.forEach { frame ->
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(frame.path,bounds)
                assertEquals("image/jpeg",bounds.outMimeType); assertTrue(bounds.outWidth in 1..256 && bounds.outHeight in 1..256); assertTrue(frame.length() in 1..256L*1024)
            }
        } finally { source.delete(); folder.deleteRecursively() }
    }
    @Test fun savedHighlightRangesRetimeCaptionsSupportUndoAndExportRealMp4():Unit=runBlocking {
        val source=video(); val output=File(app.filesDir,"outputs/${UUID.randomUUID()}.json").apply { parentFile!!.mkdirs() }
        val clip=ClipEdit(source.path,500,2300,2400)
        val captions=CaptionTrack.srt(listOf(CaptionCue(100,1700,"MATCH")))
        val recipe=EditorState(tool="clipper",inputs=listOf(source.path),video=true,start="0.5",end="2.3",clips=listOf(clip),captions=captions,highlightPrompt="a test scene").recipe(studio.retro.ai.data.Preferences()).put("highlight_index",0)
        output.writeText(JSONObject().put("version",1).put("duration_ms",1800).put("sample_count",2).put("model","openai/clip-vit-base-patch32").put("metric","cosine_similarity").put("ranges",JSONArray().put(JSONObject().put("start_ms",400).put("end_ms",1300).put("score",0.3))).toString())
        val project=Project(UUID.randomUUID().toString(),"Protocol result test","highlights",recipe.toString(),status="completed",output=output.path,mime="application/json")
        val vm=withContext(Dispatchers.Main) { StudioViewModel(app) }
        var created:Project?=null
        try {
            withContext(Dispatchers.Main) { vm.applyHighlights(project) }
            withTimeout(15000) { vm.editor.first { it.semanticClips.isNotEmpty() } }
            withContext(Dispatchers.Main) {
                val range=vm.editor.value.semanticClips.single(); assertEquals(900L,range.startMs); assertEquals(1800L,range.endMs)
                vm.useSemanticRange(range)
                assertEquals(listOf(CaptionCue(0,900,"MATCH")),CaptionTrack.parse(vm.editor.value.captions,900))
                vm.undo(); assertEquals(clip,vm.editor.value.clips.single()); assertEquals(captions,vm.editor.value.captions)
                vm.redo(); assertEquals(900L,vm.editor.value.clips.single().durationMs)
            }
            val existing=app.database.projects().observe().first().map { it.id }.toSet()
            withContext(Dispatchers.Main) { vm.create() }
            created=withTimeout(90000) { app.database.projects().observe().first { rows -> rows.any { it.id !in existing && it.tool=="clipper" && it.status in setOf("completed","failed") } }.first { it.id !in existing && it.tool=="clipper" && it.status in setOf("completed","failed") } }
            assertEquals(created.error,"completed",created.status); assertEquals("video/mp4",created.mime)
            assertTrue("Real exported video keeps the selected duration",VideoEngine.duration(File(created.output)) in 700L..1200L)
        } finally { created?.let { app.database.projects().delete(it.id); File(it.output).delete() }; source.delete(); output.delete() }
    }
    @Test fun backgroundWorkerRejectsUnconfirmedFrameUploadBeforeAnyConnection():Unit=runBlocking {
        val old=app.settings.flow.first(); val source=video(); val id=UUID.randomUUID().toString()
        try {
            app.settings.backend("https://highlight-consent.invalid"); app.settings.consent(true)
            val recipe=EditorState(tool="clipper",inputs=listOf(source.path),video=true,clips=listOf(ClipEdit(source.path,0,2400,2400)),highlightPrompt="a scene").recipe(app.settings.flow.first()).put("highlight_index",0).put("highlight_upload_confirmed",false)
            val request=OneTimeWorkRequestBuilder<CreationWorker>().setInputData(workDataOf("project" to id)).build()
            app.database.projects().save(Project(id,"Unconfirmed highlights","highlights",recipe.toString(),status="queued",workId=request.id.toString()))
            WorkManager.getInstance(app).enqueueUniqueWork(id,ExistingWorkPolicy.KEEP,request)
            val result=withTimeout(20000) { app.database.projects().observe().first { rows -> rows.any { it.id==id && it.status=="failed" } }.first { it.id==id } }
            assertTrue(result.error,result.error.contains("Confirm selected-frame upload")); assertTrue(result.output.isEmpty())
            assertFalse(File(app.cacheDir,"highlight-frames-$id").exists())
        } finally { app.database.projects().delete(id); source.delete(); app.settings.backend(old.backend); app.settings.consent(old.remoteConsent) }
    }
}
