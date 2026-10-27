package studio.retro.ai.media

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile

object OfflineSpeech {
    val gate=Mutex()
    fun ensureMemory(context:Context) {
        val info=ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        require(!info.lowMemory && info.availMem>=384L*1024*1024) { "Offline speech needs at least 384 MB of free system memory. Close other apps or use server subtitles." }
    }
    /** Loads the actual model and runs the recognizer; an empty silence result is expected. */
    fun probe(directory:File) {
        Model(directory.path).use { model -> Recognizer(model,16000f).use { recognizer -> recognizer.setWords(true); recognizer.acceptWaveForm(ByteArray(3200),3200); SpeechWords.parse(recognizer.finalResult,100) } }
    }
    suspend fun transcribe(context:Context,modelDirectory:File,audio:File,output:File,progress:suspend(Int?,String)->Unit)=withContext(Dispatchers.IO) {
        progress(null,"WAITING FOR OFFLINE SPEECH PROCESSOR...")
        gate.withLock {
            currentCoroutineContext().ensureActive(); ensureMemory(context)
            require(SpeechModelStore.installed(modelDirectory.parentFile!!)) { "Import the verified English speech model in Settings first." }
            val wave=SpeechPcm.inspect(audio)
            require(wave.rate==16000 && wave.channels==1 && wave.frames<=16000*120) { "Offline speech expects mono 16 kHz audio up to two minutes." }
            val durationMs=wave.frames.toLong()*1000/16000
            progress(null,"LOADING OFFLINE ENGLISH MODEL...")
            val words=ArrayList<SpeechWord>()
            try {
                Model(modelDirectory.path).use { model ->
                    currentCoroutineContext().ensureActive()
                    Recognizer(model,16000f).use { recognizer ->
                        recognizer.setWords(true)
                        RandomAccessFile(audio,"r").use { source ->
                            source.seek(44); val buffer=ByteArray(8000); var consumed=0L
                            while(true) {
                                currentCoroutineContext().ensureActive()
                                val count=source.read(buffer); if(count<0)break
                                if(recognizer.acceptWaveForm(buffer,count))words+=SpeechWords.parse(recognizer.result,durationMs)
                                require(words.size<=10000)
                                consumed+=count; progress((consumed*99/(wave.frames*2L)).toInt(),"RECOGNIZING SPEECH ON THIS PHONE...")
                            }
                        }
                        words+=SpeechWords.parse(recognizer.finalResult,durationMs)
                    }
                }
                currentCoroutineContext().ensureActive()
                val captions=SpeechWords.captions(words)
                require(captions.isNotEmpty()) { "No English speech was detected in the selected audio. Choose a clear speech segment and review the model language." }
                output.writeText(CaptionTrack.srt(captions)); progress(100,"OFFLINE SUBTITLES READY")
            } catch(t:Throwable) { output.delete(); throw t }
        }
    }
}
