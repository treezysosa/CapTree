package studio.retro.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.data.*
import studio.retro.ai.work.CreationWorker
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhaseSixProjectTest {
    private val app:StudioApplication get()=ApplicationProvider.getApplicationContext()
    private fun photo():File=File(app.filesDir,"imports/${UUID.randomUUID()}.png").also { output ->
        output.parentFile!!.mkdirs(); val bitmap=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(60,120,170)) }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    @Test fun contentUriBackupRestoresRoomProjectAndRunsARealOfflineExport():Unit=runBlocking {
        val source=photo(); val zip=File(app.filesDir,"outputs/${UUID.randomUUID()}.retro.zip").apply { parentFile!!.mkdirs(); createNewFile() }
        val dao=app.database.projects(); val ids=ArrayList<String>(); val paths=ArrayList<String>()
        val original=Project(UUID.randomUUID().toString(),"Portable test","enhance",EditorState(tool="enhance",inputs=listOf(source.path),intensity=0.25f,faceRestore=true).recipe(Preferences(backend="https://PRIVATE_SERVER.invalid",remoteConsent=true)).toString())
        ids+=original.id
        val vm=withContext(Dispatchers.Main) { StudioViewModel(app) }
        try {
            dao.save(original)
            val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",zip)
            withContext(Dispatchers.Main) { vm.backup(original,uri) }
            withTimeout(20000) { vm.importing.first { !it } }
            assertTrue(vm.message.value,zip.length()>100)
            withContext(Dispatchers.Main) { vm.importBackup(uri) }
            withTimeout(20000) { vm.importing.first { !it } }
            val restored=withTimeout(10000) { dao.observe().first { rows -> rows.any { it.name=="Portable test RESTORED" } }.first { it.name=="Portable test RESTORED" } }
            ids+=restored.id
            val recipe=JSONObject(restored.recipe); val input=recipe.getJSONArray("inputs").getString(0); paths+=input
            assertArrayEquals(source.readBytes(),File(input).readBytes())
            assertFalse(recipe.getBoolean("face_restore")); assertFalse(recipe.getBoolean("consent")); assertEquals("",recipe.getString("backend"))
            withContext(Dispatchers.Main) { vm.resume(restored) }
            withTimeout(10000) { vm.editor.first { it.inputs==listOf(input) } }
            assertEquals(0.25f,vm.editor.value.intensity,0.001f)
            val existing=dao.observe().first().map { it.id }.toSet()
            withContext(Dispatchers.Main) { vm.create() }
            val result=withTimeout(90000) { dao.observe().first { rows -> rows.any { it.id !in existing && it.tool=="enhance" && it.status in setOf("completed","failed") } }.first { it.id !in existing && it.tool=="enhance" && it.status in setOf("completed","failed") } }
            ids+=result.id; paths+=result.output
            assertEquals(result.error,"completed",result.status)
            val bitmap=requireNotNull(BitmapFactory.decodeFile(result.output)); assertEquals(64,bitmap.width); assertEquals(48,bitmap.height); bitmap.recycle()
        } finally { ids.forEach { app.database.projects().delete(it) }; paths.forEach { File(it).delete() }; source.delete(); zip.delete() }
    }
    @Test fun workerRejectsFaceUploadWithoutTheSeparateConfirmation():Unit=runBlocking {
        val old=app.settings.flow.first(); val source=photo(); val id=UUID.randomUUID().toString()
        try {
            app.settings.backend("https://face-confirmation.invalid"); app.settings.consent(true)
            val recipe=EditorState(tool="enhance",inputs=listOf(source.path),faceRestore=true).recipe(app.settings.flow.first()).put("face_upload_confirmed",false)
            val request=OneTimeWorkRequestBuilder<CreationWorker>().setInputData(workDataOf("project" to id)).build()
            app.database.projects().save(Project(id,"No upload consent","enhance",recipe.toString(),status="queued",workId=request.id.toString()))
            androidx.work.WorkManager.getInstance(app).enqueueUniqueWork(id,ExistingWorkPolicy.KEEP,request)
            val result=withTimeout(20000) { app.database.projects().observe().first { rows -> rows.any { it.id==id && it.status=="failed" } }.first { it.id==id } }
            assertTrue(result.error,result.error.contains("Confirm processed-photo upload")); assertTrue(result.output.isEmpty())
        } finally { app.database.projects().delete(id); source.delete(); app.settings.backend(old.backend); app.settings.consent(old.remoteConsent) }
    }
}
