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
import kotlinx.coroutines.sync.withLock
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
    val gifPalette:String="Adaptive 256", val gifDither:Boolean=false,
    val captionSourceClips:List<ClipEdit> = emptyList(), val captionSourceCues:List<CaptionCue> = emptyList(),
    val faceRestore:Boolean=false, val faceStrength:Float=0.7f,
    val highlightPrompt:String="", val highlightSeconds:Int=6,
    val semanticClips:List<SemanticRange> = emptyList(), val semanticSampleCount:Int=0
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
        put("face_restore",faceRestore); put("face_strength",faceStrength.toDouble())
        put("highlight_prompt",highlightPrompt); put("highlight_clip_ms",highlightSeconds*1000)
        put("caption_source_clips",JSONArray().apply { captionSourceClips.forEach { clip -> put(JSONObject().apply { put("path",clip.path); put("start",clip.startMs); put("end",clip.endMs); put("duration",clip.sourceDurationMs); put("id",clip.id); put("origin",clip.originId) }) } })
        put("caption_source_cues",JSONArray().apply { captionSourceCues.forEach { cue -> put(JSONObject().apply { put("start",cue.startMs); put("end",cue.endMs); put("text",cue.text) }) } })
    }
    companion object {
        fun restore(tool:String,json:String):EditorState {
            val r=JSONObject(json); val a=r.optJSONArray("inputs") ?: JSONArray()
            val timeline=r.optJSONArray("clips")
            fun readClips(array:JSONArray?):List<ClipEdit> = if(array==null)emptyList() else List(array.length()) { index -> val c=array.getJSONObject(index); val id=c.optString("id").ifBlank { UUID.randomUUID().toString() }; ClipEdit(c.getString("path"),c.getLong("start"),c.getLong("end"),c.getLong("duration"),id,c.optString("origin",id).ifBlank { id }) }
            val clips=readClips(timeline)
            val sourceClips=readClips(r.optJSONArray("caption_source_clips"))
            val sourceArray=r.optJSONArray("caption_source_cues")
            val sourceCues=if(sourceArray==null)emptyList() else List(sourceArray.length()) { i -> val cue=sourceArray.getJSONObject(i); CaptionCue(cue.getLong("start"),cue.getLong("end"),cue.getString("text")) }
            val selected=r.optInt("selected_clip",0).coerceIn(0,clips.lastIndex.coerceAtLeast(0))
            val chosen=clips.getOrNull(selected)
            return EditorState(tool,List(a.length()){a.getString(it)},r.optBoolean("video"),"${(chosen?.startMs ?: r.optLong("start"))/1000.0}","${(chosen?.endMs ?: r.optLong("end",3000))/1000.0}",r.optInt("fps",8),r.optDouble("speed",1.0).toFloat(),r.optInt("width",320),r.optBoolean("loop",true),r.optString("caption"),r.optString("sticker"),r.optString("format","GIF"),r.optInt("scale",2),r.optDouble("intensity",0.7).toFloat(),r.optBoolean("denoise",true),r.optBoolean("sharpen",true),r.optString("prompt"),r.optString("negative"),r.optString("style","Cinematic"),r.optString("aspect","1:1"),r.optLong("seed",-1).let { if(it<0)"" else "$it" },r.optInt("duration",3),r.optDouble("motion",0.5).toFloat(),r.optString("captions"),clips=clips,selectedClip=selected,playheadMs=chosen?.startMs ?: 0,transcriptionLanguage=r.optString("transcription_language","auto"),stabilize=r.optBoolean("stabilize"),gifPalette=r.optString("gif_palette","Classic 256"),gifDither=r.optBoolean("gif_dither"),captionSourceClips=sourceClips,captionSourceCues=sourceCues,faceRestore=r.optBoolean("face_restore"),faceStrength=r.optDouble("face_strength",0.7).toFloat(),highlightPrompt=r.optString("highlight_prompt"),highlightSeconds=(r.optLong("highlight_clip_ms",6000)/1000).toInt().coerceIn(1,15)).let { state -> state.copy(start=r.optString("draft_start",state.start),end=r.optString("draft_end",state.end),seed=r.optString("draft_seed",state.seed)) }
        }
    }
}
data class HighlightConfirmation(val state:EditorState,val backend:String)

