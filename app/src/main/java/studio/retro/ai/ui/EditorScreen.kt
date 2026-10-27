package studio.retro.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.*
import studio.retro.ai.*
import java.io.File

@Composable fun EditorScreen(vm:StudioViewModel,e:EditorState) {
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(40)) { if(it.isNotEmpty())vm.import(it,false) }
    val videoPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { if(it.isNotEmpty())vm.import(it,true) }
    val subtitlePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if(it!=null)vm.importCaptions(it) }
    val subtitleExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { if(it!=null)vm.exportCaptions(it) }
    val isAI=e.tool in setOf("photo","video","aigif")
    val title=if(e.tool=="aigif")"AI ANIMATION" else ToolNames[ToolIds.indexOf(e.tool).coerceAtLeast(0)].replace('\n',' ')
    ConsolePanel(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),lcd=true) {
        PixelText(title,color=LocalConsole.current.lcd,size=10)
        Text(if(isAI)"REMOTE AI · your self-hosted server" else if(e.tool=="upscale")"LOCAL AI · imported Real-ESRGAN ONNX model" else "OFFLINE · conventional media processing",color=LocalConsole.current.accent,fontSize=11.sp)
        if(!isAI || e.tool in setOf("photo","video")) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(e.tool!="clipper")RaisedButton(if(isAI)"INPUT IMAGE" else "IMPORT PHOTOS",{ imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },Modifier.weight(1f))
                if(!isAI && e.tool in setOf("gif","clipper","enhance"))RaisedButton("IMPORT VIDEO",{ videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },Modifier.weight(1f))
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
            TextButton({ vm.edit { it.copy(inputs=emptyList(),video=false,clips=emptyList(),selectedClip=0,suggestions=emptyList()) } }) { Text("CLEAR INPUTS") }
        }
        when(e.tool) {
            "upscale" -> {
                Choice("UPSCALE",listOf("2×","4×"),"${e.scale}×") { choice -> vm.edit { it.copy(scale=if(choice=="2×")2 else 4) } }
                Text("Uses a genuine x4 super-resolution model; 2× downsamples its result. Input is decoded to ≤1024 pixels. Output size is checked against available memory.",fontSize=12.sp)
                Text("Face restoration requires an additional model and is not included in this MVP.",fontSize=12.sp,color=LocalConsole.current.accent)
                PhotoAdjustments(vm,e)
            }
            "enhance" -> {
                Text(if(e.video)"GPU brightness, contrast, saturation, edge-aware spatial denoising, and sharpening. These are traditional algorithms. Stabilization and face restoration require additional implementations." else "Histogram exposure and contrast, gray-world white balance, saturation, edge-aware denoising, and sharpening. These are traditional algorithms.",fontSize=12.sp)
                PhotoAdjustments(vm,e)
            }
            "clipper" -> {
                ClipTimeline(vm,e)
                Text("Each block has its own trim. Export merges the visible order with synchronized audio. Left/right D-pad seeks by 1/30 second; up/down selects a block.",fontSize=12.sp)
                TimeFields(vm,e)
                Choice("CROP / RESIZE",listOf("Original","9:16","1:1","16:9"),e.aspect) { value -> vm.edit { it.copy(aspect=value) } }
                RaisedButton("SPLIT AT PLAYHEAD",vm::split,enabled=e.clips.isNotEmpty())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { RaisedButton("SCENE CUTS",{ vm.analyze(false) },Modifier.weight(1f)); RaisedButton("QUIET AUDIO",{ vm.analyze(true) },Modifier.weight(1f)) }
                if(e.suggestions.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(e.suggestions.size) { index -> TextButton({ vm.seek(e.suggestions[index]) }) { Text("${e.suggestions[index]/1000.0}s") } } }
                Text("Suggestions measure scene changes and quiet audio; they do not classify semantic highlights.",fontSize=11.sp,color=LocalConsole.current.accent)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { RaisedButton("IMPORT SRT",{ subtitlePicker.launch(arrayOf("application/x-subrip","text/plain","*/*")) },Modifier.weight(1f)); RaisedButton("EXPORT SRT",{ subtitleExport.launch("RETRO-captions.srt") },Modifier.weight(1f),enabled=e.captions.isNotBlank()) }
                RetroField("EDIT CAPTIONS · SRT or milliseconds|text",e.captions,{ value -> vm.edit { it.copy(captions=value) } },multiline=true)
                Text("Caption times refer to the exported timeline. Reordering/trimming clips does not retime captions automatically. Export validates subtitle syntax. The optional faster-whisper CLI can generate SRT input from speech.",fontSize=11.sp)
            }
            "gif" -> {
                Choice("OUTPUT",listOf("GIF","MP4"),e.format) { value -> vm.edit { it.copy(format=value) } }
                if(e.video)TimeFields(vm,e)
                RetroSlider("FRAMES PER SECOND · ${e.fps}",e.fps.toFloat(),1f..24f,22) { value -> vm.edit { it.copy(fps=value.toInt()) } }
                if(e.format=="GIF") {
                    RetroSlider("SPEED · ${e.speed}×",e.speed,0.25f..4f) { value -> vm.edit { it.copy(speed=value) } }
                    Choice("MAXIMUM EDGE · SIZE OPTIMIZATION",listOf("160","240","320","480"),"${e.width}") { value -> vm.edit { it.copy(width=value.toInt()) } }
                    RetroToggle("Loop animation",e.loop) { value -> vm.edit { it.copy(loop=value) } }
                    RetroField("CAPTION",e.caption,{ value -> vm.edit { it.copy(caption=value) } })
                    Choice("STICKER",listOf("","★","♥","✦","☀"),e.sticker) { value -> vm.edit { it.copy(sticker=value) } }
                    Text("Real animated GIF · RGB332 palette · up to 240 frames. Reduce dimensions or FPS for smaller files.",fontSize=11.sp)
                } else Text("MP4 exports preserve the trim and audio of a video, or animate images at the chosen FPS. Caption, sticker, looping, and GIF speed controls apply to GIF output.",fontSize=11.sp)
                RaisedButton("TEXT → AI ANIMATED GIF",{ vm.open("aigif") },color=LocalConsole.current.pink)
            }
            "photo","video","aigif" -> {
                Text("Prompts and any imported image are sent only when you start generation and have enabled remote processing. Requires your own compatible inference server and model hardware.",fontSize=12.sp,color=LocalConsole.current.accent)
                RetroField("PROMPT >",e.prompt,{ value -> vm.edit { it.copy(prompt=value) } },multiline=true)
                if(e.tool=="photo")RetroField("NEGATIVE PROMPT >",e.negative,{ value -> vm.edit { it.copy(negative=value) } },multiline=true)
                Choice("STYLE",if(e.tool=="photo")PhotoStyles else VideoStyles,e.style) { value -> vm.edit { it.copy(style=value) } }
                Choice("ASPECT RATIO",listOf("1:1","9:16","16:9"),e.aspect) { value -> vm.edit { it.copy(aspect=value) } }
                RetroField("SEED · blank = random",e.seed,{ value -> vm.edit { it.copy(seed=value) } },number=true)
                if(e.tool!="photo") {
                    Choice("DURATION · SECONDS",listOf("2","3","6"),"${e.duration}") { value -> vm.edit { it.copy(duration=value.toInt()) } }
                    RetroSlider("MOTION PROMPT STRENGTH",e.motion,0f..1f) { value -> vm.edit { it.copy(motion=value) } }
                    Text("CogVideoX renders a fixed six-second source at its native resolution. The server trims/resizes it to your selection. Motion changes the model prompt; it is not optical-flow control.",fontSize=11.sp)
                }
                RaisedButton("BACKEND SETTINGS",{ vm.screen.value="settings" })
            }
        }
        RaisedButton(if(isAI)"START GENERATION" else "START PROCESSING",{ vm.create() },Modifier.fillMaxWidth(),LocalConsole.current.pink)
        RaisedButton("SAVE UNFINISHED PROJECT",{ vm.create(true) },Modifier.fillMaxWidth())
    }
}
@Composable private fun ClipTimeline(vm:StudioViewModel,e:EditorState) {
    val colors=LocalConsole.current
    PixelText("CLIP TIMELINE · ${"%.2f".format(e.clips.sumOf { it.durationMs }/1000.0)}s",size=8,color=colors.accent)
    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        items(e.clips.size,key={e.clips[it].id}) { index ->
            val clip=e.clips[index]
            Column(Modifier.widthIn(min=106.dp,max=180.dp).background(if(index==e.selectedClip)colors.shell else Navy,RoundedCornerShape(5.dp)).border(2.dp,if(index==e.selectedClip)colors.lcd else colors.accent,RoundedCornerShape(5.dp)).clickable { vm.selectClip(index) }.padding(10.dp)) {
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
    RetroSlider("ENHANCEMENT INTENSITY · ${(e.intensity*100).toInt()}%",e.intensity,0f..1f) { value -> vm.edit { it.copy(intensity=value) } }
    RetroToggle(if(e.video)"Spatial video noise reduction" else "Noise reduction",e.denoise) { value -> vm.edit { it.copy(denoise=value) } }
    RetroToggle("Sharpening",e.sharpen) { value -> vm.edit { it.copy(sharpen=value) } }
}
@Composable private fun TimeFields(vm:StudioViewModel,e:EditorState) { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { RetroField("START · sec",e.start,{ value -> vm.edit { it.copy(start=value) } },Modifier.weight(1f),number=true); RetroField("END · sec",e.end,{ value -> vm.edit { it.copy(end=value) } },Modifier.weight(1f),number=true) } }
@Composable fun RetroField(label:String,value:String,onChange:(String)->Unit,modifier:Modifier=Modifier,multiline:Boolean=false,number:Boolean=false) { OutlinedTextField(value,onChange,modifier.fillMaxWidth(),label={ Text(label,fontSize=11.sp) },singleLine=!multiline,minLines=if(multiline)3 else 1,keyboardOptions=KeyboardOptions(keyboardType=if(number)KeyboardType.Decimal else KeyboardType.Text)) }
@Composable fun Choice(label:String,choices:List<String>,selected:String,onSelect:(String)->Unit) {
    Column { Text(label,color=LocalConsole.current.accent,fontSize=11.sp); LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(choices.size) { i -> FilterChip(selected==choices[i],{ onSelect(choices[i]) },label={ Text(choices[i].ifBlank { "None" },fontSize=12.sp) }) } } }
}
