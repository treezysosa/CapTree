package studio.retro.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import studio.retro.ai.StudioViewModel
import studio.retro.ai.data.Project
import java.io.File
import java.util.UUID

@Composable fun ProjectsScreen(vm:StudioViewModel,gallery:Boolean) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("All") }
    var opened by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<Project?>(null) }
    var newName by remember { mutableStateOf("") }
    val visible=projects.filter { (!gallery || it.status=="completed") && when(filter) { "Photos"->it.mime.startsWith("image/") && it.mime!="image/gif"; "Videos"->it.mime.startsWith("video/"); "GIFs"->it.mime=="image/gif"; "Favorites"->it.favorite; else->true } }
    ConsolePanel(Modifier.fillMaxSize(),lcd=true) {
        PixelText(if(gallery)"PIXEL GALLERY" else "SAVE GAME MENU",color=LocalConsole.current.lcd)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { RaisedButton("PROJECTS",{ vm.screen.value="projects" },Modifier.weight(1f)); RaisedButton("GALLERY",{ vm.screen.value="gallery" },Modifier.weight(1f)) }
        Choice("FILTER",listOf("All","Photos","Videos","GIFs","Favorites"),filter) { filter=it }
        if(visible.isEmpty())Text("Your save slots are empty. Press START to create something.",fontSize=14.sp)
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(visible,key={it.id}) { p ->
                ConsolePanel(Modifier.fillMaxWidth()) {
                    Text(p.name,color=LocalConsole.current.accent,fontSize=15.sp)
                    Text("${p.tool.uppercase()} · ${p.status.uppercase()}${if(p.favorite)" · ★" else ""}",fontSize=10.sp)
                    if(p.error.isNotBlank())Text(p.error,color=LocalConsole.current.pink,fontSize=12.sp)
                    if(p.status=="completed") {
                        RaisedButton(if(opened==p.id)"CLOSE PREVIEW" else "OPEN CREATION",{ opened=if(opened==p.id)null else p.id })
                        if(opened==p.id)ResultActions(vm,p)
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) { TextButton({ vm.resume(p) }) { Text("RESUME",fontSize=10.sp) }; TextButton({ rename=p; newName=p.name }) { Text("RENAME",fontSize=10.sp) }; TextButton({ vm.favorite(p) }) { Text(if(p.favorite)"UNFAVORITE" else "FAVORITE",fontSize=10.sp) } }
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) { TextButton({ vm.duplicate(p) },enabled=p.status !in setOf("queued","processing")) { Text("DUPLICATE",fontSize=10.sp) }; TextButton({ vm.delete(p) }) { Text("DELETE",fontSize=10.sp) }; if(p.status in setOf("queued","processing"))TextButton({ vm.active.value=p.id; vm.screen.value="processing" }) { Text("STATUS",fontSize=10.sp) } }
                }
            }
        }
    }
    rename?.let { p -> AlertDialog(onDismissRequest={ rename=null },title={ PixelText("RENAME SAVE",size=9) },text={ RetroField("Name",newName,{ newName=it }) },confirmButton={ TextButton({ vm.rename(p,newName); rename=null }) { Text("SAVE") } },dismissButton={ TextButton({ rename=null }) { Text("CANCEL") } }) }
}
@Composable fun ProcessingScreen(vm:StudioViewModel) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val id by vm.active.collectAsStateWithLifecycle()
    val p=projects.firstOrNull { it.id==id }
    var info by remember(p?.workId) { mutableStateOf<WorkInfo?>(null) }
    LaunchedEffect(p?.workId) { p?.workId?.takeIf { it.isNotBlank() }?.let { vm.work.getWorkInfoByIdFlow(UUID.fromString(it)).collectLatest { info=it } } }
    ConsolePanel(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),lcd=true) {
        PixelText("CREATION STATION",color=LocalConsole.current.lcd)
        if(p==null)Text("Loading saved project…") else {
            Text(p.name)
            when(p.status) {
                "queued","processing" -> {
                    val percent=info?.progress?.getInt("percent",-1)?.takeIf { it>=0 }
                    PixelProgress(percent,info?.progress?.getString("message") ?: if(p.status=="queued")"WAITING FOR PROCESSOR..." else "LOADING CREATIVE POWER...")
                    Text("Processing continues in the background. Jobs are limited to Android WorkManager's execution window; remote generation times out after eight minutes.",fontSize=12.sp)
                    RaisedButton("B · CANCEL",{ vm.cancel(p) },color=LocalConsole.current.pink)
                }
                "completed" -> { PixelText("CREATION SAVED!",color=LocalConsole.current.lcd); ResultActions(vm,p) }
                else -> { Text(p.error.ifBlank { p.status.uppercase() },color=LocalConsole.current.pink); RaisedButton("RETRY AS NEW PROJECT",{ vm.retry(p) }); RaisedButton("EDIT INPUTS",{ vm.resume(p) }) }
            }
        }
    }
}
@Composable private fun ResultActions(vm:StudioViewModel,p:Project) {
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(p.mime.ifBlank { "application/octet-stream" })) { uri -> if(uri!=null)vm.export(p,uri) }
    var comparison by remember(p.id) { mutableStateOf("After") }
    val source=remember(p.recipe) { runCatching { JSONObject(p.recipe).optJSONArray("inputs")?.optString(0) }.getOrNull() }
    if(p.tool in setOf("enhance","upscale") && !source.isNullOrBlank()) {
        Choice("COMPARISON",if(p.mime.startsWith("video/"))listOf("Before","After") else listOf("Before","After","Wipe"),comparison) { comparison=it }
    }
    if(comparison=="Wipe" && source!=null)PhotoComparison(source,p.output) else MediaPreview(if(comparison=="Before" && source!=null)source else p.output,p.mime)
    Text("${File(p.output).length()/1024} KB · ${p.mime}",fontSize=11.sp,color=LocalConsole.current.accent)
    if(p.tool in setOf("photo","video","aigif"))Text("SEED · ${runCatching { JSONObject(p.recipe).optLong("seed",-1) }.getOrDefault(-1)}",fontSize=11.sp,color=LocalConsole.current.accent)
    if(android.os.Build.VERSION.SDK_INT>=29)RaisedButton("SAVE TO GALLERY",{ vm.saveGallery(p) },Modifier.fillMaxWidth())
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { RaisedButton("EXPORT FILE",{ export.launch("RETRO_${p.id}.${File(p.output).extension}") },Modifier.weight(1f)); RaisedButton("SHARE",{ vm.share(p) },Modifier.weight(1f),LocalConsole.current.pink) }
}
