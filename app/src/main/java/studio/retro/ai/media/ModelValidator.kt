package studio.retro.ai.media

import ai.onnxruntime.*
import android.os.SystemClock
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

data class ModelReport(val sha256:String,val elapsedMs:Long,val inputShape:String,val outputShape:String) {
    fun description()="Compatible x4 RGB ONNX · ${elapsedMs} ms test\nInput $inputShape → output $outputShape\nSHA-256 $sha256"
}

object ModelValidator {
    fun checkContract(session:OrtSession) {
        require(session.inputNames.size==1 && session.outputNames.size==1) { "Expected one RGB input and one RGB output." }
        val input=session.inputInfo.values.first().info as? TensorInfo ?: error("Model input must be a tensor.")
        require(input.type==OnnxJavaType.FLOAT && input.shape.size==4 && input.shape[0] in listOf(-1L,1L) && input.shape[1]==3L) { "Expected float32 RGB [1,3,H,W] input." }
        require((input.shape[2]<=0 || input.shape[2]==96L) && (input.shape[3]<=0 || input.shape[3]==96L)) { "Use dynamic spatial dimensions or a fixed 96×96 input." }
    }

    /** Executes a real 96×96 inference probe, not just graph loading or shape inspection. */
    fun probe(file:File):ModelReport {
        require(file.isFile && file.length() in 1..160L*1024*1024) { "Select a model file smaller than 160 MB." }
        val started=SystemClock.elapsedRealtime()
        val env=OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            env.createSession(file.path,options).use { session ->
                checkContract(session)
                val input=FloatArray(3*96*96) { (it%96)/95f }
                OnnxTensor.createTensor(env,FloatBuffer.wrap(input),longArrayOf(1,3,96,96)).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                        val output=result[0] as? OnnxTensor ?: error("Model output is not a tensor.")
                        require(output.info.type==OnnxJavaType.FLOAT && output.info.shape.contentEquals(longArrayOf(1,3,384,384))) { "Expected float32 x4 [1,3,384,384] output." }
                        val values=output.floatBuffer
                        require(values.capacity()==3*384*384) { "Incorrect output length." }
                        for(i in 0 until values.capacity())require(values.get(i).isFinite()) { "Model produced non-finite pixel values." }
                    }
                }
            }
        }
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source -> val block=ByteArray(65536); while(true) { val n=source.read(block); if(n<0)break; digest.update(block,0,n) } }
        return ModelReport(digest.digest().joinToString("") { "%02x".format(it) },SystemClock.elapsedRealtime()-started,"[1,3,96,96]","[1,3,384,384]")
    }
}
