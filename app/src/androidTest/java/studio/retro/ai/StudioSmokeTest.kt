package studio.retro.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import studio.retro.ai.media.ClipEdit
import java.io.File
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class StudioSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun homeCartridgeOpensRealOfflineEditor() {
        compose.onNodeWithText("AUTO\nENHANCE").performScrollTo().performClick()
        compose.onNodeWithText("IMPORT PHOTOS").assertExists()
        compose.onNodeWithText("START PROCESSING").performScrollTo().assertExists()
    }
    @Test fun timelineSplitAndMoveButtonsChangeTheActualEditOrder() {
        val app:StudioApplication=ApplicationProvider.getApplicationContext()
        val file=File(app.cacheDir,"ui-timeline.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { source -> file.outputStream().use { source.copyTo(it) } }
        lateinit var vm:StudioViewModel
        var cleanup:StudioViewModel?=null
        try {
            compose.runOnIdle {
                vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]
                cleanup=vm
                vm.open("clipper")
                vm.editor.value=vm.editor.value.copy(inputs=listOf(file.path),video=true,start="0",end="2.4",clips=listOf(ClipEdit(file.path,0,2400,2400)))
                vm.seek(1200)
            }
            compose.waitUntil(10000) { vm.editor.value.playheadMs in 800L..1600L }
            compose.onNodeWithText("SPLIT AT PLAYHEAD").performScrollTo().performClick()
            compose.onNodeWithText("A · OK").performClick()
            compose.runOnIdle { assertEquals(2,vm.editor.value.clips.size); assertEquals(2400L,vm.editor.value.clips.sumOf { it.durationMs }) }
            compose.onNodeWithText("CLIP 2").performScrollTo().performClick()
            val selectedId=vm.editor.value.clips[1].id
            compose.onNodeWithText("← MOVE").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(selectedId,vm.editor.value.clips[0].id); assertEquals(0,vm.editor.value.selectedClip) }
        } finally { compose.runOnIdle { cleanup?.back() }; file.delete() }
    }
}
