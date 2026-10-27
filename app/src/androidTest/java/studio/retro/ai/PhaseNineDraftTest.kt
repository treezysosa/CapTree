package studio.retro.ai

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.data.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhaseNineDraftTest {
    private val app:StudioApplication get()=ApplicationProvider.getApplicationContext()
    private fun photo()=File(app.filesDir,"imports/${UUID.randomUUID()}.png").also { file ->
        file.parentFile!!.mkdirs(); val bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    private suspend fun find(prompt:String):EditorSession {
        val dao=app.database.sessions()
        return dao.observe().first().mapNotNull { dao.get(it.id) }.first { EditorCheckpoints.decode(it.payload).current.prompt==prompt }
    }
    @Test fun automaticDraftsRecoverUndoRedoWithoutRestoringUploadConfirmations():Unit=runBlocking {
        val tag=UUID.randomUUID().toString()
        val vm=withContext(Dispatchers.Main) { StudioViewModel(app) }; var id:String?=null
        try {
            withContext(Dispatchers.Main) {
                vm.open("photo"); vm.edit { it.copy(prompt="$tag first") }; vm.edit { it.copy(prompt="$tag second",seed="00123") }; vm.undo(); vm.faceConfirmation.value=true
                vm.flushAutosave()
            }.join()
            val saved=find("$tag first"); id=saved.id
            assertFalse(saved.payload.contains("\"backend\"")); assertFalse(saved.payload.contains("upload_confirmed"))
            val restored=withContext(Dispatchers.Main) { StudioViewModel(app).also { it.restoreDraft(saved.id) } }
            withTimeout(15000) { restored.editor.first { it.prompt=="$tag first" } }
            withContext(Dispatchers.Main) {
                assertTrue(restored.canUndo.value); assertTrue(restored.canRedo.value); assertFalse(restored.faceConfirmation.value); assertNull(restored.highlightConfirmation.value); assertNull(restored.active.value)
                restored.redo(); assertEquals("$tag second",restored.editor.value.prompt); assertEquals("00123",restored.editor.value.seed)
                restored.undo(); assertEquals("$tag first",restored.editor.value.prompt)
                restored.open("enhance")
            }
        } finally { id?.let { app.draftWriter.delete(it) }; withContext(Dispatchers.Main) { vm.open("enhance") } }
    }
    @Test fun storageCleanupKeepsMediaReferencedOnlyByASavedUndoStep():Unit=runBlocking {
        val first=photo(); val second=photo(); val vm=withContext(Dispatchers.Main) { StudioViewModel(app) }; var id:String?=null
        val token=UUID.randomUUID().toString()
        try {
            withContext(Dispatchers.Main) {
                vm.open("enhance"); vm.edit { it.copy(inputs=listOf(first.path),prompt=token) }; vm.edit { it.copy(inputs=listOf(second.path)) }; vm.flushAutosave()
            }.join()
            val saved=find(token); id=saved.id
            assertTrue(EditorCheckpoints.mediaPaths(saved.media).contains(first.path))
            withContext(Dispatchers.Main) { vm.open("photo") }
            val blank=withContext(Dispatchers.Main) { StudioViewModel(app) }
            withContext(Dispatchers.Main) { blank.clean() }
            withTimeout(15000) { blank.importing.first { !it } }
            assertTrue(first.isFile); assertTrue(second.isFile)
            app.draftWriter.delete(saved.id)
            withContext(Dispatchers.Main) { blank.clean() }
            withTimeout(15000) { blank.importing.first { !it } }
            assertFalse(first.exists()); assertFalse(second.exists())
        } finally { id?.let { app.draftWriter.delete(it) }; first.delete(); second.delete() }
    }
    @Test fun olderWritesAndLateWritesAfterDiscardCannotReplaceADraft():Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(app,StudioDatabase::class.java).build()
        try {
            val writer=DraftWriter(db.sessions()); val newer=EditorSession("id","photo","{}","[]",20,20,"new")
            assertTrue(writer.save(newer)); assertFalse(writer.save(newer.copy(revision=10,label="old"))); assertEquals("new",db.sessions().get("id")!!.label)
            val pinned=writer.retain("other",30,setOf("/private/undo.png"))
            writer.withRetainedMedia { assertTrue(it.contains("/private/undo.png")) }; writer.release(pinned)
            writer.delete("id"); assertFalse(writer.save(newer.copy(revision=100))); assertNull(db.sessions().get("id"))
        } finally { db.close() }
    }
    @Test fun cleanupConsultsTheDatabaseBeforeTheUiProjectListHasLoaded():Unit=runBlocking {
        val source=photo(); val id=UUID.randomUUID().toString()
        try {
            val recipe=EditorState(inputs=listOf(source.path)).recipe(Preferences()).toString()
            app.database.projects().save(Project(id,"Queued job protection","enhance",recipe,status="queued"))
            val vm=withContext(Dispatchers.Main) { StudioViewModel(app).also { it.clean() } }
            withTimeout(15000) { vm.message.first { it.contains("Wait for processing") } }
            assertTrue(source.isFile); assertFalse(vm.importing.value)
        } finally { app.database.projects().delete(id); source.delete() }
    }
    @Test fun versionOneDatabaseMigrationPreservesExistingSaves():Unit=runBlocking {
        val name="phase9-migration-${UUID.randomUUID()}.db"; val path=app.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        var upgraded:StudioDatabase?=null
        try {
            SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
                old.execSQL("CREATE TABLE projects (id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,tool TEXT NOT NULL,recipe TEXT NOT NULL,created INTEGER NOT NULL,status TEXT NOT NULL,output TEXT NOT NULL,mime TEXT NOT NULL,favorite INTEGER NOT NULL,error TEXT NOT NULL,workId TEXT NOT NULL)")
                old.execSQL("INSERT INTO projects VALUES (?,?,?,?,?,?,?,?,?,?,?)",arrayOf("legacy","Existing save","gif","{\"fps\":8}",1L,"completed","/kept/output.gif","image/gif",1,"","old-work"))
                old.version=1
            }
            val db=Room.databaseBuilder(app,StudioDatabase::class.java,name).addMigrations(StudioDatabase.MIGRATION_1_2).build(); upgraded=db
            val saved=requireNotNull(db.projects().get("legacy")); assertEquals("Existing save",saved.name); assertEquals("/kept/output.gif",saved.output); assertTrue(saved.favorite); assertEquals("old-work",saved.workId)
            assertTrue(db.sessions().observe().first().isEmpty())
            assertTrue(DraftWriter(db.sessions()).save(EditorSession("new","photo","{}","[]",1,1,"Draft")))
        } finally { upgraded?.close(); app.deleteDatabase(name) }
    }
}
