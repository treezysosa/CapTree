package studio.retro.ai.work

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import studio.retro.ai.StudioApplication
import studio.retro.ai.data.*
import studio.retro.ai.media.*
import java.io.File

class CreationWorker(context:Context, params:WorkerParameters):CoroutineWorker(context,params) {
    private val app=context.applicationContext as StudioApplication
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        val dao=app.database.projects(); val id=inputData.getString("project") ?: return@withContext Result.failure()
        val project=dao.get(id) ?: return@withContext Result.failure()
        val recipe=JSONObject(project.recipe); val tool=project.tool
        val outputDir=File(applicationContext.filesDir,"outputs").apply { mkdirs() }
        val mp4=recipe.optString("format")=="MP4" || tool=="video" || tool=="clipper" || (tool=="enhance" && recipe.optBoolean("video"))
        val jpeg=tool in setOf("enhance","upscale") && !mp4 && recipe.optInt("quality",92)<100
        val suffix=if(mp4)"mp4" else if(tool in setOf("gif","aigif"))"gif" else if(jpeg)"jpg" else "png"
        val output=File(outputDir,"$id.$suffix")
        val mime=if(mp4)"video/mp4" else if(suffix=="gif")"image/gif" else if(jpeg)"image/jpeg" else "image/png"
        suspend fun progress(value:Int?,message:String) { setProgress(workDataOf("percent" to (value ?: -1),"message" to message)) }
        try {
            if(dao.status(id,"processing")==0)return@withContext Result.failure()
            output.delete()
            progress(null,"LOADING CREATIVE POWER...")
            val inputs=recipe.optJSONArray("inputs")?.let { a -> List(a.length()) { File(a.getString(it)).canonicalFile.also { file -> require(file.path.startsWith(File(applicationContext.filesDir,"imports").canonicalPath+File.separator)) { "Invalid input location." } } } }.orEmpty()
            when(tool) {
                "enhance","upscale" -> {
                    require(inputs.isNotEmpty()) { "Import media first." }
                    if(recipe.optBoolean("video")) {
                        val duration=VideoEngine.duration(inputs.first())
                        VideoEngine.export(applicationContext,listOf(Segment(inputs.first(),0,duration)),output,"Original",recipe.optDouble("intensity",0.7).toFloat(),emptyList()) { value -> setProgressAsync(workDataOf("percent" to (value ?: -1),"message" to "RENDERING VIDEO...")) }
                    } else {
                        val source=PhotoEngine.read(inputs.first(),if(tool=="upscale")1024 else 2048)
                        try {
                            val enhanced=PhotoEngine.enhance(source,recipe.optDouble("intensity",0.7).toFloat(),recipe.optBoolean("denoise"),recipe.optBoolean("sharpen",true)) { progress(if(tool=="upscale")it/5 else it,"ENHANCING PIXELS...") }
                            val result=try { if(tool=="upscale")UpscaleEngine.upscale(applicationContext,enhanced,recipe.optInt("scale",2)) { progress(20+it*4/5,"AI SUPER RESOLUTION...") } else enhanced } catch(t:Throwable) { enhanced.recycle(); throw t }
                            try { output.outputStream().use { require(result.compress(if(jpeg)android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG,recipe.optInt("quality",92),it)) } } finally { if(result!==enhanced)result.recycle(); enhanced.recycle() }
                        } finally { source.recycle() }
                    }
                }
                "clipper" -> {
                    val segments=inputs.mapIndexed { i,file -> val duration=VideoEngine.duration(file); Segment(file,if(i==0)recipe.optLong("start",0) else 0,if(i==0)recipe.optLong("end",duration).coerceAtMost(duration) else duration) }
                    val captions=recipe.optString("captions").lines().mapNotNull { line -> val parts=line.split('|',limit=2); if(parts.size==2)parts[0].trim().toLongOrNull()?.let { it to parts[1].trim() } else null }.sortedBy { it.first }
                    VideoEngine.export(applicationContext,segments,output,recipe.optString("aspect","Original"),0f,captions) { value -> setProgressAsync(workDataOf("percent" to (value ?: -1),"message" to "RENDERING VIDEO...")) }
                }
                "gif" -> {
                    if(mp4) {
                        if(recipe.optBoolean("video")) { val duration=VideoEngine.duration(inputs.first()); VideoEngine.export(applicationContext,listOf(Segment(inputs.first(),recipe.optLong("start",0),recipe.optLong("end",duration))),output,"Original",0f,emptyList()) { value -> setProgressAsync(workDataOf("percent" to (value ?: -1),"message" to "RENDERING VIDEO...")) } }
                        else VideoEngine.imagesToMp4(applicationContext,inputs,output,recipe.optInt("fps",8)) { value -> setProgressAsync(workDataOf("percent" to (value ?: -1),"message" to "RENDERING VIDEO...")) }
                    } else GifEngine.create(inputs,recipe.optBoolean("video"),output,recipe.optLong("start"),recipe.optLong("end",3000),recipe.optInt("fps",8),recipe.optDouble("speed",1.0).toFloat(),recipe.optInt("width",320),recipe.optBoolean("loop",true),recipe.optString("caption"),recipe.optString("sticker")) { progress(it,"ENCODING ANIMATION...") }
                }
                "photo","video","aigif" -> {
                    val prefs=app.settings.flow.first()
                    require(prefs.remoteConsent && recipe.optBoolean("consent") && prefs.backend==recipe.optString("backend")) { "Confirm remote processing for this backend in Settings." }
                    val client=BackendClient(prefs.backend,BackendSecrets(applicationContext).read())
                    val seed=client.generate(recipe,inputs.firstOrNull(),output) { value,message -> progress(value,message) }
                    if(seed>=0)recipe.put("seed",seed)
                }
                else -> error("Unknown tool.")
            }
            require(output.isFile && output.length()>0) { "The processor produced no output." }
            if(dao.complete(id,recipe.toString(),output.path,mime)==0) { output.delete(); return@withContext Result.failure() }
            Result.success(workDataOf("path" to output.path))
        } catch(e:CancellationException) {
            output.delete(); withContext(NonCancellable) { dao.status(id,"cancelled","Cancelled. Your imported files are kept for resuming.") }; throw e
        } catch(e:Exception) {
            output.delete(); dao.status(id,"failed",e.message?.take(500) ?: "Processing failed."); Result.failure()
        } catch(e:OutOfMemoryError) {
            output.delete(); dao.status(id,"failed","Not enough memory. Use smaller input media."); Result.failure()
        } catch(e:LinkageError) {
            output.delete(); dao.status(id,"failed","Unsupported device: the required native AI or media library could not load for this CPU/Android version."); Result.failure()
        }
    }
}