class StudioViewModel(application:Application):AndroidViewModel(application) {
    private val app=application as StudioApplication
    private val dao=app.database.projects()
    val settings=app.settings
    val prefs=settings.flow.stateIn(viewModelScope,SharingStarted.Eagerly,Preferences())
    val projects=dao.observe().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    private val sessionDao=app.database.sessions()
    val automaticDrafts=sessionDao.observe().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val autosaveStatus=MutableStateFlow("")
    private var draftId=UUID.randomUUID().toString()
    private var draftRevision=0L
    private var autosaveJob:Job?=null
    val screen=MutableStateFlow("home")
    val editor=MutableStateFlow(EditorState())
    val message=MutableStateFlow("")
    val importing=MutableStateFlow(false)
    val busyMessage=MutableStateFlow("READING YOUR MEDIA...")
    val busyPercent=MutableStateFlow<Int?>(null)
    val transferActive=MutableStateFlow(false)
    val faceConfirmation=MutableStateFlow(false)
    val highlightConfirmation=MutableStateFlow<HighlightConfirmation?>(null)
    private var transferJob:Job?=null
    private var transferCommitted=false
    val modelReport=MutableStateFlow("")
    val speechInstalled=MutableStateFlow(SpeechModelStore.installed(File(app.filesDir,"models")))
    val backendReport=MutableStateFlow<JSONObject?>(null)
    val active=MutableStateFlow<String?>(null)
    val work=WorkManager.getInstance(app)
    private val history=EditHistory<EditorState>()
    val canUndo=MutableStateFlow(false); val canRedo=MutableStateFlow(false)
    private fun updateHistory() { canUndo.value=history.canUndo; canRedo.value=history.canRedo }
    private fun clearHistory() { history.clear(); updateHistory(); faceConfirmation.value=false; highlightConfirmation.value=null }
    private fun snapshot(e:EditorState)=e.copy(playheadMs=0,previewRequestMs=null,suggestions=emptyList(),suggestedClips=emptyList(),semanticClips=emptyList(),semanticSampleCount=0)
    private fun workspace(state:EditorState) {
        flushAutosave(); clearHistory(); draftId=UUID.randomUUID().toString(); draftRevision=0; autosaveStatus.value=""; editor.value=state
    }
    fun open(tool:String) { workspace(EditorCheckpoints.empty(tool)); screen.value="editor" }
    fun back() { flushAutosave(); faceConfirmation.value=false; highlightConfirmation.value=null; screen.value="home" }
    private data class PendingDraft(val id:String,val revision:Long,val value:EditorCheckpoint,val edited:Long=System.currentTimeMillis())
    private fun captureDraft():PendingDraft? {
        val value=EditorCheckpoint(EditorCheckpoints.durable(editor.value),history.snapshot())
        if(!EditorCheckpoints.meaningful(value))return null
        draftRevision=app.draftWriter.revision(draftRevision)
        autosaveStatus.value="AUTO-SAVING..."
        return PendingDraft(draftId,draftRevision,value)
    }
    private fun submitDraft(pending:PendingDraft):Job {
        val retained=app.draftWriter.retain(pending.id,pending.revision,EditorCheckpoints.paths(pending.value))
        return app.draftScope.launch {
        try {
            val (payload,value)=EditorCheckpoints.encode(pending.value)
            val root=File(app.filesDir,"imports").canonicalPath+File.separator
            require(EditorCheckpoints.paths(value).all { path -> File(path).canonicalPath.startsWith(root) && File(path).isFile }) { "Draft media is missing or outside private imports." }
            val record=EditorSession(pending.id,value.current.tool,payload,EditorCheckpoints.media(value),pending.revision,pending.edited,"${value.current.tool.uppercase()} · ${value.current.inputs.size} MEDIA")
            val saved=app.draftWriter.save(record)
            withContext(Dispatchers.Main) {
                if(draftId==pending.id && draftRevision==pending.revision)autosaveStatus.value=if(saved)"AUTO-SAVED${if(value.historyLimited)" · HISTORY LIMITED" else ""}" else "AUTO-SAVE SKIPPED · A NEWER DRAFT EXISTS"
            }
        } catch(e:Exception) {
            if(e is CancellationException)throw e
            withContext(Dispatchers.Main) { if(draftId==pending.id && draftRevision==pending.revision)autosaveStatus.value="AUTO-SAVE PAUSED · ${e.message ?: "Storage unavailable"}" }
        } finally { app.draftWriter.release(retained) }
        }
    }
    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        val pending=captureDraft() ?: return
        autosaveJob=viewModelScope.launch { delay(500); submitDraft(pending) }
    }
    /** Also used by onStop; the application scope completes writes after activity recreation. */
    fun flushAutosave():Job {
        autosaveJob?.cancel(); autosaveJob=null
        val pending=captureDraft()
        return if(pending==null)app.draftScope.launch {} else submitDraft(pending)
    }
    fun restoreDraft(id:String) = launch {
        require(!importing.value) { "Wait for current processing to finish." }
        flushAutosave().join()
        val record=sessionDao.get(id) ?: error("This automatic draft was deleted.")
        val (value,past,future)=withContext(Dispatchers.IO) {
            val value=EditorCheckpoints.decode(record.payload)
            val root=File(app.filesDir,"imports").canonicalPath+File.separator
            fun available(state:EditorState)=EditorCheckpoints.paths(EditorCheckpoint(state,HistorySnapshot())).all { File(it).canonicalPath.startsWith(root) && File(it).isFile }
            require(available(value.current)) { "The draft's imported media is missing. Restore a portable project backup if available." }
            Triple(value,value.history.past.filter(::available),value.history.future.filter(::available))
        }
        autosaveJob?.cancel(); clearHistory(); draftId=id; draftRevision=record.revision
        history.restore(HistorySnapshot(past,future)); updateHistory(); editor.value=value.current
        autosaveStatus.value=if(value.historyLimited || past.size!=value.history.past.size || future.size!=value.history.future.size)"RECOVERED DRAFT · SOME HISTORY UNAVAILABLE" else "RECOVERED AUTO-SAVE"
        screen.value="editor"
    }
    fun discardDraft(id:String) = launch {
        if(draftId==id) { autosaveJob?.cancel(); clearHistory(); editor.value=EditorCheckpoints.empty(editor.value.tool); draftId=UUID.randomUUID().toString(); draftRevision=0; autosaveStatus.value="" }
        app.draftWriter.delete(id)
    }
    fun keepDraft(id:String) = launch {
        val record=sessionDao.get(id) ?: error("This automatic draft was deleted.")
        val state=withContext(Dispatchers.IO) { EditorCheckpoints.decode(record.payload).current }
        val recipe=state.recipe(Preferences()).put("draft_start",state.start).put("draft_end",state.end).put("draft_seed",state.seed)
        dao.save(Project(UUID.randomUUID().toString(),"${record.label} · KEPT DRAFT",state.tool,recipe.toString()))
        message.value="Draft kept in Save Games. Use BACKUP PROJECT there to save a portable copy. Undo history remains with the automatic draft on this device."
    }
    private fun trim(e:EditorState):ClipEdit? {
        val clip=e.clips.getOrNull(e.selectedClip) ?: return null
        val start=e.start.toDoubleOrNull()?.takeIf { it.isFinite() && it>=0 && it<=clip.sourceDurationMs/1000.0 } ?: return null
        val end=e.end.toDoubleOrNull()?.takeIf { it.isFinite() && it>=0 && it<=clip.sourceDurationMs/1000.0 } ?: return null
        return runCatching { clip.copy(startMs=(start*1000).roundToLong(),endMs=(end*1000).roundToLong()).validated() }.getOrNull()
    }
    fun edit(group:String?=null,change:(EditorState)->EditorState) {
        try {
            val old=editor.value; var next=change(old)
            if(next.highlightPrompt!=old.highlightPrompt || next.highlightSeconds!=old.highlightSeconds)next=next.copy(semanticClips=emptyList(),semanticSampleCount=0)
            if(next.captions!=old.captions)next=next.copy(captionSourceClips=emptyList(),captionSourceCues=emptyList())
            if(next.tool=="clipper" && next.clips==old.clips && next.selectedClip==old.selectedClip && (next.start!=old.start || next.end!=old.end)) {
                trim(next)?.let { edited -> next=next.copy(clips=next.clips.mapIndexed { i,c -> if(i==next.selectedClip)edited else c }) }
            }
            if(next.tool=="clipper" && next.clips!=old.clips) {
                if((old.captions.isNotBlank() || old.captionSourceCues.isNotEmpty()) && next.captions==old.captions && old.clips.isNotEmpty()) {
                    val source=old.captionSourceClips.ifEmpty { old.clips }
                    val cues=if(old.captionSourceClips.isNotEmpty())old.captionSourceCues else CaptionTrack.parse(old.captions,old.clips.sumOf { it.durationMs }.coerceAtLeast(1))
                    next=next.copy(captions=CaptionTrack.srt(CaptionTimeline.retime(cues,source,next.clips)),captionSourceClips=source,captionSourceCues=cues)
                }
                next=next.copy(suggestions=emptyList(),suggestedClips=emptyList(),semanticClips=emptyList(),semanticSampleCount=0)
            }
            history.record(snapshot(old),snapshot(next),group,android.os.SystemClock.uptimeMillis())
            editor.value=next; updateHistory()
            if(snapshot(old)!=snapshot(next))scheduleAutosave()
        } catch(e:Exception) { message.value=e.message ?: "This edit could not be applied." }
    }
    private fun restoreHistory(state:EditorState) {
        val index=state.selectedClip.coerceIn(0,state.clips.lastIndex.coerceAtLeast(0)); val clip=state.clips.getOrNull(index)
        editor.value=state.copy(selectedClip=index,playheadMs=clip?.startMs ?: 0,previewRequestMs=clip?.startMs,suggestions=emptyList(),suggestedClips=emptyList(),semanticClips=emptyList(),semanticSampleCount=0); updateHistory(); scheduleAutosave()
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
    fun speechModel(uri:Uri) = launch {
        require(!importing.value && projects.value.none { it.status in setOf("queued","processing") }) { "Wait for current processing before installing a speech model." }
        importing.value=true; busyMessage.value="VERIFYING SPEECH MODEL..."; busyPercent.value=null
        try {
            withContext(Dispatchers.IO) {
                OfflineSpeech.gate.withLock {
                    OfflineSpeech.ensureMemory(app)
                    val task=currentCoroutineContext()
                    app.contentResolver.openInputStream(uri)!!.use { source -> SpeechModelStore.install(source,File(app.filesDir,"models"),OfflineSpeech::probe,{ task.ensureActive() }) { value,label -> busyPercent.value=value; busyMessage.value=label } }
                }
            }
            message.value="English speech model installed after actual device inference. Offline subtitles are ready."
        } finally { speechInstalled.value=SpeechModelStore.installed(File(app.filesDir,"models")); importing.value=false; busyPercent.value=null; busyMessage.value="READING YOUR MEDIA..." }
    }
    fun removeSpeechModel() = launch {
        require(!importing.value && projects.value.none { it.status in setOf("queued","processing") }) { "Wait for processing before removing the speech model." }
        withContext(Dispatchers.IO) { OfflineSpeech.gate.withLock { require(SpeechModelStore.directory(File(app.filesDir,"models")).deleteRecursively()) { "Cannot remove the speech model." } } }
        speechInstalled.value=false; message.value="Offline speech model removed. Saved subtitles are kept."
    }
    private suspend fun enqueue(project:Project) {
        val request=OneTimeWorkRequestBuilder<CreationWorker>().setInputData(workDataOf("project" to project.id)).addTag("creation").build()
        dao.save(project.copy(status="queued",workId=request.id.toString(),error=""))
        work.enqueueUniqueWork(project.id,ExistingWorkPolicy.KEEP,request)
        active.value=project.id; screen.value="processing"
    }
    fun create(draft:Boolean=false,faceConfirmed:Boolean=false) = launch {
        require(!importing.value) { "Wait for the current import or model test to finish." }
        flushAutosave()
        val e=editor.value
        if(!draft) {
            if(e.tool in setOf("photo","video","aigif")) { require(e.prompt.isNotBlank()) { "Enter a prompt." }; require(prefs.value.backend.isNotBlank() && prefs.value.remoteConsent) { "Set up an HTTPS backend and allow remote processing in Settings." } }
            else require(e.inputs.isNotEmpty()) { "Import media first." }
            if(e.faceRestore && !e.video && e.tool in setOf("enhance","upscale")) {
                require(prefs.value.remoteConsent && prefs.value.backend.isNotBlank()) { "Configure a face-restoration backend and allow remote processing in Settings first." }
                require(e.faceStrength.isFinite() && e.faceStrength>0f && e.faceStrength<=1f)
                if(!faceConfirmed) { faceConfirmation.value=true; return@launch }
            }
            if(e.tool=="clipper") {
                require(trim(e)==e.clips.getOrNull(e.selectedClip) && trim(e)!=null) { "Enter valid trim times and keep at least 40 milliseconds." }
                e.clips.forEach { it.validated() }
                CaptionTrack.parse(e.captions,e.clips.sumOf { it.durationMs }.coerceAtLeast(1))
            }
            if(e.tool=="gif" && e.video && e.format=="GIF")EditRules.frameTimes(((e.start.toDoubleOrNull() ?: 0.0)*1000).toLong(),((e.end.toDoubleOrNull() ?: 0.0)*1000).toLong(),e.fps,e.speed)
        }
        faceConfirmation.value=false
        val recipe=e.recipe(prefs.value).put("face_upload_confirmed",!draft && faceConfirmed && e.faceRestore)
        if(draft)recipe.put("draft_start",e.start).put("draft_end",e.end).put("draft_seed",e.seed)
        val p=Project(UUID.randomUUID().toString(),"${e.tool.uppercase()} · ${java.text.SimpleDateFormat("MMM dd HH:mm",java.util.Locale.US).format(java.util.Date())}",e.tool,recipe.toString())
        if(draft) { dao.save(p); message.value="Saved in projects." } else enqueue(p)
    }
    fun cancel(project:Project) = launch { work.cancelUniqueWork(project.id); dao.status(project.id,"cancelled") }
    fun resume(project:Project) = launch {
        if(project.tool=="highlights" && project.status=="completed") { applyHighlights(project); return@launch }
        if(project.tool=="transcribe" && project.status=="completed") { applyTranscript(project); return@launch }
        var state=EditorState.restore(if(project.tool in setOf("transcribe","highlights"))"clipper" else project.tool,project.recipe)
        if(state.tool=="clipper" && state.clips.isEmpty() && state.inputs.isNotEmpty()) {
            val restored=state
            val clips=withContext(Dispatchers.IO) { restored.inputs.mapIndexed { index,path -> val d=VideoEngine.duration(File(path)); ClipEdit(path,if(index==0)((restored.start.toDoubleOrNull() ?: 0.0)*1000).toLong() else 0,if(index==0)((restored.end.toDoubleOrNull() ?: d/1000.0)*1000).toLong() else d,d) } }
            state=state.copy(clips=clips,playheadMs=clips.first().startMs)
        }
        workspace(state); screen.value="editor"; scheduleAutosave()
    }
    fun retry(project:Project) = launch {
        if(project.tool=="highlights") {
            workspace(EditorState.restore("clipper",project.recipe)); screen.value="editor"; scheduleAutosave(); semanticHighlights(); return@launch
        }
        if(JSONObject(project.recipe).let { it.optBoolean("face_restore") && !it.optBoolean("video") } && project.tool in setOf("enhance","upscale")) {
            workspace(EditorState.restore(project.tool,project.recipe)); screen.value="editor"; scheduleAutosave(); faceConfirmation.value=true
        } else enqueue(project.copy(id=UUID.randomUUID().toString(),created=System.currentTimeMillis(),output=""))
    }
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
    private fun transfer(label:String,completeMessage:String,block:suspend()->Unit) {
        if(importing.value) { message.value="Wait for the current import or transfer to finish."; return }
        importing.value=true; transferActive.value=true; transferCommitted=false; busyPercent.value=null; busyMessage.value=label
        transferJob=viewModelScope.launch {
            try { block(); message.value=completeMessage }
            catch(e:CancellationException) { message.value=if(transferCommitted)completeMessage else "Transfer cancelled."; throw e }
            catch(e:Exception) { message.value=e.message ?: "Project transfer failed." }
            catch(e:OutOfMemoryError) { message.value="Not enough memory to transfer this project." }
            finally { importing.value=false; transferActive.value=false; busyPercent.value=null; busyMessage.value="READING YOUR MEDIA..."; transferJob=null }
        }
    }
    fun cancelTransfer() { if(transferActive.value)transferJob?.cancel() }
    fun backup(project:Project,uri:Uri)=transfer("BACKING UP PROJECT...","Project backup saved. It includes media and edits, without server credentials.") {
        withContext(Dispatchers.IO) {
            var complete=false
            try {
                val context=currentCoroutineContext()
                app.contentResolver.openOutputStream(uri,"wt")!!.use { output -> ProjectArchive.write(project,app.filesDir,output,check={context.ensureActive()},progress={busyPercent.value=it}) }
                complete=true; transferCommitted=true
            } finally { if(!complete)runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver,uri) } }
        }
    }
    fun importBackup(uri:Uri)=transfer("RESTORING PROJECT...","Project restored as a new save. Server settings, credentials and model weights were not imported.") {
        withContext(Dispatchers.IO) {
            val context=currentCoroutineContext()
            app.contentResolver.openInputStream(uri)!!.use { input ->
                ProjectArchive.restore(input,app.filesDir,check={context.ensureActive()},progress={busyPercent.value=it}) { project ->
                    transferActive.value=false
                    dao.save(project); transferCommitted=true; screen.value="projects"
                }
            }
        }
    }
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
        editor.value=e.copy(selectedClip=index,start="${clip.startMs/1000.0}",end="${clip.endMs/1000.0}",playheadMs=clip.startMs,previewRequestMs=clip.startMs,suggestions=emptyList(),suggestedClips=emptyList(),semanticClips=emptyList(),semanticSampleCount=0)
        scheduleAutosave()
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
        scheduleAutosave()
    }
    fun previewPosition(clipId:String,request:Long?,ms:Long) {
        val e=editor.value; val clip=e.clips.getOrNull(e.selectedClip) ?: return
        // Ignore callbacks queued before the latest seek or from a previously selected block.
        if(e.tool=="clipper" && clip.id==clipId && e.previewRequestMs==request && ms in clip.startMs..clip.endMs)
            editor.value=e.copy(playheadMs=ms)
    }
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
        require(!importing.value) { "Wait for the current import or transfer to finish." }
        importing.value=true; busyMessage.value="CLEANING STORAGE..."
        try {
            val savedProjects=dao.observe().first()
            require(savedProjects.none { it.status in setOf("queued","processing") }) { "Wait for processing to finish before cleaning storage." }
            flushAutosave().join()
            val retained=(history.states()+editor.value).flatMap { it.inputs+(it.clips+it.captionSourceClips).map { clip -> clip.path } }.toSet()
            withContext(Dispatchers.IO) {
                app.draftWriter.withRetainedMedia { sessions ->
                    val used=savedProjects.flatMap { p -> runCatching { val r=JSONObject(p.recipe); val a=r.optJSONArray("inputs"); val source=r.optJSONArray("caption_source_clips"); (if(a==null)emptyList() else List(a.length()){a.getString(it)})+(if(source==null)emptyList() else List(source.length()){source.getJSONObject(it).getString("path")}) }.getOrDefault(emptyList()) }.toSet()+retained+sessions
                    app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    ProjectArchive.interruptedStaging(app.filesDir).forEach { it.deleteRecursively() }
                    File(app.filesDir,"imports").listFiles()?.filter { it.path !in used }?.forEach { it.delete() }
                }
            }; message.value="Cache and unused imports cleared. Automatic drafts and their undo media are kept."
        } finally { importing.value=false; busyMessage.value="READING YOUR MEDIA..." }
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
    fun semanticHighlights(confirmed:Boolean=false) = launch {
        require(!importing.value) { "Wait for current processing to finish." }
        val pending=if(confirmed)highlightConfirmation.value ?: error("Review and confirm selected-frame upload first.") else HighlightConfirmation(editor.value,prefs.value.backend)
        val e=pending.state
        require(e.tool=="clipper")
        val clip=e.clips.getOrNull(e.selectedClip)?.validated() ?: error("Select a video clip first.")
        require(trim(e)==clip) { "Finish valid start/end times before requesting highlights." }
        SemanticHighlights.sampleTimes(clip.durationMs)
        require(e.highlightPrompt.isNotBlank() && e.highlightPrompt.length<=300) { "Describe what to find using 1–300 characters." }
        require(prefs.value.remoteConsent && prefs.value.backend.isNotBlank() && prefs.value.backend==pending.backend) { "Configure your backend and allow remote processing in Settings. Confirm again if the server changed." }
        if(!confirmed) { highlightConfirmation.value=pending; return@launch }
        highlightConfirmation.value=null
        val recipe=e.recipe(prefs.value).put("highlight_index",e.selectedClip).put("highlight_upload_confirmed",true)
        enqueue(Project(UUID.randomUUID().toString(),"VISUAL HIGHLIGHTS · CLIP ${e.selectedClip+1}","highlights",recipe.toString()))
    }
    fun applyHighlights(project:Project) = launch {
        require(project.status=="completed" && project.mime=="application/json")
        val recipe=JSONObject(project.recipe)
        val state=EditorState.restore("clipper",project.recipe)
        val index=recipe.getInt("highlight_index"); val clip=state.clips.getOrNull(index)?.validated() ?: error("Original clip is missing.")
        val result=withContext(Dispatchers.IO) {
            val file=File(project.output); require(file.length() in 1..65536) { "Invalid highlight result size." }
            SemanticHighlights.parse(file.readText(),clip.durationMs)
        }
        workspace(state.copy(selectedClip=index,start="${clip.startMs/1000.0}",end="${clip.endMs/1000.0}",playheadMs=clip.startMs,semanticClips=result.ranges.map { it.copy(startMs=it.startMs+clip.startMs,endMs=it.endMs+clip.startMs) },semanticSampleCount=result.sampleCount))
        scheduleAutosave()
        screen.value="editor"
        message.value="Visual matches loaded on the saved timeline snapshot. Preview a range before applying it. Cosine scores are relative similarity, not confidence."
    }
    fun useSemanticRange(range:SemanticRange) {
        val e=editor.value; val clip=e.clips.getOrNull(e.selectedClip) ?: return
        if(range !in e.semanticClips || range.startMs<clip.startMs || range.endMs>clip.endMs) { message.value="Re-run highlights for the current trim."; return }
        edit { it.copy(start="${range.startMs/1000.0}",end="${range.endMs/1000.0}",playheadMs=range.startMs,previewRequestMs=range.startMs,semanticClips=emptyList(),semanticSampleCount=0) }
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
    fun transcribe(offline:Boolean=false) = launch {
        require(!importing.value) { "Wait for current processing to finish." }
        val e=editor.value; val clip=e.clips.getOrNull(e.selectedClip)?.validated() ?: error("Select a video clip first.")
        require(trim(e)==clip) { "Finish valid start/end times before requesting subtitles." }
        require(clip.durationMs<=if(offline)120000 else 600000) { if(offline)"Offline subtitles support up to two minutes per selected block." else "Trim the selected clip to ten minutes or less." }
        if(offline)require(SpeechModelStore.installed(File(app.filesDir,"models"))) { "Import the verified English speech model in Settings first." }
        else require(prefs.value.remoteConsent && prefs.value.backend.isNotBlank()) { "Configure a backend and allow remote processing in Settings first." }
        val recipe=e.recipe(prefs.value).put("transcribe_index",e.selectedClip).put("caption_offset",Timeline.outputOffset(e.clips,e.selectedClip))
            .put("speech_engine",if(offline)"offline" else "server")
        if(offline) { recipe.put("backend","").put("consent",false).put("transcription_language","en") }
        enqueue(Project(UUID.randomUUID().toString(),"${if(offline)"OFFLINE" else "AUTO"} SUBTITLES · CLIP ${e.selectedClip+1}","transcribe",recipe.toString()))
    }
    fun applyTranscript(project:Project) = launch {
        require(project.status=="completed" && project.mime=="application/x-subrip")
        val state=EditorState.restore("clipper",project.recipe)
        val recipe=JSONObject(project.recipe); val index=recipe.optInt("transcribe_index",0); val clip=state.clips.getOrNull(index) ?: error("Original clip is missing.")
        val duration=state.clips.sumOf { it.durationMs }; val offset=recipe.optLong("caption_offset",0)
        val replacement=withContext(Dispatchers.IO) { CaptionTrack.parse(File(project.output).readText(),duration) }
        val combined=CaptionTrack.replaceRange(CaptionTrack.parse(state.captions,duration),replacement,offset,offset+clip.durationMs)
        workspace(state.copy(captions=CaptionTrack.srt(combined),captionSourceClips=emptyList(),captionSourceCues=emptyList())); screen.value="editor"; scheduleAutosave()
        message.value="Subtitles applied to the saved timeline snapshot. Review text and timing before exporting."
    }
}
