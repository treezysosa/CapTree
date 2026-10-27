package studio.retro.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.data.DeviceReport
import studio.retro.ai.media.ClipEdit
import kotlinx.coroutines.*

@RunWith(AndroidJUnit4::class)
class PhaseTenDeviceTest {
    @Test fun localDeviceReportDescribesTheInstalledBuildWithoutPrivateData() {
        val app=ApplicationProvider.getApplicationContext<StudioApplication>()
        val text=DeviceReport.collect(app); val report=JSONObject(text)
        assertEquals("studio.retro.ai",report.getString("application_id"))
        assertEquals(10,report.getLong("version_code")); assertEquals("0.10.0",report.getString("version_name"))
        assertTrue(report.getLong("page_size_bytes") in listOf(4096L,16384L,65536L))
        assertTrue(report.getLong("app_heap_limit_bytes")>0)
        assertTrue(report.getJSONArray("cpu_abis").length()>0)
        assertTrue(report.getJSONArray("signer_sha256").getString(0).matches(Regex("[0-9a-f]{64}")))
        assertFalse(text.contains(app.filesDir.path)); assertFalse(text.contains("backend")); assertFalse(text.contains("token"))
    }
    @Test fun onnxNativeRuntimeLoadsOnTheEmulatorCpu() {
        // This checks the bundled native loader, not inference with an absent model.
        assertTrue(ai.onnxruntime.OrtEnvironment.getEnvironment().version.isNotBlank())
    }
    @Test fun latePreviewPositionsCannotOverrideASeekOrAnotherSelectedBlock():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<StudioApplication>()
        withContext(Dispatchers.Main) {
            val vm=StudioViewModel(app); vm.open("clipper")
            val first=ClipEdit("/local/first.mp4",0,2400,2400)
            val second=ClipEdit("/local/second.mp4",300,2000,2400)
            vm.editor.value=vm.editor.value.copy(clips=listOf(first,second),start="0",end="2.4")
            vm.seek(1200)
            vm.previewPosition(first.id,null,0); assertEquals(1200L,vm.editor.value.playheadMs)
            vm.previewPosition(first.id,1200,1250); assertEquals(1250L,vm.editor.value.playheadMs)
            vm.selectClip(1)
            vm.previewPosition(first.id,300,900); assertEquals(300L,vm.editor.value.playheadMs)
            vm.previewPosition(second.id,300,0); assertEquals(300L,vm.editor.value.playheadMs)
            vm.previewPosition(second.id,300,500); assertEquals(500L,vm.editor.value.playheadMs)
            vm.open("enhance")
        }
    }
}
