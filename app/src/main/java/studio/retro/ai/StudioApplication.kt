package studio.retro.ai

import android.app.Application
import androidx.room.Room
import studio.retro.ai.data.StudioDatabase
import studio.retro.ai.data.StudioSettings

class StudioApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, StudioDatabase::class.java, "save-games.db").build() }
    val settings by lazy { StudioSettings(this) }
}
