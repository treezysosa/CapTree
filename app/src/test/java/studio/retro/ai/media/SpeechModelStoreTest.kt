package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpeechModelStoreTest {
    @Test fun wrongArchiveCannotReplaceAnInstalledModelOrRunNativeCode() {
        val root=Files.createTempDirectory("speech-store").toFile(); val old=SpeechModelStore.directory(root).apply { mkdirs(); resolve("keep.txt").writeText("original") }; var called=false
        try {
            try { SpeechModelStore.install("bad ZIP".byteInputStream(),root,{called=true}); fail("Expected checksum rejection") } catch(expected:IllegalArgumentException) { assertTrue(expected.message!!.contains("SHA-256")) }
            assertFalse(called); assertEquals("original",old.resolve("keep.txt").readText()); assertEquals(listOf(old),root.listFiles()!!.toList())
        } finally { root.deleteRecursively() }
    }
    @Test fun extractionRejectsTraversalUnknownAndIncompleteArchives() {
        val root=Files.createTempDirectory("speech-zip").toFile()
        try {
            for(name in listOf("../escape",SpeechModelStore.MODEL_ID+"/../../escape",SpeechModelStore.MODEL_ID+"/surprise.so",SpeechModelStore.MODEL_ID+"/README")) {
                val zip=File(root,"input.zip"); ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry(name)); it.write(byteArrayOf(1)); it.closeEntry() }
                val staging=File(root,"stage").apply { mkdirs() }
                try { SpeechModelStore.extract(zip,staging); fail("Expected unsafe/incomplete archive rejection") } catch(expected:IllegalArgumentException) { assertTrue(staging.listFiles()!!.isEmpty()) }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun interruptedCleanupOnlySelectsOwnedUuidFolders() {
        val root=Files.createTempDirectory("speech-clean").toFile()
        try {
            val install=File(root,"speech-install-${UUID.randomUUID()}").apply { mkdirs() }
            val old=File(root,"speech-old-${UUID.randomUUID()}").apply { mkdirs() }
            File(root,"speech-old-keep").mkdirs(); File(root,"other-model").mkdirs()
            assertEquals(setOf(install,old),SpeechModelStore.recover(root).toSet())
        } finally { root.deleteRecursively() }
    }
}
