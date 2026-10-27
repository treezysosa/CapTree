package studio.retro.ai.media

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Only the verified small English checkpoint is accepted. No automatic model downloads. */
object SpeechModelStore {
    const val MODEL_ID="vosk-model-small-en-us-0.15"
    const val MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val MODEL_BYTES=41205931L
    const val SHA256="30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    private val required=setOf("am/final.mdl","graph/disambig_tid.int","graph/HCLr.fst","graph/Gr.fst","graph/phones/word_boundary.int","conf/model.conf","conf/mfcc.conf","ivector/splice.conf","ivector/final.dubm","ivector/global_cmvn.stats","ivector/final.ie","ivector/online_cmvn.conf","ivector/final.mat","README")
    fun directory(modelsRoot:File)=File(modelsRoot,"vosk-en-small")
    private fun valid(folder:File)=runCatching { File(folder,"verified.sha256").readText()==SHA256 && required.all { File(folder,it).let { file -> file.isFile && file.length()>0 } } }.getOrDefault(false)
    fun installed(modelsRoot:File)=valid(directory(modelsRoot))
    private fun owned(file:File,prefix:String)=file.name.startsWith(prefix) && runCatching { UUID.fromString(file.name.removePrefix(prefix)).toString()==file.name.removePrefix(prefix) }.getOrDefault(false)
    /** Called before new work starts; restores an old model if a process died during replacement. */
    fun recover(modelsRoot:File):List<File> {
        val folders=modelsRoot.listFiles().orEmpty().filter { it.isDirectory && it.canonicalFile.parentFile==modelsRoot.canonicalFile }
        if(!directory(modelsRoot).exists())folders.filter { owned(it,"speech-old-") && valid(it) }.maxByOrNull { it.lastModified() }?.renameTo(directory(modelsRoot))
        return folders.filter { it.exists() && (owned(it,"speech-install-") || owned(it,"speech-old-")) }
    }
    fun install(input:InputStream,modelsRoot:File,probe:(File)->Unit,check:()->Unit={},progress:(Int?,String)->Unit={_,_->}) {
        modelsRoot.mkdirs()
        require(modelsRoot.usableSpace>=256L*1024*1024) { "Keep at least 256 MB free to install the speech model." }
        val staging=File(modelsRoot,"speech-install-${UUID.randomUUID()}").apply { mkdirs() }
        val archive=File(staging,"model.zip"); val backup=File(modelsRoot,"speech-old-${UUID.randomUUID()}")
        var moved=false
        try {
            val hash=MessageDigest.getInstance("SHA-256"); var bytes=0L
            archive.outputStream().use { output ->
                val buffer=ByteArray(65536)
                while(true) { check(); val count=input.read(buffer); if(count<0)break; bytes+=count; require(bytes<=64L*1024*1024) { "Speech model ZIP exceeds 64 MB." }; hash.update(buffer,0,count); output.write(buffer,0,count); progress((bytes*35/MODEL_BYTES).toInt().coerceAtMost(35),"VERIFYING ENGLISH SPEECH MODEL...") }
            }
            require(bytes==MODEL_BYTES && hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }==SHA256) { "Choose the original vosk-model-small-en-us-0.15.zip. Its size/SHA-256 must match the verified English model." }
            val prepared=extract(archive,staging,check) { progress(35+it*55/100,"UNPACKING SPEECH MODEL...") }
            check(); progress(null,"TESTING SPEECH MODEL ON THIS DEVICE..."); probe(prepared); check()
            File(prepared,"verified.sha256").writeText(SHA256)
            val current=directory(modelsRoot)
            if(current.exists())require(current.renameTo(backup)) { "Cannot replace the existing speech model." }
            if(!prepared.renameTo(current)) {
                if(backup.exists())backup.renameTo(current)
                error("Cannot install the speech model.")
            }
            moved=true; progress(100,"OFFLINE SPEECH MODEL INSTALLED")
        } finally {
            if(moved)backup.deleteRecursively()
            staging.deleteRecursively()
        }
    }
    internal fun extract(archive:File,staging:File,check:()->Unit={},progress:(Int)->Unit={}):File {
        val target=File(staging,MODEL_ID)
        ZipFile(archive).use { zip ->
            val entries=zip.entries().asSequence().toList()
            require(entries.size in 1..100 && entries.map { it.name }.distinct().size==entries.size) { "Invalid speech model archive entries." }
            var total=0L
            for(entry in entries) {
                val name=entry.name
                require(name.startsWith("$MODEL_ID/") && !name.startsWith('/') && name.split('/').none { it==".." || it=="." } && !name.contains('\\')) { "Unsafe model archive path." }
                val relative=name.removePrefix("$MODEL_ID/")
                require(if(entry.isDirectory)relative.isEmpty() || required.any { it.startsWith(relative) } else relative in required) { "Unsupported speech model file." }
                if(!entry.isDirectory) { require(entry.size in 1..96L*1024*1024); total+=entry.size; require(total<=160L*1024*1024) { "Speech model exceeds the extraction limit." } }
            }
            require(entries.filter { !it.isDirectory }.map { it.name.removePrefix("$MODEL_ID/") }.toSet()==required) { "Incomplete English speech model." }
            var done=0L
            for(entry in entries) {
                check(); val output=File(staging,entry.name).canonicalFile
                require(output.path.startsWith(staging.canonicalPath+File.separator))
                if(entry.isDirectory)output.mkdirs() else {
                    output.parentFile!!.mkdirs(); var count=0L
                    zip.getInputStream(entry).use { source -> output.outputStream().use { sink ->
                        val buffer=ByteArray(65536)
                        while(true) { check(); val n=source.read(buffer); if(n<0)break; count+=n; require(count<=entry.size); sink.write(buffer,0,n); done+=n; progress((done*100/total).toInt()) }
                    } }
                    require(count==entry.size)
                }
            }
        }
        return target
    }
}
