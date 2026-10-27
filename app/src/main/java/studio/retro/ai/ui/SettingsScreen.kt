package studio.retro.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.retro.ai.StudioViewModel
import studio.retro.ai.data.BackendSecrets
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.retro.ai.data.BackendClient
import studio.retro.ai.media.SpeechModelStore
import androidx.compose.ui.platform.LocalUriHandler

@Composable fun SettingsScreen(vm:StudioViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var url by remember(prefs.backend) { mutableStateOf(prefs.backend) }
    var token by remember { mutableStateOf("") }
    var modelInstalled by remember { mutableStateOf(File(context.filesDir,"models/realesrgan-x4.onnx").exists()) }
    val importing by vm.importing.collectAsStateWithLifecycle()
    val modelReport by vm.modelReport.collectAsStateWithLifecycle()
    val backendReport by vm.backendReport.collectAsStateWithLifecycle()
    LaunchedEffect(importing) { if(!importing) {
        modelInstalled=File(context.filesDir,"models/realesrgan-x4.onnx").exists()
        vm.modelReport.value=withContext(Dispatchers.IO) { File(context.filesDir,"models/validation.txt").takeIf { it.exists() }?.readText().orEmpty() }
    } }
    val model=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null)vm.model(uri) }
    val speechModel=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null)vm.speechModel(uri) }
    val speechInstalled by vm.speechInstalled.collectAsStateWithLifecycle()
    val uriHandler=LocalUriHandler.current
    ConsolePanel(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),lcd=true) {
        PixelText("CONSOLE SETTINGS",color=LocalConsole.current.lcd)
        Choice("SHELL THEME",listOf("Classic Purple","Glacier Blue","Retro Green"),prefs.theme) { value -> vm.launch { vm.settings.theme(value) } }
        RetroToggle("Haptic feedback",prefs.haptics) { value -> vm.launch { vm.settings.haptics(value) } }
        RetroToggle("Button sounds",prefs.sound) { value -> vm.launch { vm.settings.sound(value) } }
        Choice("PHOTO EXPORT · 100 = LOSSLESS PNG",listOf("80","92","100"),"${prefs.quality}") { value -> vm.launch { vm.settings.quality(value.toInt()) } }
        PixelText("AI MODEL CARTRIDGE",size=9,color=LocalConsole.current.accent)
        Text(if(modelInstalled)"ONNX model installed. Run a device test to check its current inference compatibility." else "No model installed. Upscaling needs a compatible Real-ESRGAN RGB float32 NCHW x4 ONNX model.",fontSize=12.sp)
        RaisedButton("IMPORT ONNX MODEL",{ model.launch(arrayOf("application/octet-stream","application/onnx","*/*")) })
        RaisedButton("RUN ON-DEVICE MODEL TEST",vm::testModel,enabled=modelInstalled && !importing)
        if(modelInstalled && modelReport.isNotBlank())Text(modelReport,fontSize=10.sp,color=LocalConsole.current.accent)
        Text("Model weights are not bundled. See docs/MODELS.md for export commands, tensor format, attribution, and memory limits. Optional GFPGAN face restoration uses separately configured models on your CPU server.",fontSize=12.sp)
        PixelText("OFFLINE SPEECH CARTRIDGE",size=9,color=LocalConsole.current.accent)
        Text(if(speechInstalled)"English speech model installed and verified." else "No offline speech model. Optional English subtitles run on your phone after model import.",fontSize=12.sp)
        Text("Vosk small English · Apache 2.0 · 40 MB download. Keep 256 MB storage free for installation and at least 384 MB system memory available for inference. Supports up to two minutes per selected block; captions need review. Other languages remain available through server subtitles.",fontSize=12.sp)
        RaisedButton("GET ENGLISH SPEECH MODEL",{ uriHandler.openUri(SpeechModelStore.MODEL_URL) },enabled=!importing)
        RaisedButton("IMPORT ENGLISH MODEL ZIP",{ speechModel.launch(arrayOf("application/zip","application/octet-stream","*/*")) },enabled=!importing)
        RaisedButton("REMOVE SPEECH MODEL",vm::removeSpeechModel,enabled=speechInstalled && !importing)
        Text("Download opens in your browser. Import checks the exact official archive and tests the real recognizer before installation. The app never downloads speech weights automatically. Native speech decoding runs locally and needs no microphone permission.",fontSize=12.sp)
        PixelText("OPTIONAL AI SERVER",size=9,color=LocalConsole.current.accent)
        RetroField("HTTPS BACKEND URL",url,{ url=it })
        OutlinedTextField(token,{ token=it },Modifier.fillMaxWidth(),label={ Text("Replace server token (blank keeps current)") },singleLine=true,visualTransformation=PasswordVisualTransformation())
        RaisedButton("SAVE CONNECTION",{ vm.launch { if(url.isNotBlank())BackendClient(url,""); if(token.isNotBlank())BackendSecrets(context).save(token); token=""; vm.settings.backend(url); vm.message.value="Connection saved. Review and enable remote processing below." } })
        RaisedButton("TEST BACKEND",vm::testBackend,enabled=prefs.backend.isNotBlank())
        backendReport?.takeIf { it.optString("_origin")==prefs.backend }?.optJSONArray("features")?.let { features ->
            for(index in 0 until features.length()) {
                val feature=features.getJSONObject(index)
                Text("${feature.optString("id")} · ${feature.optString("state").replace('_',' ').uppercase()}",fontSize=12.sp,color=if(feature.optBoolean("supported"))LocalConsole.current.lcd else LocalConsole.current.pink)
                Text(feature.optString("reason"),fontSize=11.sp)
            }
        }
        RetroToggle("Allow remote AI processing",prefs.remoteConsent) { value -> vm.launch { require(!value || prefs.backend.isNotBlank()) { "Save a backend URL first." }; vm.settings.consent(value) } }
        Text("Starting remote generation sends your prompt and optional image to this server. Subtitles send selected audio, and optional face restoration sends the processed photo, each after an additional confirmation. Offline editing keeps media on your phone. There are no accounts, ads, tracking SDKs, paid APIs, credits, or watermarks.",fontSize=12.sp)
        Text("Advanced models need powerful hardware; your server may incur hardware and electricity costs. HTTPS with a valid certificate is required. Backend access tokens are encrypted with Android Keystore.",fontSize=12.sp)
        RaisedButton("DISCONNECT & REMOVE TOKEN",{ vm.launch { BackendSecrets(context).save(""); vm.settings.backend(""); url=""; token=""; vm.message.value="Backend disconnected." } })
        PixelText("STORAGE & PRIVACY",size=9,color=LocalConsole.current.accent)
        Text("Automatic drafts keep editor values and undo/redo in private storage. Restore or discard them in Save Games → Autosaves. Cleanup keeps media referenced by those drafts. They do not survive uninstall; KEEP AS PROJECT and export a portable backup for that.",fontSize=12.sp)
        Text("Imports and projects live in app-private storage. Exports are created only on your command. Android Photo Picker grants access only to selected files. Use BACKUP PROJECT in Save Games before uninstalling; restore it with IMPORT PROJECT BACKUP. Backups include your media and captions but exclude server credentials and model weights.",fontSize=12.sp)
        RaisedButton("CLEAR CACHE & UNUSED IMPORTS",vm::clean)
        RaisedButton("REMOVE AI MODEL",{ vm.launch { require(!importing && vm.projects.value.none { it.status in setOf("queued","processing") }) { "Wait for processing to finish." }; File(context.filesDir,"models/realesrgan-x4.onnx").delete(); File(context.filesDir,"models/validation.txt").delete(); modelInstalled=false; vm.modelReport.value=""; vm.message.value="Model removed." } })
        PixelText("ABOUT · v${studio.retro.ai.BuildConfig.VERSION_NAME}",size=9,color=LocalConsole.current.accent)
        Text("RETRO AI STUDIO\nAn original handheld-inspired creation studio. Android 8+ · Kotlin / Compose.\nPress Start 2P © its authors, SIL Open Font License 1.1. Media3 / AndroidX: Apache 2.0. ONNX Runtime: MIT.\nModel licenses are separate; see THIRD_PARTY_NOTICES.md. Not affiliated with Nintendo.",fontSize=12.sp)
        RaisedButton("OPEN SAVE GAMES",{ vm.screen.value="projects" })
    }
}
