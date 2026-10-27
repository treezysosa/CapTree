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
    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: String)
}
@Database(entities = [Project::class], version = 1, exportSchema = false)
abstract class StudioDatabase : RoomDatabase() { abstract fun projects(): ProjectDao }
