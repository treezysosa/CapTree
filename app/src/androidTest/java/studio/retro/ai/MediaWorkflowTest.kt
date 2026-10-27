package studio.retro.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import studio.retro.ai.media.*
import java.io.File
import java.util.UUID

/** These tests run real Android codecs, OpenGL, GIF decoding, Room and WorkManager. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class MediaWorkflowTest {
    private val app: StudioApplication get() = ApplicationProvider.getApplicationContext()

    private fun fixture(name: String): File {
        val file = File(app.cacheDir, "${UUID.randomUUID()}-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { source -> file.outputStream().use { source.copyTo(it) } }
        return file
    }

    private fun photo(color: Int, directory: File = app.cacheDir): File {
        directory.mkdirs()
        val file = File(directory, "${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }

    @Test fun gifContainsActuallyDifferentAnimatedFrames() = runBlocking {
        val red = photo(Color.RED); val blue = photo(Color.BLUE)
        val output = File(app.cacheDir, "${UUID.randomUUID()}.gif")
        try {
            GifEngine.create(listOf(red, blue), false, output, 0, 1000, 2, 1f, 160, true, "", "") {}
            val movie = requireNotNull(output.inputStream().use { Movie.decodeStream(it) })
            assertEquals(1000, movie.duration())
            val canvasBitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            movie.setTime(0); movie.draw(canvas, 0f, 0f)
            assertEquals(Color.RED, canvasBitmap.getPixel(48, 32))
            movie.setTime(600); movie.draw(canvas, 0f, 0f)
            assertEquals(Color.BLUE, canvasBitmap.getPixel(48, 32))
            canvasBitmap.recycle()
        } finally { red.delete(); blue.delete(); output.delete() }
    }

    @Test fun photoCorrectionPreservesAnAlreadyWhiteImage() = runBlocking {
        val input = photo(Color.WHITE)
        try {
            val bitmap = PhotoEngine.read(input)
            val result = PhotoEngine.enhance(bitmap, 1f, true, true) {}
            assertEquals(Color.WHITE, result.getPixel(30, 30))
            bitmap.recycle(); result.recycle()
        } finally { input.delete() }
    }

    @Test fun videoTrimMergeAndCropPreserveAnAudioTrack() = runBlocking {
        val first = fixture("fixture.mp4"); val second = fixture("fixture-muted.mp4")
        val output = File(app.cacheDir, "${UUID.randomUUID()}.mp4")
        try {
            withTimeout(90000) {
                VideoEngine.export(app, listOf(Segment(first, 200, 800), Segment(second, 300, 900)), output, "1:1", 0f, listOf(CaptionCue(700, 1100, "RETRO"))) {}
            }
            assertTrue(output.length() > 1000)
            assertTrue(VideoEngine.duration(output) in 1000L..1600L)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                val video = formats.first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                assertEquals(video.getInteger(MediaFormat.KEY_WIDTH), video.getInteger(MediaFormat.KEY_HEIGHT))
                assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
                val lastTimes=mutableListOf<Long>()
                for(index in formats.indices) {
                    extractor.selectTrack(index); extractor.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    var last=0L
                    while(extractor.sampleTime>=0) { last=extractor.sampleTime; if(!extractor.advance())break }
                    lastTimes+=last; extractor.unselectTrack(index)
                }
                assertTrue("Audio and video end times must stay within 200 ms",lastTimes.max()-lastTimes.min()<200000)
            } finally { extractor.release() }
        } finally { first.delete(); second.delete(); output.delete() }
    }

    @Test fun gpuDenoiseAndSharpenProduceDecodableVideo() = runBlocking {
        val input = fixture("fixture.mp4"); val output = File(app.cacheDir, "${UUID.randomUUID()}.mp4")
        try {
            withTimeout(90000) { VideoEngine.export(app, listOf(Segment(input, 0, 1000)), output, "Original", 0.7f, emptyList(), true, true) {} }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                val frame = reader.getFrameAtTime(400000, MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull(frame)
                assertEquals(160, frame!!.width)
                frame.recycle()
            } finally { reader.release() }
        } finally { input.delete(); output.delete() }
    }

    @Test fun importedPhotoRunsThroughTheActualBackgroundGifWorkflow() = runBlocking {
        val input = photo(Color.GREEN, File(app.filesDir, "outputs"))
        val vm = StudioViewModel(app)
        var projectId: String? = null
        try {
            vm.open("gif")
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", input)
            vm.import(listOf(uri), false)
            withTimeout(10000) { vm.editor.first { it.inputs.isNotEmpty() } }
            vm.create()
            val project = withTimeout(90000) { vm.projects.first { rows -> rows.any { it.tool == "gif" && it.status in setOf("completed", "failed") } }.first { it.tool == "gif" && it.status in setOf("completed", "failed") } }
            projectId = project.id
            assertEquals(project.error, "completed", project.status)
            assertTrue(File(project.output).readBytes().take(6).toByteArray().toString(Charsets.US_ASCII).startsWith("GIF89a"))
            File(project.output).delete()
        } finally {
            projectId?.let { app.database.projects().delete(it) }
            vm.editor.value.inputs.forEach { File(it).delete() }
            input.delete()
        }
    }
}
