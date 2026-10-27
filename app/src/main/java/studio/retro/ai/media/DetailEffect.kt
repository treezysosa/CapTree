package studio.retro.ai.media

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Spatial edge-aware denoising and an unsharp mask. This is conventional GPU processing. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class DetailEffect(private val denoise: Float, private val sharpen: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        DetailProgram(context, useHdr, denoise.coerceIn(0f, 1f), sharpen.coerceIn(0f, 1f))

    private class DetailProgram(context: Context, useHdr: Boolean, denoise: Float, sharpen: Float) : BaseGlShaderProgram(useHdr, 1) {
        private val program = try {
            GlProgram(context, "shaders/retro_detail.vert", "shaders/retro_detail.frag").apply {
                setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                setFloatUniform("uDenoise", denoise)
                setFloatUniform("uSharpen", sharpen)
            }
        } catch (error: Exception) { throw VideoFrameProcessingException(error) }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            program.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexture", inputTexId, 0)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (error: Exception) { throw VideoFrameProcessingException(error, presentationTimeUs) }
        }

        override fun release() {
            try { program.delete() } catch (error: Exception) { throw VideoFrameProcessingException(error) }
            finally { super.release() }
        }
    }
}
