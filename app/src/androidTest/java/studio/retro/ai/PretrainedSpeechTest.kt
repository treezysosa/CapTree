package studio.retro.ai

import android.content.pm.PackageManager
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
import studio.retro.ai.data.Project
import studio.retro.ai.media.*
import java.io.File
import java.util.UUID

/** The separate model workflow supplies real weights; production APKs contain none. */
@RunWith(AndroidJUnit4::class)
class PretrainedSpeechTest {
    @Test fun realOfflineSpeechRunsWithoutRemoteConsentAndExportsCaptions():Unit=runBlocking {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val available=assets.list("speech-validation")?.contains("speech-model.zip")==true
        if(InstrumentationRegistry.getArguments().getString("class").orEmpty().contains("PretrainedSpeechTest"))assertTrue("The dedicated speech run must supply real pretrained weights.",available)
        assumeTrue("Pretrained speech weights are supplied only by the dedicated validation workflow.",available)
        val app:StudioApplication=ApplicationProvider.getApplicationContext(); val old=app.settings.flow.first()
        val models=File(app.filesDir,"models"); val source=File(app.filesDir,"imports/${UUID.randomUUID()}.mp4").apply { parentFile!!.mkdirs() }
        val silence=File(app.cacheDir,"speech-silence.wav"); val none=File(app.cacheDir,"speech-no-result.srt")
        val vm=withContext(Dispatchers.Main) { StudioViewModel(app) }
        val created=ArrayList<Project>(); val started=System.currentTimeMillis()
        try {
            assets.open("speech-validation/speech.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            assets.open("speech-validation/speech-model.zip").use { input -> SpeechModelStore.install(input,models,OfflineSpeech::probe) }
            assertTrue(SpeechModelStore.installed(models))
            app.settings.backend("https://offline-speech-must-not-connect.invalid"); app.settings.consent(false)
            withTimeout(10000) { vm.prefs.first { !it.remoteConsent && it.backend.contains("must-not-connect") } }
            val permissions=app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            assertFalse("File transcription must not request a microphone",permissions.contains("android.permission.RECORD_AUDIO"))
            val duration=VideoEngine.duration(source)
            val intro=ClipEdit(source.path,0,500,duration); val spoken=ClipEdit(source.path,0,duration,duration)
            withContext(Dispatchers.Main) {
                vm.open("clipper")
                vm.editor.value=EditorState(tool="clipper",inputs=listOf(source.path),video=true,start="0",end="${duration/1000.0}",clips=listOf(intro,spoken),selectedClip=1,captions=CaptionTrack.srt(listOf(CaptionCue(0,400,"KEEP INTRO"))))
                vm.transcribe(offline=true)
            }
            val transcript=withTimeout(120000) { app.database.projects().observe().first { rows -> rows.any { it.tool=="transcribe" && it.created>=started && it.status in setOf("completed","failed") } }.first { it.tool=="transcribe" && it.created>=started && it.status in setOf("completed","failed") } }
            created+=transcript; assertEquals(transcript.error,"completed",transcript.status)
            val recipe=JSONObject(transcript.recipe)
            assertEquals("offline",recipe.getString("speech_engine")); assertEquals("",recipe.getString("backend")); assertFalse(recipe.getBoolean("consent")); assertEquals(SpeechModelStore.SHA256,recipe.getString("speech_model_sha256"))
            val cues=CaptionTrack.parse(File(transcript.output).readText(),duration+500)
            val text=cues.joinToString(" ") { it.text }
            assertTrue(text,setOf("one","two","three","four","five").count { text.split(' ').contains(it) }>=3)
            assertTrue(cues.all { it.startMs>=500 && it.endMs<=duration+500 })
            withContext(Dispatchers.Main) { vm.applyTranscript(transcript) }
            withTimeout(10000) { vm.editor.first { it.captions.contains("one") } }
            assertEquals("KEEP INTRO",CaptionTrack.textAt(CaptionTrack.parse(vm.editor.value.captions,duration+500),100))
            val before=app.database.projects().observe().first().map { it.id }.toSet()
            withContext(Dispatchers.Main) { vm.create() }
            val exported=withTimeout(120000) { app.database.projects().observe().first { rows -> rows.any { it.id !in before && it.tool=="clipper" && it.status in setOf("completed","failed") } }.first { it.id !in before && it.tool=="clipper" && it.status in setOf("completed","failed") } }
            created+=exported; assertEquals(exported.error,"completed",exported.status); assertEquals("video/mp4",exported.mime)
            assertTrue(kotlin.math.abs(VideoEngine.duration(File(exported.output))-(duration+500))<350)
            val diagnostics=File(app.getExternalFilesDir(null),"diagnostics").apply { mkdirs() }
            File(transcript.output).copyTo(File(diagnostics,"speech-on-device.srt"),overwrite=true)
            File(exported.output).copyTo(File(diagnostics,"speech-caption-export.mp4"),overwrite=true)
            File(diagnostics,"speech-on-device.json").writeText(JSONObject().put("engine","Vosk Android 0.3.75").put("model",SpeechModelStore.MODEL_ID).put("sha256",SpeechModelStore.SHA256).put("android_api",android.os.Build.VERSION.SDK_INT).put("remote_consent",false).put("transcript",text).put("cue_count",cues.size).put("timeline_offset_ms",500).put("export_duration_ms",VideoEngine.duration(File(exported.output))).toString(2))
            // A real recognizer on silence must fail instead of manufacturing subtitles.
            silence.outputStream().use { it.write(WavPcm16.header(16000,1,32000)); it.write(ByteArray(32000)) }
            try { OfflineSpeech.transcribe(app,SpeechModelStore.directory(models),silence,none) { _,_-> }; fail("Silence cannot produce fake subtitles") } catch(expected:IllegalArgumentException) { assertTrue(expected.message!!.contains("No English speech")); assertFalse(none.exists()) }
            val spokenWav=File(app.cacheDir,"speech-real.wav")
            try {
                assets.open("speech-validation/speech.wav").use { input -> spokenWav.outputStream().use { input.copyTo(it) } }
                try { OfflineSpeech.transcribe(app,SpeechModelStore.directory(models),spokenWav,none) { value,_ -> if(value!=null && value>0)throw CancellationException("Test cancellation after actual decoding") }; fail("Expected cancellation") } catch(expected:CancellationException) { assertFalse(none.exists()) }
            } finally { spokenWav.delete() }
        } finally {
            created.forEach { app.database.projects().delete(it.id); File(it.output).delete() }
            source.delete(); silence.delete(); none.delete(); SpeechModelStore.directory(models).deleteRecursively()
            app.settings.backend(old.backend); app.settings.consent(old.remoteConsent)
        }
    }
}
