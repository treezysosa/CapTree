package studio.retro.ai.media

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.PassthroughShaderProgram

/** Timestamp-based frame selection without a look-ahead texture cache. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class FrameRateEffect(private val fps:Int):GlEffect {
    override fun toGlShaderProgram(context:Context,useHdr:Boolean)=object:PassthroughShaderProgram() {
        private val cadence=FrameCadence(fps)
        override fun queueInputFrame(provider:GlObjectsProvider,texture:GlTextureInfo,timeUs:Long) {
            if(cadence.keep(timeUs))super.queueInputFrame(provider,texture,timeUs)
            else { getInputListener().onInputFrameProcessed(texture); getInputListener().onReadyToAcceptInputFrame() }
        }
        override fun signalEndOfCurrentInputStream() { cadence.reset(); super.signalEndOfCurrentInputStream() }
        override fun flush() { cadence.reset(); super.flush() }
    }
}
