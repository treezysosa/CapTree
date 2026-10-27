package studio.retro.ai

import android.app.Application
import androidx.room.Room
import studio.retro.ai.data.StudioDatabase
import studio.retro.ai.data.StudioSettings
import studio.retro.ai.data.ProjectArchive
import studio.retro.ai.media.SpeechModelStore
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import studio.retro.ai.data.DraftWriter

class StudioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Capture only folders from a previous process before any new restore can begin.
        val interrupted=ProjectArchive.interruptedStaging(filesDir)+SpeechModelStore.recover(java.io.File(filesDir,"models"))
        if(interrupted.isNotEmpty())thread(isDaemon=true,name="retro-staging-cleanup") { interrupted.forEach { it.deleteRecursively() } }
    }
    val database by lazy { Room.databaseBuilder(this, StudioDatabase::class.java, "save-games.db").addMigrations(StudioDatabase.MIGRATION_1_2).build() }
    val settings by lazy { StudioSettings(this) }
    val draftScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val draftWriter by lazy { DraftWriter(database.sessions()) }
}
