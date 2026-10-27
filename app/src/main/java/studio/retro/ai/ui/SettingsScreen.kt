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

@Composable fun SettingsScreen(vm:StudioViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var url by remember(prefs.backend) { mutableStateOf(prefs.backend) }
    var token by remember { mutableStateOf("") }
    var modelInstalled by remember { mutableStateOf(File(context.filesDir,"models/realesrgan-x4.onnx").exists()) }
    val model=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) { vm.model(uri); modelInstalled=true } }
    ConsolePanel(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),lcd=true) {
        PixelText("CONSOLE SETTINGS",color=LocalConsole.current.lcd)
        Choice("SHELL THEME",listOf("Classic Purple","Glacier Blue","Retro Green"),prefs.theme) { value -> vm.launch { vm.settings.theme(value) } }
        RetroToggle("Haptic feedback",prefs.haptics) { value -> vm.launch { vm.settings.haptics(value) } }
        RetroToggle("Button sounds",prefs.sound) { value -> vm.launch { vm.settings.sound(value) } }
        Choice("PHOTO EXPORT · 100 = LOSSLESS PNG",listOf("80","92","100"),"${prefs.quality}") { value -> vm.launch { vm.settings.quality(value.toInt()) } }
        PixelText("AI MODEL CARTRIDGE",size=9,color=LocalConsole.current.accent)
        Text(if(modelInstalled)"ONNX model file present. Compatibility is verified during processing." else "No model installed. Upscaling needs a Real-ESRGAN RGB float32 NCHW x4 ONNX model.",fontSize=12.sp)
        RaisedButton("IMPORT ONNX MODEL",{ model.launch(arrayOf("application/octet-stream","application/onnx","*/*")) })
        Text("Model weights are not bundled. See docs/MODELS.md for export commands, tensor format, attribution, and memory limits. Face restoration needs a separate compatible model and is unavailable here.",fontSize=12.sp)
        PixelText("OPTIONAL AI SERVER",size=9,color=LocalConsole.current.accent)
        RetroField("HTTPS BACKEND URL",url,{ url=it })
        OutlinedTextField(token,{ token=it },Modifier.fillMaxWidth(),label={ Text("Replace server token (blank keeps current)") },singleLine=true,visualTransformation=PasswordVisualTransformation())
        RaisedButton("SAVE CONNECTION",{ vm.launch { require(url.isBlank() || url.startsWith("https://")) { "HTTPS is required." }; if(token.isNotBlank())BackendSecrets(context).save(token); token=""; vm.settings.backend(url); vm.message.value="Connection saved. Review and enable remote processing below." } })
        RaisedButton("TEST BACKEND",vm::testBackend,enabled=prefs.backend.isNotBlank())
        RetroToggle("Allow remote AI processing",prefs.remoteConsent) { value -> vm.launch { require(!value || prefs.backend.isNotBlank()) { "Save a backend URL first." }; vm.settings.consent(value) } }
        Text("When enabled, starting an AI generation uploads your prompt and optional input image to the server shown above. Local editing never uploads media. There are no accounts, ads, tracking SDKs, paid APIs, credits, or watermarks.",fontSize=12.sp)
        Text("Advanced models need powerful hardware; your server may incur hardware and electricity costs. HTTPS with a valid certificate is required. Backend access tokens are encrypted with Android Keystore.",fontSize=12.sp)
        RaisedButton("DISCONNECT & REMOVE TOKEN",{ vm.launch { BackendSecrets(context).save(""); vm.settings.backend(""); url=""; token=""; vm.message.value="Backend disconnected." } })
        PixelText("STORAGE & PRIVACY",size=9,color=LocalConsole.current.accent)
        Text("Imports and projects live in app-private storage. Exports are created only on your command. Android Photo Picker grants access only to selected files. Uninstalling removes private projects.",fontSize=12.sp)
        RaisedButton("CLEAR CACHE & UNUSED IMPORTS",vm::clean)
        RaisedButton("REMOVE AI MODEL",{ vm.launch { require(vm.projects.value.none { it.status in setOf("queued","processing") }) { "Wait for processing to finish." }; File(context.filesDir,"models/realesrgan-x4.onnx").delete(); modelInstalled=false; vm.message.value="Model removed." } })
        PixelText("ABOUT · v0.1.0",size=9,color=LocalConsole.current.accent)
        Text("RETRO AI STUDIO\nAn original handheld-inspired creation studio. Android 8+ · Kotlin / Compose.\nPress Start 2P © its authors, SIL Open Font License 1.1. Media3 / AndroidX: Apache 2.0. ONNX Runtime: MIT.\nModel licenses are separate; see THIRD_PARTY_NOTICES.md. Not affiliated with Nintendo.",fontSize=12.sp)
        RaisedButton("OPEN SAVE GAMES",{ vm.screen.value="projects" })
    }
}
