package studio.retro.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.data.DeviceReport

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
}
