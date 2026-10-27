package studio.retro.ai

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.media.ModelValidator
import studio.retro.ai.media.UpscaleEngine
import java.io.File
import kotlin.math.abs

/** Runs only when the separate pretrained-model workflow supplies official test weights. */
@RunWith(AndroidJUnit4::class)
class PretrainedModelTest {
    @Test fun realEsrganMatchesTheIndependentCpuGoldenTile():Unit=runBlocking {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val available=assets.list("validation")?.contains("realesrgan-x4.onnx")==true
        if(InstrumentationRegistry.getArguments().getString("class").orEmpty().contains("PretrainedModelTest"))assertTrue("The dedicated model-validation run must supply real weights.",available)
        assumeTrue("Real pretrained weights are supplied by the model-validation workflow, not bundled in the APK.",available)
        val app:StudioApplication=ApplicationProvider.getApplicationContext()
        val model=File(app.filesDir,"models/realesrgan-x4.onnx").apply { parentFile!!.mkdirs() }
        try {
            assets.open("validation/realesrgan-x4.onnx").use { source -> model.outputStream().use { source.copyTo(it) } }
            val report=ModelValidator.probe(model)
            assertEquals(64,report.sha256.length)
            val input=requireNotNull(assets.open("validation/input.png").use(BitmapFactory::decodeStream))
            val expected=requireNotNull(assets.open("validation/expected-x4.png").use(BitmapFactory::decodeStream))
            var progress=0
            val actual=UpscaleEngine.upscale(app,input,4) { progress=it }
            assertEquals(100,progress); assertEquals(64,actual.width); assertEquals(64,actual.height)
            var maximumError=0
            for(y in 0 until 64)for(x in 0 until 64) {
                val a=actual.getPixel(x,y); val b=expected.getPixel(x,y)
                for(shift in listOf(0,8,16))maximumError=maxOf(maximumError,abs((a ushr shift and 255)-(b ushr shift and 255)))
            }
            assertTrue("Android ONNX pixel error: $maximumError",maximumError<=3)
            input.recycle(); expected.recycle(); actual.recycle()
        } finally { model.delete() }
    }
}
