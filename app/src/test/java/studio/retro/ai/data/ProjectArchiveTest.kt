package studio.retro.ai.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProjectArchiveTest {
    private fun root()=Files.createTempDirectory("retro-archive").toFile()
    private fun source(root:File):Project {
        val input=File(root,"imports/input.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1,2,3,4)) }
        val output=File(root,"outputs/output.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(5,6,7,8)) }
        val clip=JSONObject().put("path",input.path).put("start",0).put("end",1000).put("duration",2000).put("id","clip").put("origin","clip")
        val recipe=JSONObject().put("tool","clipper").put("inputs",JSONArray().put(input.path)).put("clips",JSONArray().put(clip)).put("caption_source_clips",JSONArray().put(clip)).put("captions","0|Retro")
            .put("backend","PRIVATE_BACKEND").put("consent",true).put("token","PRIVATE_TOKEN").put("face_restore",true).put("gif_palette","Adaptive 64")
        return Project("old","My save","clipper",recipe.toString(),status="completed",output=output.path,mime="image/png",favorite=true)
    }
    private fun entries(bytes:ByteArray):LinkedHashMap<String,ByteArray> {
        val values=LinkedHashMap<String,ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip -> while(true) { val e=zip.nextEntry ?: break; values[e.name]=zip.readBytes(); zip.closeEntry() } }
        return values
    }
    private fun pack(entries:Map<String,ByteArray>):ByteArray=ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> for((name,bytes) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } } }.toByteArray()
    @Test fun roundTripPreservesMediaAndSourcePathsWithoutServerSecrets():Unit=runBlocking {
        val original=root(); val target=root()
        try {
            val p=source(original); val out=ByteArrayOutputStream(); ProjectArchive.write(p,original,out)
            val manifest=entries(out.toByteArray()).getValue("project.json").toString(Charsets.UTF_8)
            assertFalse(manifest.contains(original.path)); assertFalse(manifest.contains("PRIVATE_BACKEND")); assertFalse(manifest.contains("PRIVATE_TOKEN"))
            var saved:Project?=null
            val restored=ProjectArchive.restore(out.toByteArray().inputStream(),target) { saved=it }
            assertEquals(restored,saved); assertNotEquals(p.id,restored.id); assertTrue(restored.favorite); assertEquals("completed",restored.status)
            assertArrayEquals(File(p.output).readBytes(),File(restored.output).readBytes())
            val recipe=JSONObject(restored.recipe); val input=recipe.getJSONArray("inputs").getString(0)
            assertArrayEquals(byteArrayOf(1,2,3,4),File(input).readBytes())
            assertEquals(input,recipe.getJSONArray("clips").getJSONObject(0).getString("path"))
            assertEquals(input,recipe.getJSONArray("caption_source_clips").getJSONObject(0).getString("path"))
            assertEquals("",recipe.getString("backend")); assertFalse(recipe.getBoolean("consent")); assertFalse(recipe.getBoolean("face_restore"))
            assertEquals("Adaptive 64",recipe.getString("gif_palette"))
        } finally { original.deleteRecursively(); target.deleteRecursively() }
    }
    @Test fun unexpectedPathsAndTamperedAssetsLeaveNoImportedFiles():Unit=runBlocking {
        val original=root()
        try {
            val out=ByteArrayOutputStream(); ProjectArchive.write(source(original),original,out)
            for(attack in listOf("path","hash","size","depth")) {
                val target=root(); var saved=false
                try {
                    val files=entries(out.toByteArray())
                    when(attack) {
                        "path"->files["../../escape.txt"]="bad".toByteArray()
                        "hash"->{ val name=files.keys.first { it.startsWith("assets/") }; files[name]=byteArrayOf(9,9,9,9) }
                        "size"->{ val meta=JSONObject(files.getValue("project.json").toString(Charsets.UTF_8)); meta.getJSONArray("assets").getJSONObject(0).put("size",ProjectArchive.MAX_FILE+1); files["project.json"]=meta.toString().toByteArray() }
                        "depth"->files["project.json"]=("[".repeat(1000)+"0"+"]".repeat(1000)).toByteArray()
                    }
                    try { ProjectArchive.restore(pack(files).inputStream(),target) { saved=true }; fail("Malicious archive must be rejected") } catch(expected:IllegalArgumentException) { assertFalse(saved) }
                    assertTrue(target.walkTopDown().none { it.isFile })
                } finally { target.deleteRecursively() }
            }
        } finally { original.deleteRecursively() }
    }
    @Test fun persistenceFailureAndCancellationRollBackNewMedia():Unit=runBlocking {
        val original=root()
        try {
            val out=ByteArrayOutputStream(); ProjectArchive.write(source(original),original,out)
            for(cancel in listOf(false,true)) {
                val target=root()
                try {
                    var checks=0
                    try {
                        ProjectArchive.restore(out.toByteArray().inputStream(),target,check={if(cancel && ++checks==4)throw CancellationException()}) { throw IllegalStateException("database failed") }
                        fail("Failure must propagate")
                    } catch(expected:Exception) { assertTrue(expected is CancellationException || expected.message=="database failed") }
                    assertTrue(target.walkTopDown().none { it.isFile })
                } finally { target.deleteRecursively() }
            }
        } finally { original.deleteRecursively() }
    }
    @Test fun credentialFilesCannotBePackagedAsMedia() {
        val root=root()
        try {
            val secret=File(root,"server-token").apply { writeText("private") }
            val recipe=JSONObject().put("inputs",JSONArray().put(secret.path))
            try { ProjectArchive.write(Project("id","bad","photo",recipe.toString()),root,ByteArrayOutputStream()); fail("Private settings must not be exported") } catch(expected:IllegalArgumentException) { assertTrue(expected.message!!.contains("private media")) }
        } finally { root.deleteRecursively() }
    }
    @Test fun highlightResultBackupsRetainTheReportAndResetFrameConsent():Unit=runBlocking {
        val original=root(); val target=root()
        try {
            val base=source(original)
            val report=File(original,"outputs/highlights.json").apply { writeText("{\"ranges\":[]}") }
            val recipe=JSONObject(base.recipe).put("highlight_prompt","a guitar").put("highlight_clip_ms",6000).put("highlight_index",0).put("highlight_upload_confirmed",true)
            val project=base.copy(tool="highlights",recipe=recipe.toString(),output=report.path,mime="application/json")
            val backup=ByteArrayOutputStream(); ProjectArchive.write(project,original,backup)
            val restored=ProjectArchive.restore(backup.toByteArray().inputStream(),target) {}
            assertEquals("highlights",restored.tool); assertEquals("application/json",restored.mime); assertEquals(report.readText(),File(restored.output).readText()); assertEquals("json",File(restored.output).extension)
            val settings=JSONObject(restored.recipe)
            assertEquals("a guitar",settings.getString("highlight_prompt")); assertFalse(settings.has("highlight_upload_confirmed")); assertFalse(settings.getBoolean("consent"))
        } finally { original.deleteRecursively(); target.deleteRecursively() }
    }
    @Test fun interruptedImportCleanupSelectsOnlyOwnedUuidFolders() {
        val root=root()
        try {
            val interrupted=File(root,"restore-temp/${java.util.UUID.randomUUID()}").apply { mkdirs(); resolve("part.png").writeBytes(byteArrayOf(1)) }
            File(root,"restore-temp/keep-this-folder").mkdirs()
            File(root,"imports/${java.util.UUID.randomUUID()}").mkdirs()
            assertEquals(listOf(interrupted.canonicalFile),ProjectArchive.interruptedStaging(root))
        } finally { root.deleteRecursively() }
    }
}
