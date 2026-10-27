package studio.retro.ai.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable media/recipe archives. Files are committed only after every asset is verified. */
object ProjectArchive {
    const val MAX_FILE=512L*1024*1024
    const val MAX_TOTAL=1024L*1024*1024
    private const val MAX_METADATA=2*1024*1024
    private val tools=setOf("upscale","video","photo","clipper","enhance","gif","aigif","transcribe","highlights")
    private val mimes=setOf("image/jpeg","image/png","image/webp","image/gif","video/mp4","application/x-subrip","application/json")
    private val extensions=setOf("jpg","jpeg","png","webp","gif","mp4","mov","mkv","webm","srt","json")
    private val scalars=setOf("tool","video","start","end","fps","speed","width","loop","caption","sticker","format","scale","intensity","denoise","sharpen","prompt","negative","style","aspect","seed","duration","motion","captions","transcription_language","selected_clip","stabilize","gif_palette","gif_dither","face_restore","face_strength","face_restoration_applied","transcribe_index","caption_offset","quality","highlight_prompt","highlight_clip_ms","highlight_index","speech_engine","speech_model","speech_model_sha256")
    private data class Asset(val key:String,val file:File,val size:Long,val hash:String,val crc:Long)
    fun interruptedStaging(filesRoot:File):List<File> {
        val directory=File(filesRoot,"restore-temp").canonicalFile
        return directory.listFiles().orEmpty().filter { file ->
            file.isDirectory && runCatching { UUID.fromString(file.name).toString()==file.name && file.canonicalFile.parentFile==directory }.getOrDefault(false)
        }
    }

    private fun recipe(source:JSONObject,map:(String)->String):JSONObject=JSONObject().apply {
        for(key in scalars)if(source.has(key)) {
            val value=source.get(key)
            require(value is String || value is Number || value is Boolean) { "Invalid project setting: $key" }
            put(key,value)
        }
        put("backend",""); put("consent",false); put("face_restore",false)
        val inputs=source.optJSONArray("inputs") ?: JSONArray()
        require(inputs.length()<=40) { "Too many project inputs." }
        put("inputs",JSONArray().apply { for(i in 0 until inputs.length())put(map(inputs.getString(i))) })
        for(key in listOf("clips","caption_source_clips")) {
            val clips=source.optJSONArray(key) ?: JSONArray()
            require(clips.length()<=40) { "Too many timeline blocks." }
            put(key,JSONArray().apply {
                for(i in 0 until clips.length()) {
                    val old=clips.getJSONObject(i)
                    val start=old.getLong("start"); val end=old.getLong("end"); val duration=old.getLong("duration")
                    require(start>=0 && end>start && end<=duration) { "Fix invalid timeline ranges before backing up." }
                    val id=old.getString("id"); val origin=old.optString("origin",id)
                    require(id.length in 1..80 && origin.length in 1..80)
                    put(JSONObject().put("path",map(old.getString("path"))).put("start",start).put("end",end).put("duration",duration).put("id",id).put("origin",origin))
                }
            })
        }
        val cues=source.optJSONArray("caption_source_cues") ?: JSONArray()
        require(cues.length()<=2000) { "Too many captions." }
        put("caption_source_cues",JSONArray().apply {
            for(i in 0 until cues.length()) {
                val cue=cues.getJSONObject(i); val start=cue.getLong("start"); val end=cue.getLong("end"); val text=cue.getString("text")
                require(start>=0 && end>start && text.length<=1000) { "Invalid caption source." }
                put(JSONObject().put("start",start).put("end",end).put("text",text))
            }
        })
    }

