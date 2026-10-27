package studio.retro.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** The same test APK runs against v0.9, then v0.10 after adb install -r. */
@RunWith(AndroidJUnit4::class)
class PhaseTenUpgradeTest {
    private val app:StudioApplication get()=ApplicationProvider.getApplicationContext()
    private fun enabled()=assumeTrue("The upgrade workflow supplies phase10_upgrade=1.",InstrumentationRegistry.getArguments().getString("phase10_upgrade")=="1")
    @Suppress("DEPRECATION") private fun installedVersion()=app.packageManager.getPackageInfo(app.packageName,0).versionCode
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun prepareOldInstallation():Unit=runBlocking {
        enabled(); assertEquals(9,installedVersion())
        val staged=File(app.filesDir,"outputs/phase10-source.png").apply { parentFile!!.mkdirs() }
        val bitmap=Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(90,130,80))
        staged.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        val vm=withContext(Dispatchers.Main) { StudioViewModel(app) }
        val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",staged)
        withContext(Dispatchers.Main) { vm.open("enhance"); vm.import(listOf(uri),false) }
        withTimeout(20000) { vm.editor.first { it.inputs.isNotEmpty() } }
        withContext(Dispatchers.Main) {
            vm.edit { it.copy(prompt="UPGRADE KEPT",seed="00123") }
            vm.edit { it.copy(prompt="UPGRADE REDO") }; vm.undo(); vm.flushAutosave()
        }.join()
        val source=File(vm.editor.value.inputs.single()); val dao=app.database.sessions()
        val session=dao.observe().first().mapNotNull { dao.get(it.id) }.first { EditorCheckpoints.decode(it.payload).current.inputs==listOf(source.path) }
        val project=Project(UUID.randomUUID().toString(),"UPGRADE FAVORITE","enhance",vm.editor.value.recipe(Preferences()).toString(),favorite=true)
        app.database.projects().save(project)
        app.settings.theme("Retro Green"); app.settings.quality(80); app.settings.haptics(false)
        File(app.filesDir,"phase10-upgrade.json").writeText(JSONObject().put("session",session.id).put("project",project.id).put("recipe",project.recipe).put("source",source.path).put("sha256",hash(source)).toString())
        staged.delete()
    }

    @Test fun verifyUpgradedInstallationAndExport():Unit=runBlocking {
        enabled(); assertEquals(10,installedVersion())
        val marker=JSONObject(File(app.filesDir,"phase10-upgrade.json").readText())
        val project=requireNotNull(app.database.projects().get(marker.getString("project")))
        assertEquals("UPGRADE FAVORITE",project.name); assertTrue(project.favorite); assertEquals(marker.getString("recipe"),project.recipe)
        val source=File(marker.getString("source")); assertEquals(marker.getString("sha256"),hash(source))
        val prefs=app.settings.flow.first(); assertEquals("Retro Green",prefs.theme); assertEquals(80,prefs.quality); assertFalse(prefs.haptics)
        val vm=withContext(Dispatchers.Main) { StudioViewModel(app).also { it.restoreDraft(marker.getString("session")) } }
        withTimeout(20000) { vm.editor.first { it.prompt=="UPGRADE KEPT" } }
        withContext(Dispatchers.Main) {
            assertEquals("00123",vm.editor.value.seed); assertTrue(vm.canRedo.value); assertTrue(vm.canUndo.value)
            assertFalse(vm.faceConfirmation.value); assertNull(vm.active.value)
            vm.redo(); assertEquals("UPGRADE REDO",vm.editor.value.prompt); vm.undo(); assertEquals("UPGRADE KEPT",vm.editor.value.prompt)
        }
        withTimeout(15000) { vm.prefs.first { it.theme=="Retro Green" && it.quality==80 } }
        val before=app.database.projects().observe().first().map { it.id }.toSet()
        withContext(Dispatchers.Main) { vm.create() }
        val result=withTimeout(90000) { app.database.projects().observe().first { rows -> rows.any { it.id !in before && it.status in setOf("completed","failed") } }.first { it.id !in before && it.status in setOf("completed","failed") } }
        assertEquals(result.error,"completed",result.status); assertEquals("image/jpeg",result.mime)
        val output=File(result.output); val decoded=requireNotNull(BitmapFactory.decodeFile(output.path))
        try { assertEquals(48,decoded.width); assertEquals(32,decoded.height) } finally { decoded.recycle() }
        val diagnostics=File(app.getExternalFilesDir(null),"upgrade-diagnostics").apply { mkdirs() }
        output.copyTo(File(diagnostics,"upgraded-photo.jpg"),overwrite=true)
        File(diagnostics,"upgrade-report.json").writeText(JSONObject().put("from_version_code",9).put("to_version_code",10).put("android_api",android.os.Build.VERSION.SDK_INT).put("project_favorite_recipe_preserved",true).put("settings_preserved",true).put("undo_redo_preserved",true).put("import_hash_preserved",true).put("offline_export_sha256",hash(output)).toString(2))
    }
}
