package studio.retro.ai.media

import org.junit.Assert.*
import org.junit.Test

class TimelineCaptionTest {
    private val clips = listOf(ClipEdit("one", 1000, 5000, 8000), ClipEdit("two", 0, 3000, 3000))

    @Test fun splittingPreservesDurationAndCreatesIndependentClips() {
        val result = Timeline.split(clips, 0, 2500)
        assertEquals(listOf(1500L, 2500L, 3000L), result.map { it.durationMs })
        assertEquals(clips.sumOf { it.durationMs }, result.sumOf { it.durationMs })
        assertNotEquals(result[0].id, result[1].id)
    }
    @Test(expected = IllegalArgumentException::class) fun splitOutsideTrimIsRejected() { Timeline.split(clips, 0, 6000) }
    @Test fun reorderingAndOffsetsMatchExportOrder() {
        val result = Timeline.move(clips, 1, -1)
        assertEquals("two", result[0].path)
        assertEquals(3000L, Timeline.outputOffset(result, 1))
    }
    @Test fun srtRoundTripsUnicodeAndMultilineCaptions() {
        val input = "\uFEFF1\r\n00:00:01,200 --> 00:00:02,800\r\nHello\r\n世界\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nRetro!\r\n"
        val parsed = CaptionTrack.parse(input, 5000)
        assertEquals("Hello\n世界", parsed[0].text)
        assertEquals(parsed, CaptionTrack.parse(CaptionTrack.srt(parsed), 5000))
        assertEquals("", CaptionTrack.textAt(parsed, 2800))
        assertEquals("Retro!", CaptionTrack.textAt(parsed, 3500))
    }
    @Test fun overlappingCuesAndLegacyClearEventsWork() {
        val cues = listOf(CaptionCue(0, 3000, "one"), CaptionCue(2000, 4000, "two"))
        assertEquals("one\ntwo", CaptionTrack.textAt(cues, 2500))
        val legacy = CaptionTrack.parse("1000|hello\n2000|\n3000|end", 5000)
        assertEquals("", CaptionTrack.textAt(legacy, 2500))
        assertEquals("end", CaptionTrack.textAt(legacy, 4999))
    }
    @Test(expected = IllegalArgumentException::class) fun malformedSubtitlesAreRejected() { CaptionTrack.parse("1\n00:99:00,000 --> 00:00:02,000\nNo", 3000) }
}
