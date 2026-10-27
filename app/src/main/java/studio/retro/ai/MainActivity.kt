package studio.retro.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import studio.retro.ai.ui.StudioApp

class MainActivity:ComponentActivity() {
    private val studio by viewModels<StudioViewModel>()
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { StudioApp(studio) } }
    override fun onStop() { studio.flushAutosave(); super.onStop() }
}
