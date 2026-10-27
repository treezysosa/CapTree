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
import kotlin.math.roundToLong

data class EditorState(
    val tool:String="enhance", val inputs:List<String> = emptyList(), val video:Boolean=false,
    val start:String="0", val end:String="3", val fps:Int=8, val speed:Float=1f, val width:Int=320,
    val loop:Boolean=true, val caption:String="", val sticker:String="", val format:String="GIF",
    val scale:Int=2, val intensity:Float=0.7f, val denoise:Boolean=true, val sharpen:Boolean=true,
    val prompt:String="", val negative:String="", val style:String="Cinematic", val aspect:String="1:1",
    val seed:String="", val duration:Int=3, val motion:Float=0.5f, val captions:String="",
    val clips:List<ClipEdit> = emptyList(), val selectedClip:Int=0, val playheadMs:Long=0,
    val suggestions:List<Long> = emptyList(), val previewRequestMs:Long?=null,
    val transcriptionLanguage:String="auto", val stabilize:Boolean=false,
    val suggestedClips:List<SuggestedClip> = emptyList(),
    val gifPalette:String="Adaptive 256", val gifDither:Boolean=false
) {
    fun recipe(prefs:Preferences):JSONObject = JSONObject().apply {
        put("tool",tool); put("inputs",JSONArray(inputs)); put("video",video)
        put("start",((start.toDoubleOrNull() ?: 0.0)*1000).toLong()); put("end",((end.toDoubleOrNull() ?: 3.0)*1000).toLong())
        put("fps",fps); put("speed",speed.toDouble()); put("width",width); put("loop",loop); put("caption",caption); put("sticker",sticker); put("format",format)
        put("scale",scale); put("intensity",intensity.toDouble()); put("denoise",denoise); put("sharpen",sharpen)
        put("prompt",prompt); put("negative",negative); put("style",style); put("aspect",aspect); put("seed",seed.toLongOrNull() ?: -1L); put("duration",duration); put("motion",motion.toDouble()); put("captions",captions)
        put("backend",prefs.backend); put("consent",prefs.remoteConsent); put("quality",prefs.quality)
        put("clips",JSONArray().apply { clips.forEach { clip -> put(JSONObject().apply { put("path",clip.path); put("start",clip.startMs); put("end",clip.endMs); put("duration",clip.sourceDurationMs); put("id",clip.id); put("origin",clip.originId) }) } })
        put("transcription_language",transcriptionLanguage)
        put("selected_clip",selectedClip)
        put("stabilize",stabilize)
        put("gif_palette",gifPalette); put("gif_dither",gifDither)
    }
    companion object {
        fun restore(tool:String,json:String):EditorState {
            val r=JSONObject(json); val a=r.optJSONArray("inputs") ?: JSONArray()
            val timeline=r.optJSONArray("clips")
            val clips=if(timeline==null)emptyList() else List(timeline.length()) { index -> val c=timeline.getJSONObject(index); val id=c.optString("id").ifBlank { UUID.randomUUID().toString() }; ClipEdit(c.getString("path"),c.getLong("start"),c.getLong("end"),c.getLong("duration"),id,c.optString("origin",id).ifBlank { id }) }
            val selected=r.optInt("selected_clip",0).coerceIn(0,clips.lastIndex.coerceAtLeast(0))
            val chosen=clips.getOrNull(selected)
            return EditorState(tool,List(a.length()){a.getString(it)},r.optBoolean("video"),"${(chosen?.startMs ?: r.optLong("start"))/1000.0}","${(chosen?.endMs ?: r.optLong("end",3000))/1000.0}",r.optInt("fps",8),r.optDouble("speed",1.0).toFloat(),r.optInt("width",320),r.optBoolean("loop",true),r.optString("caption"),r.optString("sticker"),r.optString("format","GIF"),r.optInt("scale",2),r.optDouble("intensity",0.7).toFloat(),r.optBoolean("denoise",true),r.optBoolean("sharpen",true),r.optString("prompt"),r.optString("negative"),r.optString("style","Cinematic"),r.optString("aspect","1:1"),r.optLong("seed",-1).let { if(it<0)"" else "$it" },r.optInt("duration",3),r.optDouble("motion",0.5).toFloat(),r.optString("captions"),clips=clips,selectedClip=selected,playheadMs=chosen?.startMs ?: 0,transcriptionLanguage=r.optString("transcription_language","auto"),stabilize=r.optBoolean("stabilize"),gifPalette=r.optString("gif_palette","Classic 256"),gifDither=r.optBoolean("gif_dither"))
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
    val busyMessage=MutableStateFlow("READING YOUR MEDIA...")
    val modelReport=MutableStateFlow("")
    val backendReport=MutableStateFlow<JSONObject?>(null)
    val active=MutableStateFlow<String?>(null)
    val work=WorkManager.getInstance(app)
    private val history=EditHistory<EditorState>()
    val canUndo=MutableStateFlow(false); val canRedo=MutableStateFlow(false)
    private fun updateHistory() { canUndo.value=history.canUndo; canRedo.value=history.canRedo }
    private fun clearHistory() { history.clear(); updateHistory() }
    private fun snapshot(e:EditorState)=e.copy(playheadMs=0,previewRequestMs=null,suggestions=emptyList(),suggestedClips=emptyList())
    fun open(tool:String) { clearHistory(); editor.value=EditorState(tool=tool,style=if(tool=="photo")"Photorealistic" else "Cinematic",aspect=if(tool=="clipper")"Original" else "1:1"); screen.value="editor" }
    fun back() { screen.value="home" }
    private fun trim(e:EditorState):ClipEdit? {
        val clip=e.clips.getOrNull(e.selectedClip) ?: return null
        val start=e.start.toDoubleOrNull()?.takeIf { it.isFinite() && it>=0 && it<=clip.sourceDurationMs/1000.0 } ?: return null
        val end=e.end.toDoubleOrNull()?.takeIf { it.isFinite() && it>=0 && it<=clip.sourceDurationMs/1000.0 } ?: return null
        return runCatching { clip.copy(startMs=(start*1000).roundToLong(),endMs=(end*1000).roundToLong()).validated() }.getOrNull()
    }
    fun edit(group:String?=null,change:(EditorState)->EditorState) {
        try {
            val old=editor.value; var next=change(old)
            if(next.tool=="clipper" && next.clips==old.clips && next.selectedClip==old.selectedClip && (next.start!=old.start || next.end!=old.end)) {
                trim(next)?.let { edited -> next=next.copy(clips=next.clips.mapIndexed { i,c -> if(i==next.selectedClip)edited else c }) }
            }
            if(next.tool=="clipper" && next.clips!=old.clips) {
                if(old.captions.isNotBlank() && next.captions==old.captions && next.clips.take(old.clips.size)!=old.clips) {
                    val cues=CaptionTrack.parse(old.captions,old.clips.sumOf { it.durationMs }.coerceAtLeast(1))
                    next=next.copy(captions=CaptionTrack.srt(CaptionTimeline.retime(cues,old.clips,next.clips)))
                }
                next=next.copy(suggestions=emptyList(),suggestedClips=emptyList())
            }
            history.record(snapshot(old),snapshot(next),group,android.os.SystemClock.uptimeMillis())
            editor.value=next; updateHistory()
        } catch(e:Exception) { message.value=e.message ?: "This edit could not be applied." }
    }
    private fun restoreHistory(state:EditorState) {
        val index=state.selectedClip.coerceIn(0,state.clips.lastIndex.coerceAtLeast(0)); val clip=state.clips.getOrNull(index)
        editor.value=state.copy(selectedClip=index,playheadMs=clip?.startMs ?: 0,previewRequestMs=clip?.startMs,suggestions=emptyList(),suggestedClips=emptyList()); updateHistory()
    }
    fun undo() { if(!importing.value)history.undo(snapshot(editor.value))?.let(::restoreHistory) }
    fun redo() { if(!importing.value)history.redo(snapshot(editor.value))?.let(::restoreHistory) }
    fun launch(block:suspend()->Unit) { viewModelScope.launch { try { block() } catch(e:CancellationException) { throw e } catch(e:Exception) { message.value=e.message ?: "Operation failed." } catch(e:LinkageError) { message.value="Unsupported device: this native library cannot load on your CPU/Android version." } catch(e:OutOfMemoryError) { message.value="Not enough memory for this operation. Try a smaller input or a device with more RAM." } } }
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
            val durations=if(video)withContext(Dispatchers.IO) { files.map { VideoEngine.duration(it) } } else emptyList()
            val duration=durations.firstOrNull()?.div(1000.0) ?: 3.0
            val clips=if(video && editor.value.tool=="clipper")files.mapIndexed { i,f -> ClipEdit(f.path,0,durations[i],durations[i]) } else emptyList()
            val existing=editor.value
            val next=if(existing.tool=="clipper" && existing.clips.isNotEmpty()) {
                require(existing.clips.size+clips.size<=40) { "A timeline supports at most 40 blocks." }
                existing.copy(inputs=(existing.inputs+files.map(File::getPath)).distinct(),clips=existing.clips+clips)
            } else existing.copy(inputs=files.map(File::getPath),video=video,start="0",end="${minOf(duration,if(existing.tool=="gif")5.0 else duration)}",clips=clips,selectedClip=0,playheadMs=0,suggestions=emptyList())
            edit { next }; message.value="${files.size} media file(s) imported into private storage."
        } finally { importing.value=false }
    }
    fun model(uri:Uri) = launch {
        require(!importing.value && projects.value.none { it.status in setOf("queued","processing") }) { "Wait for current processing to finish before installing a model." }
        busyMessage.value="TESTING ONNX MODEL ON THIS DEVICE..."
        importing.value=true
        try { withContext(Dispatchers.IO) {
            val dir=File(app.filesDir,"models").apply { mkdirs() }; val tmp=File(dir,"install.tmp")
            try { app.contentResolver.openInputStream(uri)!!.use { source -> tmp.outputStream().use { sink -> val b=ByteArray(65536); var count=0L; while(true) { val n=source.read(b); if(n<0)break; count+=n; require(count<=160L*1024*1024) { "Model exceeds 160 MB." }; sink.write(b,0,n) } } }
                val report=ModelValidator.probe(tmp)
                require(tmp.renameTo(File(dir,"realesrgan-x4.onnx"))) { "Cannot install model." }
                File(dir,"validation.txt").writeText(report.description()); modelReport.value=report.description()
            } finally { tmp.delete() }
        }; message.value="Model installed after successful on-device x4 inference validation." } finally { importing.value=false; busyMessage.value="READING YOUR MEDIA..." }
    }
    private suspend fun enqueue(project:Project) {
        val request=OneTimeWorkRequestBuilder<CreationWorker>().setInputData(workDataOf("project" to project.id)).addTag("creation").build()
        dao.save(project.copy(status="queued",workId=request.id.toString(),error=""))
        work.enqueueUniqueWork(project.id,ExistingWorkPolicy.KEEP,request)
        active.value=project.id; screen.value="processing"
    }
    fun create(draft:Boolean=false) = launch {
        require(!importing.value) { "Wait for the current import or model test to finish." }
        val e=editor.value
        if(!draft) {
            if(e.tool in setOf("photo","video","aigif")) { require(e.prompt.isNotBlank()) { "Enter a prompt." }; require(prefs.value.backend.isNotBlank() && prefs.value.remoteConsent) { "Set up an HTTPS backend and allow remote processing in Settings." } }
            else require(e.inputs.isNotEmpty()) { "Import media first." }
            if(e.tool=="clipper") {
                require(trim(e)==e.clips.getOrNull(e.selectedClip) && trim(e)!=null) { "Enter valid trim times and keep at least 40 milliseconds." }
                e.clips.forEach { it.validated() }
                CaptionTrack.parse(e.captions,e.clips.sumOf { it.durationMs }.coerceAtLeast(1))
            }
            if(e.tool=="gif" && e.video && e.format=="GIF")EditRules.frameTimes(((e.start.toDoubleOrNull() ?: 0.0)*1000).toLong(),((e.end.toDoubleOrNull() ?: 0.0)*1000).toLong(),e.fps,e.speed)
        }
        val p=Project(UUID.randomUUID().toString(),"${e.tool.uppercase()} · ${java.text.SimpleDateFormat("MMM dd HH:mm",java.util.Locale.US).format(java.util.Date())}",e.tool,e.recipe(prefs.value).toString())
        if(draft) { dao.save(p); message.value="Saved in projects." } else enqueue(p)
    }
    fun cancel(project:Project) = launch { work.cancelUniqueWork(project.id); dao.status(project.id,"cancelled") }
    fun resume(project:Project) = launch {
        if(project.tool=="transcribe" && project.status=="completed") { applyTranscript(project); return@launch }
        var state=EditorState.restore(if(project.tool=="transcribe")"clipper" else project.tool,project.recipe)
        if(state.tool=="clipper" && state.clips.isEmpty() && state.inputs.isNotEmpty()) {
            val restored=state
            val clips=withContext(Dispatchers.IO) { restored.inputs.mapIndexed { index,path -> val d=VideoEngine.duration(File(path)); ClipEdit(path,if(index==0)((restored.start.toDoubleOrNull() ?: 0.0)*1000).toLong() else 0,if(index==0)((restored.end.toDoubleOrNull() ?: d/1000.0)*1000).toLong() else d,d) } }
            state=state.copy(clips=clips,playheadMs=clips.first().startMs)
        }
        clearHistory(); editor.value=state; screen.value="editor"
    }
    fun retry(project:Project) = launch { enqueue(project.copy(id=UUID.randomUUID().toString(),created=System.currentTimeMillis(),output="")) }
    fun rename(project:Project,name:String) = launch { dao.rename(project.id,name.take(80).ifBlank { project.name }) }
    fun favorite(project:Project) = launch { dao.favorite(project.id,!project.favorite) }
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
        if(Build.VERSION.SDK_INT<29)error("On Android 8–9, use EXPORT FILE to choose a folder.")
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
        try { val e=editor.value; require(e.inputs.isNotEmpty() && e.video) { "Import a video first." }; val path=e.clips.getOrNull(e.selectedClip)?.path ?: e.inputs.first(); val points=if(silence)VideoEngine.silence(File(path)) else VideoEngine.scenes(File(path)); edit { it.copy(suggestions=points) }; message.value=if(points.isEmpty())"No ${if(silence)"quiet audio" else "scene cuts"} detected." else "${points.size} measured suggestions are ready. Tap a timestamp to seek, then split or trim." } finally { importing.value=false }
    }
    fun split() = edit { e ->
        require(e.tool=="clipper" && e.clips.isNotEmpty()) { "Import a clip first." }
        require(e.clips.size<40) { "A timeline supports at most 40 blocks." }
        val clips=Timeline.split(e.clips,e.selectedClip,e.playheadMs)
        e.copy(clips=clips,end="${clips[e.selectedClip].endMs/1000.0}")
    }
    fun selectClip(index:Int) {
        val e=editor.value; val clip=e.clips.getOrNull(index) ?: return
        history.endGroup()
        editor.value=e.copy(selectedClip=index,start="${clip.startMs/1000.0}",end="${clip.endMs/1000.0}",playheadMs=clip.startMs,previewRequestMs=clip.startMs,suggestions=emptyList(),suggestedClips=emptyList())
    }
    fun moveClip(direction:Int) {
        val e=editor.value; if(e.clips.isEmpty())return
        val target=(e.selectedClip+direction).coerceIn(e.clips.indices)
        edit { it.copy(clips=Timeline.move(it.clips,it.selectedClip,direction),selectedClip=target) }
    }
    fun removeClip() {
        val e=editor.value; if(e.clips.isEmpty())return
        val clips=e.clips.filterIndexed { i,_ -> i!=e.selectedClip }
        val first=clips.firstOrNull()
        edit { it.copy(clips=clips,inputs=clips.map { clip -> clip.path }.distinct(),selectedClip=0,start="${(first?.startMs ?: 0)/1000.0}",end="${(first?.endMs ?: 0)/1000.0}",playheadMs=first?.startMs ?: 0,previewRequestMs=first?.startMs) }
    }
    fun seek(ms:Long) {
        val e=editor.value; val clip=e.clips.getOrNull(e.selectedClip) ?: return
        history.endGroup()
        val position=ms.coerceIn(clip.startMs,clip.endMs.coerceAtLeast(clip.startMs))
        editor.value=e.copy(playheadMs=position,previewRequestMs=position)
    }
    fun previewPosition(ms:Long) { val e=editor.value; if(e.tool=="clipper")editor.value=e.copy(playheadMs=ms) }
    fun importCaptions(uri:Uri) = launch {
        val text=withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)!!.bufferedReader().use { val chars=CharArray(1_000_001); var count=0; while(count<chars.size) { val n=it.read(chars,count,chars.size-count); if(n<0)break; count+=n }; require(count<=1_000_000) { "Subtitle file exceeds 1 MB." }; String(chars,0,count) } }
        val e=editor.value; val cues=CaptionTrack.parse(text,e.clips.sumOf { it.durationMs }.coerceAtLeast(1))
        edit { it.copy(captions=CaptionTrack.srt(cues)) }; message.value="${cues.size} editable subtitle cues imported. Times use the exported timeline."
    }
    fun exportCaptions(uri:Uri) = launch {
        val e=editor.value; val text=CaptionTrack.srt(CaptionTrack.parse(e.captions,e.clips.sumOf { it.durationMs }.coerceAtLeast(1)))
        withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(text) } }; message.value="SRT captions exported."
    }
    fun clean() = launch {
        require(projects.value.none { it.status in setOf("queued","processing") }) { "Wait for processing to finish before cleaning storage." }
        val retained=(history.states()+editor.value).flatMap { it.inputs+it.clips.map { clip -> clip.path } }.toSet()
        withContext(Dispatchers.IO) {
            app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            val used=projects.value.flatMap { p -> runCatching { val a=JSONObject(p.recipe).optJSONArray("inputs"); if(a==null)emptyList() else List(a.length()){a.getString(it)} }.getOrDefault(emptyList()) }.toSet()+retained
            File(app.filesDir,"imports").listFiles()?.filter { it.path !in used }?.forEach { it.delete() }
        }; message.value="Cache and unused imports cleared."
    }
    fun testBackend() = launch {
        val origin=prefs.value.backend
        val health=withContext(Dispatchers.IO) { BackendClient(origin,BackendSecrets(app).read()).health() }
        backendReport.value=health.put("_origin",origin)
        message.value=if(health.optInt("api_version",1)<2)"Connected to an older backend. Update it to v0.3 for readiness checks and automatic subtitles." else "Connected. Review each feature's model state below. Not loaded means inference has not yet been verified."
    }
    fun suggestClips() = launch {
        require(!importing.value) { "Wait for current processing to finish." }
        val e=editor.value; val clip=e.clips.getOrNull(e.selectedClip)?.validated() ?: error("Import and select a clip first.")
        require(clip.durationMs<=120000) { "Scan a selected range of two minutes or less." }
        importing.value=true; busyMessage.value="SCANNING CLIP SIGNALS..."
        try {
            val source=File(clip.path)
            val cuts=VideoEngine.scenes(source,clip.startMs,clip.endMs)
            val quiet=runCatching { VideoEngine.silence(source,clip.startMs,clip.endMs) }
            quiet.exceptionOrNull()?.let { if(it is CancellationException)throw it }
            val offset=Timeline.outputOffset(e.clips,e.selectedClip)
            val captions=CaptionTrack.parse(e.captions,e.clips.sumOf { it.durationMs }).filter { it.endMs>offset && it.startMs<offset+clip.durationMs }.map { it.copy(startMs=maxOf(0,it.startMs-offset)+clip.startMs,endMs=minOf(clip.durationMs,it.endMs-offset)+clip.startMs) }
            val ranges=ClipSuggestions.rank(clip.startMs,clip.endMs,cuts,quiet.getOrDefault(emptyList()),captions,quiet.isSuccess)
            val current=editor.value.clips.getOrNull(editor.value.selectedClip)
            if(current?.id==clip.id && current.startMs==clip.startMs && current.endMs==clip.endMs)edit { it.copy(suggestedClips=ranges) }
        } finally { importing.value=false; busyMessage.value="READING YOUR MEDIA..." }
    }
    fun useSuggestion(suggestion:SuggestedClip) {
        edit { it.copy(start="${suggestion.startMs/1000.0}",end="${suggestion.endMs/1000.0}",playheadMs=suggestion.startMs,previewRequestMs=suggestion.startMs,suggestedClips=emptyList()) }
    }
    fun testModel() = launch {
        require(!importing.value && projects.value.none { it.status in setOf("queued","processing") }) { "Wait for current processing to finish." }
        importing.value=true; busyMessage.value="RUNNING ON-DEVICE MODEL TEST..."
        try {
            val description=withContext(Dispatchers.IO) {
                val file=File(app.filesDir,"models/realesrgan-x4.onnx"); val report=ModelValidator.probe(file)
                File(file.parentFile,"validation.txt").writeText(report.description()); report.description()
            }
            modelReport.value=description; message.value="On-device x4 inference test passed. This verifies compatibility, not the provenance of user-supplied weights."
        } finally { importing.value=false; busyMessage.value="READING YOUR MEDIA..." }
    }
    fun transcribe() = launch {
        require(!importing.value) { "Wait for current processing to finish." }
        val e=editor.value; val clip=e.clips.getOrNull(e.selectedClip)?.validated() ?: error("Select a video clip first.")
        require(clip.durationMs<=600000) { "Trim the selected clip to ten minutes or less." }
        require(prefs.value.remoteConsent && prefs.value.backend.isNotBlank()) { "Configure a backend and allow remote processing in Settings first." }
        val recipe=e.recipe(prefs.value).put("transcribe_index",e.selectedClip).put("caption_offset",Timeline.outputOffset(e.clips,e.selectedClip))
        enqueue(Project(UUID.randomUUID().toString(),"AUTO SUBTITLES · CLIP ${e.selectedClip+1}","transcribe",recipe.toString()))
    }
    fun applyTranscript(project:Project) = launch {
        require(project.status=="completed" && project.mime=="application/x-subrip")
        val state=EditorState.restore("clipper",project.recipe)
        val recipe=JSONObject(project.recipe); val index=recipe.optInt("transcribe_index",0); val clip=state.clips.getOrNull(index) ?: error("Original clip is missing.")
        val duration=state.clips.sumOf { it.durationMs }; val offset=recipe.optLong("caption_offset",0)
        val replacement=withContext(Dispatchers.IO) { CaptionTrack.parse(File(project.output).readText(),duration) }
        val combined=CaptionTrack.replaceRange(CaptionTrack.parse(state.captions,duration),replacement,offset,offset+clip.durationMs)
        clearHistory(); editor.value=state.copy(captions=CaptionTrack.srt(combined)); screen.value="editor"
        message.value="Subtitles applied to the saved timeline snapshot. Review text and timing before exporting."
    }
}
