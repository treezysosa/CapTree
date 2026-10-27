package studio.retro.ai.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey val id: String,
    val name: String,
    val tool: String,
    val recipe: String,
    val created: Long = System.currentTimeMillis(),
    val status: String = "draft",
    val output: String = "",
    val mime: String = "",
    val favorite: Boolean = false,
    val error: String = "",
    val workId: String = ""
)
@Dao interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY created DESC") fun observe(): Flow<List<Project>>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun get(id: String): Project?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(project: Project)
    @Query("UPDATE projects SET status = :status, error = :error WHERE id = :id") suspend fun status(id:String,status:String,error:String=""):Int
    @Query("UPDATE projects SET status = 'completed', recipe = :recipe, output = :output, mime = :mime, error = '' WHERE id = :id") suspend fun complete(id:String,recipe:String,output:String,mime:String):Int
    @Query("UPDATE projects SET name = :name WHERE id = :id") suspend fun rename(id:String,name:String)
    @Query("UPDATE projects SET favorite = :favorite WHERE id = :id") suspend fun favorite(id:String,favorite:Boolean)
    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: String)
}
@Entity(tableName="editor_sessions")
data class EditorSession(@PrimaryKey val id:String,val tool:String,val payload:String,val media:String,val revision:Long,val updated:Long,val label:String)
data class SessionSummary(val id:String,val tool:String,val updated:Long,val label:String)
@Dao abstract class SessionDao {
    @Query("SELECT id,tool,updated,label FROM editor_sessions ORDER BY revision DESC,id DESC") abstract fun observe():Flow<List<SessionSummary>>
    @Query("SELECT * FROM editor_sessions WHERE id=:id") abstract suspend fun get(id:String):EditorSession?
    @Query("SELECT media FROM editor_sessions") abstract suspend fun media():List<String>
    @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun put(session:EditorSession)
    @Query("DELETE FROM editor_sessions WHERE id=:id") abstract suspend fun delete(id:String)
    @Transaction open suspend fun putIfNewer(session:EditorSession):Boolean {
        val previous=get(session.id)
        if(previous!=null && previous.revision>=session.revision)return false
        put(session); return true
    }
}
@Database(entities = [Project::class,EditorSession::class], version = 2, exportSchema = false)
abstract class StudioDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun sessions(): SessionDao
    companion object {
        val MIGRATION_1_2=object:Migration(1,2) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `editor_sessions` (`id` TEXT NOT NULL, `tool` TEXT NOT NULL, `payload` TEXT NOT NULL, `media` TEXT NOT NULL, `revision` INTEGER NOT NULL, `updated` INTEGER NOT NULL, `label` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }
    }
}
