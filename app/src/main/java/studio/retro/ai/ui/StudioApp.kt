package studio.retro.ai.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.retro.ai.StudioViewModel

val ToolIds=listOf("upscale","video","photo","clipper","enhance","gif")
val ToolNames=listOf("AI PHOTO\nUPSCALER","AI VIDEO\nGENERATOR","AI PHOTO\nGENERATOR","AI\nCLIPPER","AUTO\nENHANCE","AI GIF\nGENERATOR")
val PhotoStyles=listOf("Photorealistic","Anime","Pixel Art","Cartoon","Fantasy","Cyberpunk","Retro Game Art","Oil Painting","Comic Book","Cinematic")
val VideoStyles=listOf("Cinematic","Anime","Retro VHS","Pixel Art","Cartoon","Fantasy","Horror","Realistic","Sci-Fi","1990s Camcorder")

@Composable fun StudioApp(vm:StudioViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val route by vm.screen.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val busyMessage by vm.busyMessage.collectAsStateWithLifecycle()
    val busyPercent by vm.busyPercent.collectAsStateWithLifecycle()
    val transferActive by vm.transferActive.collectAsStateWithLifecycle()
    val backendReport by vm.backendReport.collectAsStateWithLifecycle()
    val drafts by vm.automaticDrafts.collectAsStateWithLifecycle()
    var selected by remember { mutableIntStateOf(0) }
    val haptic=LocalHapticFeedback.current
    val tone=remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC,25) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    val feedback={ if(prefs.haptics)haptic.performHapticFeedback(HapticFeedbackType.LongPress); if(prefs.sound)tone?.startTone(ToneGenerator.TONE_PROP_BEEP,45); Unit }
    BackHandler(route!="home") { vm.back() }
    RetroTheme(prefs.theme) {
        val c=LocalConsole.current
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.shell,c.shell.copy(red=(c.shell.red*0.7f))))).safeDrawingPadding().imePadding().padding(horizontal=16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PixelText("RETRO AI",size=16)
                    PixelText("STUDIO",size=12,color=c.accent)
                    Text("POWER UP YOUR CREATIVITY",color=Cream.copy(alpha=0.75f),fontSize=9.sp,letterSpacing=1.2.sp,modifier=Modifier.padding(top=5.dp))
                }
                IconButton({ vm.screen.value="settings" },Modifier.background(Navy,RoundedCornerShape(8.dp))) { Icon(Icons.Default.Settings,"Settings cartridge",tint=c.accent) }
            }
            val busy=importing || projects.any { it.status in setOf("queued","processing") }
            Row(Modifier.fillMaxWidth().padding(bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                PixelText(if(busy)"▰▱▱ PROCESSING" else "▰▰▰ READY",color=if(busy)c.pink else c.lcd,size=7)
                Text(if(prefs.backend.isBlank())"OFFLINE MODE" else if(backendReport?.optString("_origin")==prefs.backend)"SERVER CHECKED" else "SERVER CONFIGURED",color=Cream,fontSize=9.sp)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when(route) {
                    "home" -> Home(selected,drafts.isNotEmpty(),{ drafts.firstOrNull()?.let { vm.restoreDraft(it.id) } }) { i -> selected=i; feedback(); vm.open(ToolIds[i]) }
                    "editor" -> EditorScreen(vm,editor)
                    "projects" -> ProjectsScreen(vm,false)
                    "gallery" -> ProjectsScreen(vm,true)
                    "settings" -> SettingsScreen(vm)
                    "processing" -> ProcessingScreen(vm)
                }
                if(importing) Box(Modifier.fillMaxSize().background(Screen.copy(alpha=0.93f)).clickable(enabled=true,onClick={}),contentAlignment=Alignment.Center) { ConsolePanel { PixelProgress(busyPercent,busyMessage); if(transferActive)RaisedButton("CANCEL TRANSFER",vm::cancelTransfer,color=c.pink) } }
            }
            ConsoleControls(
                onMove={ delta -> feedback(); if(route=="home")selected=(selected+delta+6)%6 else if(route=="editor" && editor.tool=="clipper") { if(kotlin.math.abs(delta)==1)vm.seek(editor.playheadMs+delta*33L) else vm.selectClip((editor.selectedClip+if(delta>0)1 else -1).coerceIn(0,(editor.clips.size-1).coerceAtLeast(0))) } },
                onA={ feedback(); when(route) { "home"->vm.open(ToolIds[selected]); "editor"->vm.create(); "processing"->vm.screen.value="projects"; else->vm.back() } },
                onB={
                    feedback()
                    val running=if(route=="processing")projects.firstOrNull { it.id==vm.active.value && it.status in setOf("queued","processing") } else null
                    if(running!=null)vm.cancel(running) else vm.back()
                },
                onStart={ feedback(); vm.screen.value="home" },
                onSelect={ feedback(); vm.screen.value="projects" }
            )
        }
        if(message.isNotBlank()) AlertDialog(onDismissRequest={ vm.message.value="" },title={ PixelText("CONSOLE NOTICE",color=c.lcd) },text={ Text(message) },confirmButton={ TextButton({ vm.message.value="" }) { Text("A · OK") } })
    }
}
@Composable private fun Home(selected:Int,hasDraft:Boolean,onContinue:()->Unit,onOpen:(Int)->Unit) {
    val c=LocalConsole.current
    ConsolePanel(Modifier.fillMaxSize(),lcd=true) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { PixelText("CHOOSE YOUR POWER",color=c.lcd,size=8); Text("06",color=c.accent,fontSize=12.sp) }
        LazyVerticalGrid(GridCells.Fixed(2),Modifier.weight(1f).testTag("feature-cartridges"),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(6) { i ->
                val accent=listOf(c.accent,c.pink,c.lcd,Color(0xFFE9BD70),c.lcd,c.pink)[i]
                Column(Modifier.fillMaxWidth().heightIn(min=124.dp).border(if(selected==i)3.dp else 1.dp,if(selected==i)accent else accent.copy(alpha=0.25f),RoundedCornerShape(6.dp)).background(Navy,RoundedCornerShape(6.dp)).clickable { onOpen(i) }.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { PixelIcon(i,accent); Text("0${i+1}",color=accent.copy(alpha=0.6f),fontSize=10.sp) }
                    PixelText(ToolNames[i],color=Cream,size=8)
                    Text(if(i==1 || i==2)"SELF-HOSTED AI" else if(i==0)"ON-DEVICE MODEL" else "OFFLINE READY",color=accent,fontSize=8.sp)
                }
            }
        }
        if(hasDraft)RaisedButton("CONTINUE LAST DRAFT",onContinue,Modifier.fillMaxWidth(),c.pink)
        Text("D-PAD TO EXPLORE  •  A TO ENTER",color=c.accent,fontSize=9.sp)
    }
}
