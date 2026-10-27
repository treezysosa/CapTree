package studio.retro.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.data.Project
import studio.retro.ai.media.*
import studio.retro.ai.work.CreationWorker
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhaseEightSpeechTest {
    private val app:StudioApplication get()=ApplicationProvider.getApplicationContext()
    private fun source()=File(app.filesDir,"imports/${UUID.randomUUID()}.mp4").also { file -> file.parentFile!!.mkdirs(); InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } } }
    @Test fun actualAndroidAudioDecodingConvertsTheSelectedRangeToMono16k():Unit=runBlocking {
        val input=source(); val decoded=File(app.cacheDir,"${UUID.randomUUID()}.wav"); val mono=File(app.cacheDir,"${UUID.randomUUID()}.wav")
        try {
            AudioEngine.extractWav(input,300,1300,decoded) {}
            withContext(Dispatchers.IO) { SpeechPcm.convert(decoded,mono) }
            val actual=SpeechPcm.inspect(mono)
            assertEquals(16000,actual.rate); assertEquals(1,actual.channels); assertTrue(actual.frames in 15500..16500)
        } finally { input.delete(); decoded.delete(); mono.delete() }
    }
    @Test fun offlineWorkerReportsMissingModelWithoutRequiringOrContactingAServer():Unit=runBlocking {
        assertFalse(SpeechModelStore.installed(File(app.filesDir,"models")))
        val input=source(); val id=UUID.randomUUID().toString(); val old=app.settings.flow.first()
        try {
            app.settings.backend("https://no-offline-request.invalid"); app.settings.consent(false)
            val recipe=EditorState(tool="clipper",inputs=listOf(input.path),video=true,clips=listOf(ClipEdit(input.path,0,2000,2400))).recipe(app.settings.flow.first()).put("speech_engine","offline").put("transcribe_index",0)
            val request=OneTimeWorkRequestBuilder<CreationWorker>().setInputData(workDataOf("project" to id)).build()
            app.database.projects().save(Project(id,"Missing offline model","transcribe",recipe.toString(),status="queued",workId=request.id.toString()))
            WorkManager.getInstance(app).enqueueUniqueWork(id,ExistingWorkPolicy.KEEP,request)
            val result=withTimeout(20000) { app.database.projects().observe().first { rows -> rows.any { it.id==id && it.status=="failed" } }.first { it.id==id } }
            assertTrue(result.error,result.error.contains("English speech model")); assertTrue(result.output.isEmpty())
        } finally { app.database.projects().delete(id); input.delete(); app.settings.backend(old.backend); app.settings.consent(old.remoteConsent) }
    }
}
