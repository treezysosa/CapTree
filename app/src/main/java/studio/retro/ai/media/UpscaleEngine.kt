package studio.retro.ai.media

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.min

/** Real-ESRGAN RGB float32 NCHW x4 ONNX, tiled with a context halo. No interpolated fallback. */
object UpscaleEngine {
    suspend fun upscale(context: Context, image: Bitmap, scale: Int, progress: suspend (Int)->Unit): Bitmap {
        require(scale==2 || scale==4)
        val model=File(context.filesDir,"models/realesrgan-x4.onnx")
        require(model.isFile) { "Install a compatible Real-ESRGAN x4 ONNX model in Settings first. See docs/MODELS.md." }
        val memory=ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val outputBytes=image.width.toLong()*image.height*16*4
        require(!memory.lowMemory && outputBytes < min(memory.availMem/4,Runtime.getRuntime().maxMemory()/3)) { "Insufficient memory. Import a smaller photo (try 512 pixels). Tiled AI still needs output memory." }
        val env=OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            env.createSession(model.path,options).use { session ->
                val inputInfo=session.inputInfo.values.first().info as? TensorInfo ?: error("Model must accept a tensor.")
                require(inputInfo.type==OnnxJavaType.FLOAT && inputInfo.shape.size==4) { "Model requires float32 [1,3,H,W] input." }
                val tile=64; val halo=16; val size=tile+halo*2
                require(inputInfo.shape[2]<=0 || inputInfo.shape[2]==size.toLong()) { "Model must use dynamic spatial axes or fixed 96×96 input." }
                val output=Bitmap.createBitmap(image.width*4,image.height*4,Bitmap.Config.ARGB_8888)
                val total=((image.width+tile-1)/tile)*((image.height+tile-1)/tile); var done=0
                try {
                    for(y in 0 until image.height step tile) for(x in 0 until image.width step tile) {
                        currentCoroutineContext().ensureActive()
                        val floats=FloatArray(3*size*size)
                        for(ty in 0 until size) for(tx in 0 until size) {
                            val c=image.getPixel((x+tx-halo).coerceIn(0,image.width-1),(y+ty-halo).coerceIn(0,image.height-1)); val i=ty*size+tx
                            floats[i]=(c ushr 16 and 255)/255f; floats[size*size+i]=(c ushr 8 and 255)/255f; floats[size*size*2+i]=(c and 255)/255f
                        }
                        OnnxTensor.createTensor(env,FloatBuffer.wrap(floats),longArrayOf(1,3,size.toLong(),size.toLong())).use { tensor ->
                            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                                val value=result[0] as OnnxTensor; val shape=value.info.shape; val side=size*4
                                require(shape.contentEquals(longArrayOf(1,3,side.toLong(),side.toLong()))) { "Expected x4 RGB NCHW output; this ONNX model is incompatible." }
                                val data=value.floatBuffer; val tw=min(tile,image.width-x)*4; val th=min(tile,image.height-y)*4; val pixels=IntArray(tw*th)
                                for(oy in 0 until th) for(ox in 0 until tw) {
                                    val i=(oy+halo*4)*side+ox+halo*4
                                    fun channel(plane:Int)=(data.get(plane*side*side+i)*255).toInt().coerceIn(0,255)
                                    pixels[oy*tw+ox]= -0x1000000 or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                                }
                                output.setPixels(pixels,0,tw,x*4,y*4,tw,th)
                            }
                        }
                        progress(++done*100/total)
                    }
                    return if(scale==4)output else Bitmap.createScaledBitmap(output,image.width*2,image.height*2,true).also { output.recycle() }
                } catch(t:Throwable) { output.recycle(); throw t }
            }
        }
    }
}