    fun write(project:Project,filesRoot:File,output:OutputStream,check:()->Unit={},progress:(Int)->Unit={}) {
        require(project.tool in tools && project.status !in setOf("queued","processing")) { "Wait for processing to finish before backing up." }
        val source=JSONObject(project.recipe)
        val sources=LinkedHashMap<String,File>()
        val root=filesRoot.canonicalFile
        fun sourceFile(path:String):File {
            val file=File(path).canonicalFile
            require(listOf("imports","outputs").any { file.path.startsWith(File(root,it).path+File.separator) } && file.isFile) { "A project media file is missing or outside private media storage." }
            require(file.length() in 1..MAX_FILE) { "Each backup asset must be at most 512 MB." }
            return file
        }
        recipe(source) { path -> val file=sourceFile(path); sources[file.path]=file; file.path }
        val completed=project.status=="completed"
        val result=if(completed)sourceFile(project.output) else null
        require(!completed || project.mime in mimes) { "Unsupported project output type." }
        val files=sources.values.toList()+listOfNotNull(result)
        require(files.size<=100) { "Too many backup files." }
        val total=files.sumOf { it.length() }; require(total<=MAX_TOTAL) { "Project backup limit is 1 GB." }
        var done=0L
        val assets=files.mapIndexed { index,file ->
            check()
            val suffix=file.extension.lowercase().takeIf { it in extensions } ?: "mp4"
            val digest=MessageDigest.getInstance("SHA-256"); val crc=CRC32(); var bytes=0L
            file.inputStream().use { input -> val buffer=ByteArray(65536); while(true) { check(); val n=input.read(buffer); if(n<0)break; digest.update(buffer,0,n); crc.update(buffer,0,n); bytes+=n; done+=n; progress((done*50/total.coerceAtLeast(1)).toInt()) } }
            require(bytes==file.length() && bytes in 1..MAX_FILE) { "Project media changed during backup." }
            Asset("assets/${index.toString().padStart(4,'0')}.$suffix",file,bytes,digest.digest().hex(),crc.value)
        }
        val inputMap=sources.keys.zip(assets.take(sources.size).map { it.key }).toMap()
        val clean=recipe(source) { path -> inputMap.getValue(File(path).canonicalPath) }
        val manifest=JSONObject().put("version",1).put("name",project.name.take(80)).put("tool",project.tool).put("favorite",project.favorite).put("recipe",clean)
            .put("output",if(completed)assets.last().key else "").put("mime",if(completed)project.mime else "")
            .put("assets",JSONArray().apply { assets.forEach { put(JSONObject().put("key",it.key).put("size",it.size).put("sha256",it.hash)) } })
        val metadata=manifest.toString().toByteArray(Charsets.UTF_8)
        require(metadata.size<=MAX_METADATA) { "Project metadata exceeds 2 MB. Reduce caption text." }
        done=0
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("project.json")); zip.write(metadata); zip.closeEntry()
            for(asset in assets) {
                check()
                zip.putNextEntry(ZipEntry(asset.key).apply { method=ZipEntry.STORED; size=asset.size; compressedSize=asset.size; crc=asset.crc })
                asset.file.inputStream().use { input -> val buffer=ByteArray(65536); while(true) { check(); val n=input.read(buffer); if(n<0)break; zip.write(buffer,0,n); done+=n; progress(50+(done*50/total.coerceAtLeast(1)).toInt()) } }
                zip.closeEntry()
            }
        }
        progress(100)
    }

    suspend fun restore(input:InputStream,filesRoot:File,check:()->Unit={},progress:(Int)->Unit={},save:suspend(Project)->Unit):Project {
        filesRoot.mkdirs()
        val id=UUID.randomUUID().toString(); val staging=File(filesRoot,"restore-temp/$id").apply { mkdirs() }
        val created=ArrayList<File>(); var committed=false
        try {
            return ZipInputStream(input.buffered()).use { zip ->
                check()
                require(zip.nextEntry?.name=="project.json") { "Choose a RETRO project backup with project.json first." }
                val metadata=java.io.ByteArrayOutputStream(); val buffer=ByteArray(65536)
                while(true) { check(); val n=zip.read(buffer); if(n<0)break; require(metadata.size()+n<=MAX_METADATA) { "Backup metadata exceeds 2 MB." }; metadata.write(buffer,0,n) }
                zip.closeEntry()
                val text=metadata.toString("UTF-8"); validateDepth(text)
                val manifest=JSONObject(text)
                require(manifest.getInt("version")==1) { "Unsupported backup version." }
                val tool=manifest.getString("tool"); require(tool in tools) { "Unknown project tool." }
                val array=manifest.getJSONArray("assets"); require(array.length()<=100) { "Too many backup assets." }
                val declared=LinkedHashMap<String,JSONObject>(); var total=0L
                for(i in 0 until array.length()) {
                    val asset=array.getJSONObject(i); val key=asset.getString("key"); val size=asset.getLong("size")
                    require(Regex("assets/[0-9]{4}\\.(jpg|jpeg|png|webp|gif|mp4|mov|mkv|webm|srt|json)").matches(key) && key !in declared) { "Unsafe or duplicate backup asset name." }
                    require(size in 1..MAX_FILE && Regex("[a-f0-9]{64}").matches(asset.getString("sha256"))) { "Invalid backup asset metadata." }
                    total+=size; require(total<=MAX_TOTAL) { "Backup exceeds the 1 GB import limit." }; declared[key]=asset
                }
                require(filesRoot.usableSpace>total+2*1024*1024) { "Not enough free space to restore this project." }
                val unpacked=LinkedHashMap<String,File>(); var copied=0L
                while(true) {
                    check(); val entry=zip.nextEntry ?: break; val specification=declared[entry.name]
                    require(!entry.isDirectory && specification!=null && entry.name !in unpacked) { "Unexpected, duplicate or unsafe ZIP entry." }
                    val file=File(staging,"${UUID.randomUUID()}.${entry.name.substringAfterLast('.')}")
                    val digest=MessageDigest.getInstance("SHA-256"); var size=0L
                    file.outputStream().use { sink -> while(true) { check(); val n=zip.read(buffer); if(n<0)break; size+=n; require(size<=specification.getLong("size")) { "Backup asset exceeds its declared size." }; digest.update(buffer,0,n); sink.write(buffer,0,n); copied+=n; progress((copied*100/total.coerceAtLeast(1)).toInt()) } }
                    zip.closeEntry()
                    require(size==specification.getLong("size") && digest.digest().hex()==specification.getString("sha256")) { "Backup integrity check failed." }
                    unpacked[entry.name]=file
                }
                require(unpacked.keys==declared.keys) { "Backup is incomplete." }
                val outputKey=manifest.optString("output"); val mime=manifest.optString("mime")
                require(outputKey.isEmpty() || outputKey in unpacked && mime in mimes) { "Invalid backup output." }
                val inputKeys=LinkedHashSet<String>()
                recipe(manifest.getJSONObject("recipe")) { key -> require(key in unpacked) { "Missing backup input." }; inputKeys+=key; key }
                require(outputKey.isEmpty() || outputKey !in inputKeys) { "Output must have its own backup asset." }
                require(declared.keys==inputKeys+listOf(outputKey).filter { it.isNotEmpty() }) { "Backup contains unreferenced assets." }
                val paths=HashMap<String,String>()
                File(filesRoot,"imports").mkdirs(); File(filesRoot,"outputs").mkdirs()
                for(key in inputKeys) {
                    check()
                    val original=unpacked.getValue(key); val destination=File(filesRoot,"imports/${UUID.randomUUID()}.${original.extension}")
                    require(!destination.exists() && original.renameTo(destination)) { "Cannot restore input media." }
                    created+=destination
                    paths[key]=destination.path
                }
                var result=""
                if(outputKey.isNotEmpty()) {
                    check(); val original=unpacked.getValue(outputKey); val destination=File(filesRoot,"outputs/$id.${original.extension}")
                    require(!destination.exists() && original.renameTo(destination)) { "Cannot restore output media." }; created+=destination; result=destination.path
                }
                val clean=recipe(manifest.getJSONObject("recipe")) { paths.getValue(it) }.put("tool",if(tool=="transcribe")"clipper" else tool)
                val project=Project(id,(manifest.optString("name","RESTORED PROJECT").take(70)+" RESTORED").take(80),tool,clean.toString(),status=if(result.isEmpty())"draft" else "completed",output=result,mime=if(result.isEmpty())"" else mime,favorite=manifest.optBoolean("favorite"))
                check()
                withContext(NonCancellable) { save(project); committed=true }
                progress(100)
                project
            }
        } finally {
            staging.deleteRecursively()
            if(!committed)created.forEach { it.delete() }
        }
    }
    private fun ByteArray.hex()=joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun validateDepth(text:String) {
        var depth=0; var quoted=false; var escaped=false
        for(char in text) {
            if(quoted) { if(escaped)escaped=false else if(char=='\\')escaped=true else if(char=='"')quoted=false }
            else when(char) { '"'->quoted=true; '{','['->{ depth++; require(depth<=32) { "Backup metadata is nested too deeply." } }; '}',']'->depth-- }
        }
    }
}
