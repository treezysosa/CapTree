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
        val mp4=tool!="transcribe" && (recipe.optString("format")=="MP4" || tool=="video" || tool=="clipper" || (tool=="enhance" && recipe.optBoolean("video")))
        val jpeg=tool in setOf("enhance","upscale") && !mp4 && recipe.optInt("quality",92)<100
        val suffix=if(tool=="transcribe")"srt" else if(mp4)"mp4" else if(tool in setOf("gif","aigif"))"gif" else if(jpeg)"jpg" else "png"
        val output=File(outputDir,"$id.$suffix")
        val mime=if(tool=="transcribe")"application/x-subrip" else if(mp4)"video/mp4" else if(suffix=="gif")"image/gif" else if(jpeg)"image/jpeg" else "image/png"
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
                        val intensity=recipe.optDouble("intensity",0.7).toFloat()
                        val profile=if(recipe.optBoolean("stabilize") && intensity>0)Stabilizer.analyze(inputs.first(),intensity) { progress(it,"MEASURING CAMERA MOTION...") } else null
                        progress(null,"RENDERING VIDEO...")
                        VideoEngine.export(applicationContext,listOf(Segment(inputs.first(),0,duration)),output,"Original",intensity,emptyList(),recipe.optBoolean("denoise"),recipe.optBoolean("sharpen"),stabilization=profile) { value -> setProgressAsync(workDataOf("percent" to (value ?: -1),"message" to "RENDERING VIDEO...")) }
                    } else {
                        val faceClient=if(recipe.optBoolean("face_restore")) {
                            val prefs=app.settings.flow.first()
                            require(recipe.optBoolean("face_upload_confirmed") && prefs.remoteConsent && recipe.optBoolean("consent") && prefs.backend==recipe.optString("backend")) { "Confirm processed-photo upload before face restoration." }
                            BackendClient(prefs.backend,BackendSecrets(applicationContext).read()).also { it.requireCapability("face-restoration") }
                        } else null
                        var expectedWidth=0; var expectedHeight=0
                        val source=PhotoEngine.read(inputs.first(),if(tool=="upscale")1024 else 2048)
                        try {
                            val enhanced=PhotoEngine.enhance(source,recipe.optDouble("intensity",0.7).toFloat(),recipe.optBoolean("denoise"),recipe.optBoolean("sharpen",true)) { progress(if(tool=="upscale")it/5 else it,"ENHANCING PIXELS...") }
                            val result=try { if(tool=="upscale")UpscaleEngine.upscale(applicationContext,enhanced,recipe.optInt("scale",2)) { progress(20+it*4/5,"AI SUPER RESOLUTION...") } else enhanced } catch(t:Throwable) { enhanced.recycle(); throw t }
                            expectedWidth=result.width; expectedHeight=result.height
                            try { output.outputStream().use { require(result.compress(if(jpeg && faceClient==null)android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG,recipe.optInt("quality",92),it)) } } finally { if(result!==enhanced)result.recycle(); enhanced.recycle() }
                        } finally { source.recycle() }
                        if(faceClient!=null) {
                            // Local bitmaps are recycled before the remote result is decoded.
                            val restored=File(applicationContext.cacheDir,"restored-face-$id.png")
                            try {
                                faceClient.restoreFace(output,recipe.optDouble("face_strength",0.7).toFloat(),restored) { value,message -> progress(value,message) }
                                val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                                android.graphics.BitmapFactory.decodeFile(restored.path,bounds)
                                require(bounds.outMimeType=="image/png" && bounds.outWidth==expectedWidth && bounds.outHeight==expectedHeight) { "Face server returned invalid image dimensions." }
                                if(jpeg) {
                                    val bitmap=PhotoEngine.read(restored,maxOf(expectedWidth,expectedHeight))
                                    try { output.outputStream().use { require(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,recipe.optInt("quality",92),it)) } } finally { bitmap.recycle() }
                                } else restored.copyTo(output,overwrite=true)
                                recipe.put("face_restoration_applied",true)
                            } finally { restored.delete() }
                        }
                    }
                }
                "clipper" -> {
                    val timeline=recipe.optJSONArray("clips")
                    val segments=if(timeline!=null && timeline.length()>0)List(timeline.length()) { index ->
                        val c=timeline.getJSONObject(index); val file=File(c.getString("path")).canonicalFile
                        require(file.path.startsWith(File(applicationContext.filesDir,"imports").canonicalPath+File.separator)) { "Invalid clip location." }
                        Segment(file,c.getLong("start"),c.getLong("end"))
                    } else inputs.mapIndexed { i,file -> val duration=VideoEngine.duration(file); Segment(file,if(i==0)recipe.optLong("start",0) else 0,if(i==0)recipe.optLong("end",duration).coerceAtMost(duration) else duration) }
                    val captions=CaptionTrack.parse(recipe.optString("captions"),segments.sumOf { it.end-it.start })
                    VideoEngine.export(applicationContext,segments,output,recipe.optString("aspect","Original"),0f,captions) { value -> setProgressAsync(workDataOf("percent" to (value ?: -1),"message" to "RENDERING VIDEO...")) }
                }
                "gif" -> {
                    if(mp4) {
                        val animation=AnimationOptions(recipe.optInt("fps",8),recipe.optDouble("speed",1.0).toFloat(),recipe.optInt("width",320),recipe.optString("caption"),recipe.optString("sticker"))
                        if(recipe.optBoolean("video")) { val duration=VideoEngine.duration(inputs.first()); VideoEngine.export(applicationContext,listOf(Segment(inputs.first(),recipe.optLong("start",0),recipe.optLong("end",duration))),output,"Original",0f,emptyList(),animation=animation) { value -> setProgressAsync(workDataOf("percent" to (value ?: -1),"message" to "RENDERING VIDEO...")) } }
                        else VideoEngine.imagesToMp4(applicationContext,inputs,output,recipe.optInt("fps",8),animation=animation,preparing={value -> setProgressAsync(workDataOf("percent" to value,"message" to "PREPARING ANIMATION FRAMES...")) }) { value -> setProgressAsync(workDataOf("percent" to (value ?: -1),"message" to "RENDERING VIDEO...")) }
                    } else GifEngine.create(inputs,recipe.optBoolean("video"),output,recipe.optLong("start"),recipe.optLong("end",3000),recipe.optInt("fps",8),recipe.optDouble("speed",1.0).toFloat(),recipe.optInt("width",320),recipe.optBoolean("loop",true),recipe.optString("caption"),recipe.optString("sticker"),recipe.optString("gif_palette","Classic 256"),recipe.optBoolean("gif_dither")) { progress(it,"ENCODING ANIMATION...") }
                }
                "transcribe" -> {
                    val prefs=app.settings.flow.first()
                    require(prefs.remoteConsent && recipe.optBoolean("consent") && prefs.backend==recipe.optString("backend")) { "Allow remote processing for this backend in Settings first." }
                    val client=BackendClient(prefs.backend,BackendSecrets(applicationContext).read())
                    client.requireCapability("transcription")
                    val clips=recipe.getJSONArray("clips"); val selected=clips.getJSONObject(recipe.optInt("transcribe_index",0))
                    val source=File(selected.getString("path")).canonicalFile
                    require(source in inputs) { "Invalid selected audio source." }
                    val start=selected.getLong("start"); val end=selected.getLong("end")
                    val audio=File(applicationContext.cacheDir,"selected-audio-$id.wav")
                    try {
                        AudioEngine.extractWav(source,start,end,audio) { progress(it,"PREPARING SELECTED AUDIO...") }
                        client.transcribe(audio,recipe.optString("transcription_language","auto"),output) { percent,message -> progress(percent,message) }
                        val offset=recipe.optLong("caption_offset",0)
                        val cues=CaptionTrack.parse(output.readText(),end-start).filter { it.startMs<end-start }.map { it.copy(startMs=it.startMs+offset,endMs=minOf(it.endMs,end-start)+offset) }
                        require(cues.isNotEmpty()) { "No subtitle cues were returned." }
                        output.writeText(CaptionTrack.srt(cues))
                    } finally { audio.delete() }
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
