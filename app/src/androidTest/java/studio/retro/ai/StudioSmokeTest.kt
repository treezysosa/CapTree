package studio.retro.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun homeCartridgeOpensRealOfflineEditor() {
        compose.onNodeWithText("AUTO\nENHANCE").performScrollTo().performClick()
        compose.onNodeWithText("IMPORT PHOTOS").assertExists()
        compose.onNodeWithText("START PROCESSING").performScrollTo().assertExists()
    }
}
