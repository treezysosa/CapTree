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
import studio.retro.ai.media.CaptionCue
import studio.retro.ai.media.CaptionTrack
import studio.retro.ai.data.Project
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.io.File
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class StudioSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun homeContinueRestoresTheActualAutomaticDraft() {
        val app:StudioApplication=ApplicationProvider.getApplicationContext(); val token="UI DRAFT ${UUID.randomUUID()}"
        lateinit var vm:StudioViewModel; var saved:String?=null
        try {
            compose.runOnIdle { vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]; vm.message.value=""; vm.open("photo"); vm.edit { it.copy(prompt=token,seed="00123") } }
            compose.waitUntil(15000) { vm.autosaveStatus.value.startsWith("AUTO-SAVED") }
            saved=runBlocking { val dao=app.database.sessions(); dao.observe().first().mapNotNull { dao.get(it.id) }.first { studio.retro.ai.data.EditorCheckpoints.decode(it.payload).current.prompt==token }.id }
            compose.runOnIdle { vm.open("photo"); vm.back() }
            compose.onNodeWithText("CONTINUE LAST DRAFT").assertExists().performClick()
            compose.waitUntil(15000) { vm.editor.value.prompt==token && vm.screen.value=="editor" }
            compose.onNodeWithText("RECOVERED AUTO-SAVE").assertExists()
            compose.runOnIdle { assertEquals("00123",vm.editor.value.seed); vm.discardDraft(requireNotNull(saved)); vm.back() }
        } finally { saved?.let { runBlocking { app.draftWriter.delete(it) } } }
    }
    @Test fun offlineSubtitlesShowModelReadinessAndOpenTheSpeechSettings() {
        lateinit var vm:StudioViewModel
        compose.runOnIdle {
            vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]; vm.open("clipper")
            vm.editor.value=vm.editor.value.copy(clips=listOf(ClipEdit("/local/clip.mp4",0,2000,2000)),start="0",end="2")
            vm.speechInstalled.value=false
        }
        compose.onNodeWithText("OFFLINE ENGLISH SUBTITLES").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("SPEECH MODEL SETTINGS").performScrollTo().performClick()
        compose.onNodeWithText("OFFLINE SPEECH CARTRIDGE").performScrollTo().assertExists()
        compose.onNodeWithText("IMPORT ENGLISH MODEL ZIP").assertExists()
        compose.runOnIdle { vm.back() }
    }
    @Test fun visualHighlightsRequireExplicitFrameConsentAndCancellationStartsNoJob() {
        val app:StudioApplication=ApplicationProvider.getApplicationContext(); val previous=runBlocking { app.settings.flow.first() }
        val file=File(app.cacheDir,"ui-highlight-consent-${UUID.randomUUID()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { source -> file.outputStream().use { source.copyTo(it) } }
        lateinit var vm:StudioViewModel; var cleanup:StudioViewModel?=null
        try {
            runBlocking { app.settings.backend("https://visual-consent.invalid"); app.settings.consent(true) }
            compose.runOnIdle {
                vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]; cleanup=vm; vm.open("clipper")
                vm.editor.value=vm.editor.value.copy(inputs=listOf(file.path),video=true,start="0",end="2.4",clips=listOf(ClipEdit(file.path,0,2400,2400)),highlightPrompt="a colorful scene")
            }
            compose.waitUntil(10000) { vm.prefs.value.remoteConsent && vm.prefs.value.backend=="https://visual-consent.invalid" }
            compose.onNodeWithText("AI VISUAL HIGHLIGHTS · SERVER").performScrollTo().performClick()
            compose.onNodeWithText("SEND SELECTED FRAMES?").assertExists(); compose.onNodeWithText("SEND FRAMES").assertExists()
            compose.onNodeWithText("CANCEL").performClick()
            compose.runOnIdle { assertNull(vm.active.value); assertNull(vm.highlightConfirmation.value); assertEquals("editor",vm.screen.value) }
        } finally { compose.runOnIdle { cleanup?.back() }; file.delete(); runBlocking { app.settings.backend(previous.backend); app.settings.consent(previous.remoteConsent) } }
    }
    @Test fun faceRestorationAsksBeforeUploadFromBothTouchAndActionButton() {
        val app:StudioApplication=ApplicationProvider.getApplicationContext(); val previous=runBlocking { app.settings.flow.first() }
        val photo=File(app.cacheDir,"face-consent-${UUID.randomUUID()}.png")
        val bitmap=android.graphics.Bitmap.createBitmap(64,48,android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        lateinit var vm:StudioViewModel; var cleanup:StudioViewModel?=null
        try {
            runBlocking { app.settings.backend("https://face-consent.invalid"); app.settings.consent(true) }
            compose.runOnIdle { vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]; cleanup=vm; vm.open("enhance"); vm.editor.value=vm.editor.value.copy(inputs=listOf(photo.path),faceRestore=true) }
            compose.waitUntil(10000) { vm.prefs.value.remoteConsent && vm.prefs.value.backend=="https://face-consent.invalid" }
            compose.onNodeWithText("START PROCESSING").performScrollTo().performClick()
            compose.onNodeWithText("SEND PROCESSED PHOTO?").assertExists(); compose.onNodeWithText("SEND PHOTO").assertExists()
            compose.onNodeWithText("CANCEL").performClick()
            compose.onNodeWithText("A").performClick()
            compose.onNodeWithText("SEND PROCESSED PHOTO?").assertExists()
            compose.onNodeWithText("CANCEL").performClick()
            compose.runOnIdle { assertNull(vm.active.value); assertEquals("editor",vm.screen.value) }
        } finally { compose.runOnIdle { cleanup?.back() }; photo.delete(); runBlocking { app.settings.backend(previous.backend); app.settings.consent(previous.remoteConsent) } }
    }
    @Test fun homeCartridgeOpensRealOfflineEditor() {
        compose.onNodeWithTag("feature-cartridges").performScrollToIndex(4)
        compose.onNodeWithText("AUTO\nENHANCE").performClick()
        compose.onNodeWithText("IMPORT PHOTOS").assertExists()
        compose.onNodeWithText("START PROCESSING").performScrollTo().assertExists()
    }
    @Test fun timelineButtonsRetimeCaptionsAndUndoRedoRestoreTheWholeEdit() {
        lateinit var vm:StudioViewModel
        val first=ClipEdit("/local/first.mp4",0,1000,1000)
        val second=ClipEdit("/local/second.mp4",0,1000,1000)
        val captions=CaptionTrack.srt(listOf(CaptionCue(100,600,"FIRST"),CaptionCue(1100,1600,"SECOND")))
        compose.runOnIdle {
            vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]; vm.open("clipper")
            vm.editor.value=vm.editor.value.copy(clips=listOf(first,second),selectedClip=1,start="0",end="1",captions=captions)
        }
        compose.onNodeWithText("UNDO").assertIsNotEnabled()
        compose.onNodeWithText("← MOVE").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("SECOND",CaptionTrack.textAt(CaptionTrack.parse(vm.editor.value.captions,2000),200)) }
        compose.onNodeWithText("UNDO").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(first.id,vm.editor.value.clips.first().id); assertEquals(captions,vm.editor.value.captions) }
        compose.onNodeWithText("REDO").performClick()
        compose.runOnIdle { assertEquals(second.id,vm.editor.value.clips.first().id) }
        compose.onNodeWithText("REMOVE").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(first),vm.editor.value.clips); assertEquals(listOf(CaptionCue(100,600,"FIRST")),CaptionTrack.parse(vm.editor.value.captions,1000)) }
        compose.onNodeWithText("UNDO").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2,vm.editor.value.clips.size); vm.back() }
    }
    @Test fun resultComparisonControlsPlayPauseAndCloseLinkedPreviews() {
        val app:StudioApplication=ApplicationProvider.getApplicationContext()
        val id=UUID.randomUUID().toString(); val before=File(app.cacheDir,"compare-before-$id.mp4"); val after=File(app.cacheDir,"compare-after-$id.mp4")
        for((name,file) in listOf("fixture.mp4" to before,"fixture-muted.mp4" to after))InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { source -> file.outputStream().use { source.copyTo(it) } }
        val recipe=JSONObject().put("inputs",JSONArray(listOf(before.path))).put("video",true).toString()
        val project=Project(id,"Linked UI test","enhance",recipe,status="completed",output=after.path,mime="video/mp4")
        lateinit var vm:StudioViewModel; var cleanup:StudioViewModel?=null
        try {
            runBlocking { app.database.projects().save(project) }
            compose.runOnIdle { vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]; cleanup=vm; vm.active.value=id; vm.screen.value="processing" }
            compose.waitUntil(10000) { vm.projects.value.any { it.id==id } }
            compose.onNodeWithText("Linked").performScrollTo().performClick()
            compose.onNodeWithText("LINKED PREVIEW").assertExists()
            compose.onNodeWithText("PLAY BOTH").performScrollTo()
            compose.waitUntil(20000) { runCatching { compose.onNodeWithText("PLAY BOTH").assertIsEnabled() }.isSuccess }
            compose.onNodeWithText("PLAY BOTH").performClick()
            compose.onNodeWithText("PAUSE BOTH").performClick()
            compose.onNodeWithText("PLAY BOTH").assertExists()
            compose.onNodeWithText("After").performScrollTo().performClick()
            compose.onNodeWithText("LINKED PREVIEW").assertDoesNotExist()
        } finally {
            compose.runOnIdle { cleanup?.back() }
            runBlocking { app.database.projects().delete(id) }; before.delete(); after.delete()
        }
    }
    @Test fun automaticSubtitlesAskBeforeSendingSelectedAudio() {
        val app:StudioApplication=ApplicationProvider.getApplicationContext()
        val file=File(app.cacheDir,"ui-consent.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { source -> file.outputStream().use { source.copyTo(it) } }
        lateinit var vm:StudioViewModel
        try {
            compose.runOnIdle {
                vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]
                vm.open("clipper")
                vm.editor.value=vm.editor.value.copy(inputs=listOf(file.path),video=true,start="0",end="2.4",clips=listOf(ClipEdit(file.path,0,2400,2400)))
            }
            compose.onNodeWithText("AI AUTO SUBTITLES · SERVER").performScrollTo().performClick()
            compose.onNodeWithText("SEND SELECTED AUDIO?").assertExists()
            compose.onNodeWithText("SEND AUDIO").assertExists()
            compose.onNodeWithText("CANCEL").performClick()
            compose.runOnIdle { assertEquals("editor",vm.screen.value); assertTrue(vm.projects.value.none { it.tool=="transcribe" }) }
        } finally { compose.runOnIdle { vm.back() }; file.delete() }
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
            compose.runOnIdle { assertEquals(2,vm.editor.value.clips.size); assertEquals(2400L,vm.editor.value.clips.sumOf { it.durationMs }) }
            // Bring the vertical editor to the timeline before scrolling its nested row.
            compose.onNodeWithText("CLIP TIMELINE",substring=true).performScrollTo()
            compose.onNodeWithText("CLIP 2").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.editor.value.selectedClip==1 }
            compose.onNodeWithText("CLIP 2").assertIsSelected()
            val selectedId=vm.editor.value.clips[1].id
            compose.onNodeWithText("← MOVE").performScrollTo().assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(selectedId,vm.editor.value.clips[0].id); assertEquals(0,vm.editor.value.selectedClip) }
            compose.runOnIdle { vm.active.value=null; vm.screen.value="processing" }
            compose.onNodeWithText("B").performClick()
            compose.onNodeWithText("CHOOSE YOUR POWER").assertExists()
        } finally { compose.runOnIdle { cleanup?.back() }; file.delete() }
    }

    @Test fun clipSuggestionsScanRealMediaAndApplyTheirRange() {
        val app:StudioApplication=ApplicationProvider.getApplicationContext()
        val file=File(app.cacheDir,"ui-suggestions.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { source -> file.outputStream().use { source.copyTo(it) } }
        lateinit var vm:StudioViewModel
        var cleanup:StudioViewModel?=null
        try {
            compose.runOnIdle {
                vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java]; cleanup=vm
                vm.open("clipper")
                vm.editor.value=vm.editor.value.copy(inputs=listOf(file.path),video=true,start="0",end="2.4",clips=listOf(ClipEdit(file.path,0,2400,2400)))
            }
            compose.onNodeWithText("SUGGEST CLIP RANGES").performScrollTo().performClick()
            compose.waitUntil(30000) { vm.editor.value.suggestedClips.isNotEmpty() && !vm.importing.value }
            val suggested=vm.editor.value.suggestedClips.first()
            compose.onNodeWithText("USE THIS RANGE").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(suggested.startMs,vm.editor.value.clips.first().startMs)
                assertEquals(suggested.endMs,vm.editor.value.clips.first().endMs)
                assertTrue(vm.editor.value.suggestedClips.isEmpty())
            }
        } finally { compose.runOnIdle { cleanup?.back() }; file.delete() }
    }
}
