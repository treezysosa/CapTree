package studio.retro.ai

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import studio.retro.ai.data.*
import studio.retro.ai.media.*
import studio.retro.ai.work.CreationWorker
import java.io.File
import java.util.UUID

data class EditorState(
    val tool:String="enhance", val inputs:List<String> = emptyList(), val video:Boolean=false,
    val start:String="0", val end:String="3", val fps:Int=8, val speed:Float=1f, val width:Int=320,
    val loop:Boolean=true, val caption:String="", val sticker:String="", val format:String="GIF",
    val scale:Int=2, val intensity:Float=0.7f, val denoise:Boolean=true, val sharpen:Boolean=true,
    val prompt:String="", val negative:String="", val style:String="Cinematic", val aspect:String="1:1",
    val seed:String="", val duration:Int=3, val motion:Float=0.5f, val captions:String=""
) {
    fun recipe(prefs:Preferences):JSONObject = JSONObject().apply {
        put("tool",tool); put("inputs",JSONArray(inputs)); put("video",video)
        put("start",((start.toDoubleOrNull() ?: 0.0)*1000).toLong()); put("end",((end.toDoubleOrNull() ?: 3.0)*1000).toLong())
        put("fps",fps); put("speed",speed.toDouble()); put("width",width); put("loop",loop); put("caption",caption); put("sticker",sticker); put("format",format)
        put("scale",scale); put("intensity",intensity.toDouble()); put("denoise",denoise); put("sharpen",sharpen)
        put("prompt",prompt); put("negative",negative); put("style",style); put("aspect",aspect); put("seed",seed.toLongOrNull() ?: -1L); put("duration",duration); put("motion",motion.toDouble()); put("captions",captions)
        put("backend",prefs.backend); put("consent",prefs.remoteConsent); put("quality",prefs.quality)
    }
    companion object {
        fun restore(tool:String,json:String):EditorState {
            val r=JSONObject(json); val a=r.optJSONArray("inputs") ?: JSONArray()
            return EditorState(tool,List(a.length()){a.getString(it)},r.optBoolean("video"),"${r.optLong("start")/1000.0}","${r.optLong("end",3000)/1000.0}",r.optInt("fps",8),r.optDouble("speed",1.0).toFloat(),r.optInt("width",320),r.optBoolean("loop",true),r.optString("caption"),r.optString("sticker"),r.optString("format","GIF"),r.optInt("scale",2),r.optDouble("intensity",0.7).toFloat(),r.optBoolean("denoise",true),r.optBoolean("sharpen",true),r.optString("prompt"),r.optString("negative"),r.optString("style","Cinematic"),r.optString("aspect","1:1"),r.optLong("seed",-1).let { if(it<0)"" else "$it" },r.optInt("duration",3),r.optDouble("motion",0.5).toFloat(),r.optString("captions"))
        }
    }
}
class StudioViewModel(application:Application):AndroidViewModel(application) {
    private val app=application as StudioApplication
    private val dao=app.database.projects()
    val settings=app.settings
    val prefs=settings.flow.stateIn(viewModelScope,SharingStarted.Eagerly,Preferences())
    val projects=dao.observe().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val screen=MutableStateFlow("home")
    val editor=MutableStateFlow(EditorState())
    val message=MutableStateFlow("")
    val importing=MutableStateFlow(false)
    val active=MutableStateFlow<String?>(null)
    val work=WorkManager.getInstance(app)
    fun open(tool:String) { editor.value=EditorState(tool=tool,style=if(tool=="photo")"Photorealistic" else "Cinematic",aspect=if(tool=="clipper")"Original" else "1:1"); screen.value="editor" }
    fun back() { screen.value="home" }
    fun edit(change:(EditorState)->EditorState) { editor.value=change(editor.value) }
    fun launch(block:suspend()->Unit) { viewModelScope.launch { try { block() } catch(e:CancellationException) { throw e } catch(e:Exception) { message.value=e.message ?: "Operation failed." } } }
    fun import(uris:List<Uri>,video:Boolean) = launch {
        importing.value=true
        try {
            val files=withContext(Dispatchers.IO) {
                val dir=File(app.filesDir,"imports").apply { mkdirs() }; val copied=mutableListOf<File>()
                try {
                    uris.forEach { uri ->
                        val mime=app.contentResolver.getType(uri).orEmpty()
                        require(if(video)mime.startsWith("video/") else mime in setOf("image/jpeg","image/png","image/webp","image/gif")) { "Choose a supported image or video." }
                        val file=File(dir,"${UUID.randomUUID()}.${if(video)"mp4" else if(mime=="image/png")"png" else if(mime=="image/webp")"webp" else "jpg"}"); copied+=file
                        app.contentResolver.openInputStream(uri)!!.use { source -> file.outputStream().use { sink -> val bytes=ByteArray(65536); var total=0L; while(true) { currentCoroutineContext().ensureActive(); val n=source.read(bytes); if(n<0)break; total+=n; require(total<512L*1024*1024) { "Import limit is 512 MB per file." }; sink.write(bytes,0,n) } } }
                    }; copied
                } catch(t:Throwable) { copied.forEach { it.delete() }; throw t }
            }
            val duration=if(video)withContext(Dispatchers.IO) { VideoEngine.duration(files.first())/1000.0 } else 3.0
            edit { it.copy(inputs=files.map(File::getPath),video=video,end="${minOf(duration,if(it.tool=="gif")5.0 else duration)}") }
            message.value="${files.size} media file(s) imported into private storage."
        } finally { importing.value=false }
    }
    fun model(uri:Uri) = launch {
        importing.value=true
        try { withContext(Dispatchers.IO) {
            val dir=File(app.filesDir,"models").apply { mkdirs() }; val tmp=File(dir,"install.tmp")
            try { app.contentResolver.openInputStream(uri)!!.use { source -> tmp.outputStream().use { sink -> val b=ByteArray(65536); var count=0L; while(true) { val n=source.read(b); if(n<0)break; count+=n; require(count<=160L*1024*1024) { "Model exceeds 160 MB." }; sink.write(b,0,n) } } }
                ai.onnxruntime.OrtEnvironment.getEnvironment().createSession(tmp.path).use { require(it.inputNames.size==1) { "Expected one RGB input tensor." } }
                require(tmp.renameTo(File(dir,"realesrgan-x4.onnx"))) { "Cannot install model." }
            } finally { tmp.delete() }
        }; message.value="Real-ESRGAN model installed. Its tensor format is checked during inference." } finally { importing.value=false }
    }
    private suspend fun enqueue(project:Project) {
        val request=OneTimeWorkRequestBuilder<CreationWorker>().setInputData(workDataOf("project" to project.id)).addTag("creation").build()
        dao.save(project.copy(status="queued",workId=request.id.toString(),error=""))
        work.enqueueUniqueWork(project.id,ExistingWorkPolicy.KEEP,request)
        active.value=project.id; screen.value="processing"
    }
    fun create(draft:Boolean=false) = launch {
        val e=editor.value
        if(!draft) {
            if(e.tool in setOf("photo","video","aigif")) { require(e.prompt.isNotBlank()) { "Enter a prompt." }; require(prefs.value.backend.isNotBlank() && prefs.value.remoteConsent) { "Set up an HTTPS backend and allow remote processing in Settings." } }
            else require(e.inputs.isNotEmpty()) { "Import media first." }
            if(e.tool=="gif" && e.video && e.format=="GIF")EditRules.frameTimes(((e.start.toDoubleOrNull() ?: 0.0)*1000).toLong(),((e.end.toDoubleOrNull() ?: 0.0)*1000).toLong(),e.fps,e.speed)
        }
        val p=Project(UUID.randomUUID().toString(),"${e.tool.uppercase()} · ${java.text.SimpleDateFormat("MMM dd HH:mm",java.util.Locale.US).format(java.util.Date())}",e.tool,e.recipe(prefs.value).toString())
        if(draft) { dao.save(p); message.value="Saved in projects." } else enqueue(p)
    }
    fun cancel(project:Project) = launch { work.cancelUniqueWork(project.id); dao.get(project.id)?.let { dao.save(it.copy(status="cancelled")) } }
    fun resume(project:Project) { editor.value=EditorState.restore(project.tool,project.recipe); screen.value="editor" }
    fun retry(project:Project) = launch { enqueue(project.copy(id=UUID.randomUUID().toString(),created=System.currentTimeMillis(),output="")) }
    fun rename(project:Project,name:String) = launch { dao.save(project.copy(name=name.take(80).ifBlank { project.name })) }
    fun favorite(project:Project) = launch { dao.save(project.copy(favorite=!project.favorite)) }
    fun duplicate(project:Project) = launch {
        val id=UUID.randomUUID().toString()
        val path=if(project.output.isNotBlank())withContext(Dispatchers.IO) { File(project.output).copyTo(File(app.filesDir,"outputs/$id.${File(project.output).extension}")).path } else ""
        dao.save(project.copy(id=id,name="${project.name} COPY",created=System.currentTimeMillis(),output=path,workId="",status=if(path.isBlank())"draft" else "completed"))
    }
    fun delete(project:Project) = launch { work.cancelUniqueWork(project.id); dao.delete(project.id); withContext(Dispatchers.IO) { if(project.output.isNotBlank())File(project.output).delete() }; message.value="Project deleted. Use storage cleanup to remove unused imports." }
    fun share(project:Project) {
        runCatching { val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",File(project.output)); app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(project.mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share creation").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { message.value=it.message.orEmpty() }
    }
    fun saveGallery(project:Project) = launch {
        require(Build.VERSION.SDK_INT>=29) { "On Android 8–9, use EXPORT FILE to choose a folder." }
        withContext(Dispatchers.IO) {
            val collection=if(project.mime.startsWith("video"))MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values=android.content.ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,"RETRO_${project.id}.${File(project.output).extension}"); put(MediaStore.MediaColumns.MIME_TYPE,project.mime); put(MediaStore.MediaColumns.RELATIVE_PATH,if(project.mime.startsWith("video"))"Movies/RetroAiStudio" else "Pictures/RetroAiStudio"); put(MediaStore.MediaColumns.IS_PENDING,1) }
            val uri=app.contentResolver.insert(collection,values) ?: error("Cannot create gallery entry.")
            try { app.contentResolver.openOutputStream(uri)!!.use { sink -> File(project.output).inputStream().use { it.copyTo(sink) } }; values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING,0); app.contentResolver.update(uri,values,null,null) } catch(e:Exception) { app.contentResolver.delete(uri,null,null); throw e }
        }; message.value="Saved to gallery."
    }
    fun export(project:Project,uri:Uri) = launch { withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri)!!.use { sink -> File(project.output).inputStream().use { it.copyTo(sink) } } }; message.value="Export complete." }
    fun analyze(silence:Boolean) = launch {
        importing.value=true
        try { val e=editor.value; require(e.inputs.isNotEmpty() && e.video) { "Import a video first." }; val points=if(silence)VideoEngine.silence(File(e.inputs.first())) else VideoEngine.scenes(File(e.inputs.first())); message.value=(if(silence)"Quiet audio at " else "Scene cuts at ")+if(points.isEmpty())"none detected" else points.take(25).joinToString { "${it/1000.0}s" } } finally { importing.value=false }
    }
    fun split() = launch {
        val e=editor.value; require(e.tool=="clipper" && e.inputs.isNotEmpty()) { "Import a clip first." }
        val start=(e.start.toDoubleOrNull() ?: 0.0); val end=e.end.toDoubleOrNull() ?: error("Enter end time."); require(end>start) { "End must be after start." }; val middle=(start+end)/2
        for((a,b) in listOf(start to middle,middle to end)) { val piece=e.copy(start="$a",end="$b"); val id=UUID.randomUUID().toString(); dao.save(Project(id,"SPLIT ${a}s – ${b}s","clipper",piece.recipe(prefs.value).toString())) }; message.value="Split into two editable drafts in Projects. Resume either to export."
    }
    fun clean() = launch {
        require(projects.value.none { it.status in setOf("queued","processing") }) { "Wait for processing to finish before cleaning storage." }
        withContext(Dispatchers.IO) {
            app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            val used=projects.value.flatMap { p -> runCatching { val a=JSONObject(p.recipe).optJSONArray("inputs"); if(a==null)emptyList() else List(a.length()){a.getString(it)} }.getOrDefault(emptyList()) }.toSet()+editor.value.inputs
            File(app.filesDir,"imports").listFiles()?.filter { it.path !in used }?.forEach { it.delete() }
        }; message.value="Cache and unused imports cleared."
    }
    fun testBackend() = launch { val health=withContext(Dispatchers.IO) { BackendClient(prefs.value.backend,BackendSecrets(app).read()).health() }; message.value="Connected · ${health.optJSONArray("capabilities")}" }
}
