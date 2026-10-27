package studio.retro.ai

import android.app.Application
import androidx.room.Room
import studio.retro.ai.data.StudioDatabase
import studio.retro.ai.data.StudioSettings
import studio.retro.ai.data.ProjectArchive
import kotlin.concurrent.thread

class StudioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Capture only folders from a previous process before any new restore can begin.
        val interrupted=ProjectArchive.interruptedStaging(filesDir)
        if(interrupted.isNotEmpty())thread(isDaemon=true,name="retro-staging-cleanup") { interrupted.forEach { it.deleteRecursively() } }
    }
    val database by lazy { Room.databaseBuilder(this, StudioDatabase::class.java, "save-games.db").build() }
    val settings by lazy { StudioSettings(this) }
}
