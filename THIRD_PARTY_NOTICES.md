# Third-party notices

* Press Start 2P, © 2012 The Press Start 2P Project Authors; SIL Open Font License 1.1. Full license: [licenses/PressStart2P-OFL.txt](licenses/PressStart2P-OFL.txt). The font file is unmodified.
* AndroidX, Compose, Room, DataStore, WorkManager, Media3: Apache License 2.0.
* Kotlin and Kotlin Coroutines: Apache License 2.0.
* ONNX Runtime: MIT License.
* OkHttp and Coil: Apache License 2.0.
* FastAPI: MIT; Uvicorn: BSD 3-Clause; Pillow: HPND; PyTorch: BSD-style; Diffusers / Transformers / Accelerate: Apache License 2.0.
* Gradle wrapper: Apache License 2.0; upstream wrapper JAR includes its license notices.

Model weights are not included. Real-ESRGAN uses BSD 3-Clause; BasicSR uses Apache 2.0 with additional component terms. Verify the exact checkpoint's terms. Stable Diffusion 1.5 uses CreativeML Open RAIL-M, including use restrictions. CogVideoX-2b and CogVideoX-5b-I2V have different model licenses; consult the exact repository revision and applicable commercial terms before using or redistributing. Faster-whisper is MIT; Whisper model licenses are separate. These models are optional, are not hosted by this app, and do not imply a paid inference API.

Optional GFPGAN v1.4 clean restoration uses separately installed GFPGAN, BasicSR and facexlib packages/model weights. The full upstream [GFPGAN license and third-party terms](backend/licenses/GFPGAN-LICENSE.txt) are included, with Apache/MIT/BSD and additional component terms. The app does not bundle those weights or claim that every dependency has the same license. The face-validation fixture is scikit-image's public-domain NASA astronaut photograph; the model evidence records its source.

Original pixel icons and console artwork were created for RETRO AI STUDIO. No Nintendo logos or copyrighted game artwork are included. RETRO AI STUDIO is not affiliated with Nintendo.

Optional OpenAI CLIP ViT-B/32 uses the MIT-licensed checkpoint at the revision documented in [highlight setup](docs/HIGHLIGHTS.md); [full upstream license](backend/licenses/CLIP-LICENSE.txt). Transformers is Apache 2.0. CLIP weights are downloaded explicitly by the server operator and are not bundled with Android. The CLIP validation uses the public-domain NASA astronaut photograph and original plain gray controls.

Optional offline speech uses Vosk Android 0.3.75 (Apache 2.0) and JNA 5.18.1 under its Apache 2.0 license option. The full [Apache license](app/src/main/assets/licenses/Vosk-Apache-2.0.txt) is bundled in app assets. The separately imported vosk-model-small-en-us-0.15 checkpoint is listed as Apache 2.0 by its publisher; its exact URL/hash are in [speech setup](docs/OFFLINE_SPEECH.md). Recognition fixtures are original synthetic espeak-ng speech. Model weights are not redistributed in the app.
