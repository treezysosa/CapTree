package studio.retro.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.*
import studio.retro.ai.*
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun EditorScreen(vm:StudioViewModel,e:EditorState) {
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(40)) { if(it.isNotEmpty())vm.import(it,false) }
    val videoPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { if(it.isNotEmpty())vm.import(it,true) }
    val subtitlePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if(it!=null)vm.importCaptions(it) }
    val subtitleExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { if(it!=null)vm.exportCaptions(it) }
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    var confirmTranscription by remember { mutableStateOf(false) }
    val confirmFace by vm.faceConfirmation.collectAsStateWithLifecycle()
    val confirmHighlights by vm.highlightConfirmation.collectAsStateWithLifecycle()
    val speechInstalled by vm.speechInstalled.collectAsStateWithLifecycle()
    val isAI=e.tool in setOf("photo","video","aigif")
    val title=if(e.tool=="aigif")"AI ANIMATION" else ToolNames[ToolIds.indexOf(e.tool).coerceAtLeast(0)].replace('\n',' ')
    ConsolePanel(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),lcd=true) {
        PixelText(title,color=LocalConsole.current.lcd,size=10)
        Text(if(isAI)"REMOTE AI · your self-hosted server" else if(e.faceRestore && !e.video)"LOCAL EDIT + REMOTE FACE AI · upload confirmation required" else if(e.tool=="upscale")"LOCAL AI · imported Real-ESRGAN ONNX model" else "OFFLINE · conventional media processing",color=LocalConsole.current.accent,fontSize=11.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            RaisedButton("UNDO",vm::undo,Modifier.weight(1f),enabled=canUndo && !importing)
            RaisedButton("REDO",vm::redo,Modifier.weight(1f),enabled=canRedo && !importing)
        }
        if(!isAI || e.tool in setOf("photo","video")) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(e.tool!="clipper")RaisedButton(if(isAI)"INPUT IMAGE" else "IMPORT PHOTOS",{ imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },Modifier.weight(1f))
                if(!isAI && e.tool in setOf("gif","clipper","enhance"))RaisedButton(if(e.tool=="clipper" && e.clips.isNotEmpty())"ADD VIDEOS" else "IMPORT VIDEO",{ videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },Modifier.weight(1f))
            }
        }
        if(e.inputs.isNotEmpty()) {
            val clip=e.clips.getOrNull(e.selectedClip)
            if(e.tool=="clipper" && clip!=null) {
                val valid=clip.endMs>clip.startMs && clip.startMs>=0
                MediaPreview(clip.path,"video/mp4",seekPositionMs=e.previewRequestMs,rangeStartMs=if(valid)clip.startMs else 0,rangeEndMs=if(valid)clip.endMs else null,onPositionChanged=vm::previewPosition)
            } else MediaPreview(e.inputs.first(),if(e.video)"video/mp4" else "image/png")
            Text("${e.inputs.size} file(s) · ${if(e.video)"video" else "images"}",color=Cream,fontSize=12.sp)
            if(e.tool!="clipper")LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(e.inputs.size) { i -> Text("▣ ${i+1}",Modifier.background(LocalConsole.current.shell).padding(10.dp),color=Cream) } }
            if(e.inputs.size>1 && e.tool !in setOf("clipper","gif"))Text("This tool processes the first file.",color=LocalConsole.current.accent,fontSize=11.sp)
            TextButton({ vm.edit { it.copy(inputs=emptyList(),video=false,clips=emptyList(),captions="",selectedClip=0,suggestions=emptyList(),suggestedClips=emptyList()) } }) { Text("CLEAR INPUTS") }
        }
        when(e.tool) {
            "upscale" -> {
                Choice("UPSCALE",listOf("2×","4×"),"${e.scale}×") { choice -> vm.edit { it.copy(scale=if(choice=="2×")2 else 4) } }
                Text("Uses a genuine x4 super-resolution model; 2× downsamples its result. Input is decoded to ≤1024 pixels. Output size is checked against available memory.",fontSize=12.sp)
                PhotoAdjustments(vm,e)
            }
            "enhance" -> {
                Text(if(e.video)"GPU brightness, contrast, saturation, spatial denoising, sharpening and optional translation stabilization. These are traditional algorithms." else "Histogram exposure and contrast, gray-world white balance, saturation, edge-aware denoising, and sharpening. These are traditional algorithms.",fontSize=12.sp)
                PhotoAdjustments(vm,e)
            }
            "clipper" -> {
                ClipTimeline(vm,e)
                Text("Each block has its own trim. Export merges the visible order with synchronized audio. Left/right D-pad seeks by 1/30 second; up/down selects a block.",fontSize=12.sp)
                TimeFields(vm,e)
                Choice("CROP / RESIZE",listOf("Original","9:16","1:1","16:9"),e.aspect) { value -> vm.edit { it.copy(aspect=value) } }
                RaisedButton("SPLIT AT PLAYHEAD",vm::split,enabled=e.clips.isNotEmpty())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { RaisedButton("SCENE CUTS",{ vm.analyze(false) },Modifier.weight(1f)); RaisedButton("QUIET AUDIO",{ vm.analyze(true) },Modifier.weight(1f)) }
                RaisedButton("SUGGEST CLIP RANGES",vm::suggestClips,Modifier.fillMaxWidth(),enabled=e.clips.isNotEmpty())
                for(suggestion in e.suggestedClips) {
                    Text("${suggestion.startMs/1000.0}–${suggestion.endMs/1000.0}s · signal score ${suggestion.score}\n${suggestion.reason}",fontSize=12.sp,color=LocalConsole.current.accent)
                    RaisedButton("USE THIS RANGE",{ vm.useSuggestion(suggestion) })
                }
                if(e.suggestions.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(e.suggestions.size) { index -> TextButton({ vm.seek(e.suggestions[index]) }) { Text("${e.suggestions[index]/1000.0}s") } } }
                Text("Suggestions rank scene changes, quiet audio and caption activity within a selected range up to two minutes. Scores are conventional signals, not semantic AI judgments. Captions follow the footage when a range is applied.",fontSize=11.sp,color=LocalConsole.current.accent)
                PixelText("AI VISUAL MATCHES",size=9,color=LocalConsole.current.lcd)
                RetroField("FIND IN THIS CLIP",e.highlightPrompt,{ value -> vm.edit("highlight-prompt") { it.copy(highlightPrompt=value) } },multiline=true)
                Choice("SUGGESTED CLIP LENGTH · SECONDS",listOf("3","6","10"),"${e.highlightSeconds}") { value -> vm.edit { it.copy(highlightSeconds=value.toInt()) } }
                RaisedButton("AI VISUAL HIGHLIGHTS · SERVER",{ vm.semanticHighlights() },Modifier.fillMaxWidth(),LocalConsole.current.pink,enabled=e.clips.isNotEmpty())
                RaisedButton("HIGHLIGHT SERVER SETTINGS",{ vm.screen.value="settings" })
                Text("Optional CPU CLIP compares up to 24 small still frames with your description. It does not listen to audio or understand full motion. Fast events between samples may be missed; every clip has a best match even when nothing is relevant. Scores are cosine similarity, not a confidence percentage. Requires your server and a separate frame-upload confirmation.",fontSize=11.sp)
                if(e.semanticClips.isNotEmpty())Text("${e.semanticSampleCount} frames sampled · saved description: ${e.highlightPrompt}",fontSize=12.sp,color=LocalConsole.current.accent)
                for(range in e.semanticClips) {
                    Text("${range.startMs/1000.0}–${range.endMs/1000.0}s · cosine ${"%.3f".format(java.util.Locale.US,range.score)}",fontSize=12.sp,color=LocalConsole.current.lcd)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        RaisedButton("PREVIEW MATCH",{ vm.seek((range.startMs+range.endMs)/2) },Modifier.weight(1f))
                        RaisedButton("USE AI RANGE",{ vm.useSemanticRange(range) },Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { RaisedButton("IMPORT SRT",{ subtitlePicker.launch(arrayOf("application/x-subrip","text/plain","*/*")) },Modifier.weight(1f)); RaisedButton("EXPORT SRT",{ subtitleExport.launch("RETRO-captions.srt") },Modifier.weight(1f),enabled=e.captions.isNotBlank()) }
                PixelText("AUTOMATIC SUBTITLES",size=9,color=LocalConsole.current.lcd)
                RaisedButton("OFFLINE ENGLISH SUBTITLES",{ vm.transcribe(offline=true) },Modifier.fillMaxWidth(),enabled=e.clips.isNotEmpty() && speechInstalled)
                Text(if(speechInstalled)"English model installed. Recognizes the selected block on this phone, up to two minutes. No audio upload or server connection. Review all generated captions." else "Import the optional English speech model in Settings to use automatic subtitles without a server.",fontSize=11.sp)
                RaisedButton("SPEECH MODEL SETTINGS",{ vm.screen.value="settings" })
                Choice("SERVER SPEECH LANGUAGE",listOf("auto","en","es","fr","de","ja"),e.transcriptionLanguage) { value -> vm.edit { it.copy(transcriptionLanguage=value) } }
                RaisedButton("AI AUTO SUBTITLES · SERVER",{ confirmTranscription=true },Modifier.fillMaxWidth(),LocalConsole.current.pink,enabled=e.clips.isNotEmpty())
                Text("Optional speech recognition runs on your self-hosted server. Only the selected clip's trimmed audio is sent, after confirmation. Other editing stays offline.",fontSize=11.sp)
                RetroField("EDIT CAPTIONS · SRT or milliseconds|text",e.captions,{ value -> vm.edit("caption-text") { it.copy(captions=value) } },multiline=true)
                Text("Captions follow source footage through trims, splits, moves and removals. Shorter trims hide text; expanding the trim restores it. Edited captions use the current trim as their starting point. UNDO recovers earlier edits. Finish valid caption syntax before changing the timeline.",fontSize=11.sp)
            }
            "gif" -> {
                Choice("OUTPUT",listOf("GIF","MP4"),e.format) { value -> vm.edit { it.copy(format=value) } }
                if(e.video)TimeFields(vm,e)
                RetroSlider("FRAMES PER SECOND · ${e.fps}",e.fps.toFloat(),1f..24f,22) { value -> vm.edit("gif-fps") { it.copy(fps=value.toInt()) } }
                RetroSlider("SPEED · ${"%.2f".format(e.speed)}×",e.speed,0.25f..4f) { value -> vm.edit("gif-speed") { it.copy(speed=value) } }
                Choice("MAXIMUM EDGE · SIZE OPTIMIZATION",listOf("160","240","320","480"),"${e.width}") { value -> vm.edit { it.copy(width=value.toInt()) } }
                RetroField("CAPTION",e.caption,{ value -> vm.edit("gif-caption") { it.copy(caption=value) } })
                Choice("STICKER",listOf("","★","♥","✦","☀"),e.sticker) { value -> vm.edit { it.copy(sticker=value) } }
                if(e.format=="GIF") {
                    RetroToggle("Loop animation",e.loop) { value -> vm.edit { it.copy(loop=value) } }
                    Choice("COLOR PALETTE",listOf("Classic 256","Adaptive 64","Adaptive 128","Adaptive 256"),e.gifPalette) { value -> vm.edit { it.copy(gifPalette=value) } }
                    if(e.gifPalette!="Classic 256")RetroToggle("Dither gradients",e.gifDither) { value -> vm.edit { it.copy(gifDither=value) } }
                    Text("Adaptive colors preserve subtle tones; dithering softens bands and may increase file size. Classic uses a fixed retro palette. Up to 240 frames; GIF delays round to 10 ms with a 20 ms minimum. Reduce dimensions or FPS for smaller files.",fontSize=11.sp)
                } else Text("MP4 applies captions, stickers, size and speed. Source audio changes speed with the video. Image animations support up to 60 effective FPS; video FPS is a cap without frame interpolation. Loop metadata is available for GIF output.",fontSize=11.sp)
                RaisedButton("TEXT → AI ANIMATED GIF",{ vm.open("aigif") },color=LocalConsole.current.pink)
            }
            "photo","video","aigif" -> {
                Text("Prompts and any imported image are sent only when you start generation and have enabled remote processing. Requires your own compatible inference server and model hardware.",fontSize=12.sp,color=LocalConsole.current.accent)
                RetroField("PROMPT >",e.prompt,{ value -> vm.edit("ai-prompt") { it.copy(prompt=value) } },multiline=true)
                if(e.tool=="photo")RetroField("NEGATIVE PROMPT >",e.negative,{ value -> vm.edit("ai-negative") { it.copy(negative=value) } },multiline=true)
                Choice("STYLE",if(e.tool=="photo")PhotoStyles else VideoStyles,e.style) { value -> vm.edit { it.copy(style=value) } }
                Choice("ASPECT RATIO",listOf("1:1","9:16","16:9"),e.aspect) { value -> vm.edit { it.copy(aspect=value) } }
                RetroField("SEED · blank = random",e.seed,{ value -> vm.edit("ai-seed") { it.copy(seed=value) } },number=true)
                if(e.tool!="photo") {
                    Choice("DURATION · SECONDS",listOf("2","3","6"),"${e.duration}") { value -> vm.edit { it.copy(duration=value.toInt()) } }
                    RetroSlider("MOTION PROMPT STRENGTH",e.motion,0f..1f) { value -> vm.edit("ai-motion") { it.copy(motion=value) } }
                    Text("CogVideoX renders a fixed six-second source at its native resolution. The server trims/resizes it to your selection. Motion changes the model prompt; it is not optical-flow control.",fontSize=11.sp)
                }
                RaisedButton("BACKEND SETTINGS",{ vm.screen.value="settings" })
            }
        }
        RaisedButton(if(isAI)"START GENERATION" else "START PROCESSING",{ vm.create() },Modifier.fillMaxWidth(),LocalConsole.current.pink)
        RaisedButton("SAVE UNFINISHED PROJECT",{ vm.create(true) },Modifier.fillMaxWidth())
    }
    confirmHighlights?.let { pending -> AlertDialog(
        onDismissRequest={ vm.highlightConfirmation.value=null },
        title={ PixelText("SEND SELECTED FRAMES?",size=9,color=LocalConsole.current.lcd) },
        text={ Text("Up to 24 JPEG thumbnails (256 pixels maximum edge) from clip ${pending.state.selectedClip+1}'s selected trim and your visual description will be sent to ${pending.backend}. Audio, full video and source metadata stay on this phone. The server uses CPU CLIP; results reopen this saved timeline for review. Sampled frames can still contain private content. Suggested length is capped to the trim length.") },
        confirmButton={ TextButton({ vm.semanticHighlights(confirmed=true) }) { Text("SEND FRAMES") } },
        dismissButton={ TextButton({ vm.highlightConfirmation.value=null }) { Text("CANCEL") } }
    ) }
    if(confirmTranscription)AlertDialog(
        onDismissRequest={ confirmTranscription=false },
        title={ PixelText("SEND SELECTED AUDIO?",size=9,color=LocalConsole.current.lcd) },
        text={ Text("Only trimmed audio from clip ${e.selectedClip+1} will be sent to ${prefs.backend.ifBlank { "your configured server (not set yet)" }} for speech recognition. Video frames stay on your phone. Your current timeline is saved for applying the resulting editable subtitles. Remote processing must also be allowed in Settings.") },
        confirmButton={ TextButton({ confirmTranscription=false; vm.transcribe() }) { Text("SEND AUDIO") } },
        dismissButton={ TextButton({ confirmTranscription=false }) { Text("CANCEL") } }
    )
    if(confirmFace)AlertDialog(
        onDismissRequest={ vm.faceConfirmation.value=false },
        title={ PixelText("SEND PROCESSED PHOTO?",size=9,color=LocalConsole.current.lcd) },
        text={ Text("Your locally edited${if(e.tool=="upscale")" and upscaled" else ""} photo will be sent to ${prefs.backend.ifBlank { "your configured server" }} for GFPGAN restoration of the central face. This requires the optional CPU face models. The PNG upload is limited to 12 MB. Other editing stays on your phone.") },
        confirmButton={ TextButton({ vm.faceConfirmation.value=false; vm.create(faceConfirmed=true) }) { Text("SEND PHOTO") } },
        dismissButton={ TextButton({ vm.faceConfirmation.value=false }) { Text("CANCEL") } }
    )
}
@Composable private fun ClipTimeline(vm:StudioViewModel,e:EditorState) {
    val colors=LocalConsole.current
    PixelText("CLIP TIMELINE · ${"%.2f".format(e.clips.sumOf { it.durationMs }/1000.0)}s",size=8,color=colors.accent)
    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        items(e.clips.size,key={e.clips[it].id}) { index ->
            val clip=e.clips[index]
            Column(Modifier.widthIn(min=106.dp,max=180.dp).background(if(index==e.selectedClip)colors.shell else Navy,RoundedCornerShape(5.dp)).border(2.dp,if(index==e.selectedClip)colors.lcd else colors.accent,RoundedCornerShape(5.dp)).selectable(selected=index==e.selectedClip,role=Role.Tab,onClick={ vm.selectClip(index) }).padding(10.dp)) {
                PixelText("CLIP ${index+1}",size=8)
                Text("${"%.2f".format(clip.startMs/1000.0)}–${"%.2f".format(clip.endMs/1000.0)}s",fontSize=11.sp)
            }
        }
    }
    e.clips.getOrNull(e.selectedClip)?.let { clip ->
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            RaisedButton("← MOVE",{ vm.moveClip(-1) },Modifier.weight(1f),enabled=e.selectedClip>0)
            RaisedButton("MOVE →",{ vm.moveClip(1) },Modifier.weight(1f),enabled=e.selectedClip<e.clips.lastIndex)
            RaisedButton("REMOVE",vm::removeClip,Modifier.weight(1f),colors.pink)
        }
        if(clip.durationMs>0)RetroSlider("PLAYHEAD · ${"%.3f".format(e.playheadMs/1000.0)}s",e.playheadMs.toFloat().coerceIn(clip.startMs.toFloat(),clip.endMs.toFloat()),clip.startMs.toFloat()..clip.endMs.toFloat()) { vm.seek(it.toLong()) }
    }
}
@Composable private fun PhotoAdjustments(vm:StudioViewModel,e:EditorState) {
    RetroSlider("ENHANCEMENT INTENSITY · ${(e.intensity*100).toInt()}%",e.intensity,0f..1f) { value -> vm.edit("enhance-intensity") { it.copy(intensity=value) } }
    RetroToggle(if(e.video)"Spatial video noise reduction" else "Noise reduction",e.denoise) { value -> vm.edit { it.copy(denoise=value) } }
    RetroToggle("Sharpening",e.sharpen) { value -> vm.edit { it.copy(sharpen=value) } }
    if(e.video) {
        RetroToggle("Translation stabilization",e.stabilize) { value -> vm.edit { it.copy(stabilize=value) } }
        Text("For clips up to two minutes. Measures horizontal/vertical camera motion and crops borders. Best with textured, mostly stationary backgrounds; rotation and rolling shutter are not corrected.",fontSize=11.sp,color=LocalConsole.current.accent)
    } else {
        RetroToggle("AI face restoration · server",e.faceRestore) { value -> vm.edit { it.copy(faceRestore=value) } }
        if(e.faceRestore) {
            RetroSlider("FACE RESTORATION STRENGTH",e.faceStrength,0.1f..1f) { value -> vm.edit("face-strength") { it.copy(faceStrength=value) } }
            Text("Optional GFPGAN restores the central face after local editing. Requires your self-hosted CPU server and a separate upload confirmation.",fontSize=11.sp,color=LocalConsole.current.accent)
            RaisedButton("FACE SERVER SETTINGS",{ vm.screen.value="settings" })
        }
    }
}
@Composable private fun TimeFields(vm:StudioViewModel,e:EditorState) { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { RetroField("START · sec",e.start,{ value -> vm.edit("trim-start") { it.copy(start=value) } },Modifier.weight(1f),number=true); RetroField("END · sec",e.end,{ value -> vm.edit("trim-end") { it.copy(end=value) } },Modifier.weight(1f),number=true) } }
@Composable fun RetroField(label:String,value:String,onChange:(String)->Unit,modifier:Modifier=Modifier,multiline:Boolean=false,number:Boolean=false) { OutlinedTextField(value,onChange,modifier.fillMaxWidth(),label={ Text(label,fontSize=11.sp) },singleLine=!multiline,minLines=if(multiline)3 else 1,keyboardOptions=KeyboardOptions(keyboardType=if(number)KeyboardType.Decimal else KeyboardType.Text)) }
@Composable fun Choice(label:String,choices:List<String>,selected:String,onSelect:(String)->Unit) {
    Column { Text(label,color=LocalConsole.current.accent,fontSize=11.sp); LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(choices.size) { i -> FilterChip(selected==choices[i],{ onSelect(choices[i]) },label={ Text(choices[i].ifBlank { "None" },fontSize=12.sp) }) } } }
}
