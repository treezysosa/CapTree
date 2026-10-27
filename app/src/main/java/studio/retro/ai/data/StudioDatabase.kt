package studio.retro.ai.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
@Database(entities = [Project::class], version = 1, exportSchema = false)
abstract class StudioDatabase : RoomDatabase() { abstract fun projects(): ProjectDao }
