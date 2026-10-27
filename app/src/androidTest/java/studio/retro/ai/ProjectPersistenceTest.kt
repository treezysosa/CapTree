package studio.retro.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.data.*

@RunWith(AndroidJUnit4::class)
class ProjectPersistenceTest {
    @Test fun deletingAProcessingProjectPreventsWorkerResurrection()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),StudioDatabase::class.java).build()
        try {
            val dao=db.projects()
            dao.save(Project("deleted","Test","gif","{}",status="processing"))
            dao.delete("deleted")
            assertEquals(0,dao.status("deleted","failed","cancelled"))
            assertEquals(0,dao.complete("deleted","{}","/unused/output.gif","image/gif"))
            assertNull(dao.get("deleted"))
        } finally { db.close() }
    }
    @Test fun renamingAndFavoritingPreservesWorkerOutput()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),StudioDatabase::class.java).build()
        try {
            val dao=db.projects(); dao.save(Project("one","Test","gif","{}",status="processing"))
            dao.complete("one","{\"fps\":8}","/unused/output.gif","image/gif")
            dao.rename("one","My animation"); dao.favorite("one",true)
            val p=dao.get("one")!!
            assertEquals("completed",p.status); assertEquals("/unused/output.gif",p.output); assertTrue(p.favorite); assertEquals("My animation",p.name)
        } finally { db.close() }
    }
}
