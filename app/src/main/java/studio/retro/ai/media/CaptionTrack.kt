package studio.retro.ai.media

data class CaptionCue(val startMs: Long, val endMs: Long, val text: String) {
    init {
        require(startMs >= 0 && endMs > startMs) { "Caption end must be after its start." }
        require(text.length <= 1000) { "A caption may contain at most 1000 characters." }
    }
}

object CaptionTrack {
    fun replaceRange(existing:List<CaptionCue>,replacement:List<CaptionCue>,startMs:Long,endMs:Long):List<CaptionCue> {
        require(startMs>=0 && endMs>startMs)
        val retained=existing.flatMap { cue ->
            if(cue.endMs<=startMs || cue.startMs>=endMs)listOf(cue) else listOfNotNull(
                if(cue.startMs<startMs)cue.copy(endMs=startMs) else null,
                if(cue.endMs>endMs)cue.copy(startMs=endMs) else null
            )
        }
        return (retained+replacement).sortedBy { it.startMs }
    }
    fun parse(input: String, durationMs: Long): List<CaptionCue> {
        val normalized = input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) return emptyList()
        val cues = if (normalized.contains("-->")) parseSrt(normalized) else parseLegacy(normalized, durationMs)
        require(cues.size <= 2000) { "Caption limit: 2000 cues." }
        return cues.sortedBy { it.startMs }
    }

    private fun parseSrt(input: String): List<CaptionCue> = input.split(Regex("\n[ \t]*\n")).map { block ->
        val lines = block.lines()
        val timingIndex = if (lines.first().trim().toIntOrNull() != null) 1 else 0
        require(lines.size > timingIndex + 1) { "Each SRT caption needs a timestamp range and text." }
        val times = lines[timingIndex].split("-->")
        require(times.size == 2) { "Invalid SRT timestamp range." }
        CaptionCue(timestamp(times[0].trim()), timestamp(times[1].trim().substringBefore(' ')), lines.drop(timingIndex + 1).joinToString("\n"))
    }

    private fun parseLegacy(input: String, durationMs: Long): List<CaptionCue> {
        val events = input.lines().filter { it.isNotBlank() }.map { line ->
            val pair = line.split('|', limit = 2)
            require(pair.size == 2) { "Use SRT or milliseconds|caption, one event per line." }
            val start = pair[0].trim().toLongOrNull()
            require(start != null && start >= 0) { "Invalid caption start time." }
            start to pair[1].trim()
        }.sortedBy { it.first }
        return events.mapIndexedNotNull { index, event ->
            val end = events.getOrNull(index + 1)?.first ?: durationMs
            if (event.second.isBlank() || end <= event.first) null else CaptionCue(event.first, end, event.second)
        }
    }

    fun timestamp(value: String): Long {
        val match = Regex("(\\d{2,}):([0-5]\\d):([0-5]\\d)[,.](\\d{3})").matchEntire(value)
            ?: throw IllegalArgumentException("Use HH:MM:SS,mmm caption timestamps.")
        val (h, m, s, ms) = match.destructured
        require(h.length <= 5) { "Caption time is too large." }
        return h.toLong() * 3600000 + m.toLong() * 60000 + s.toLong() * 1000 + ms.toLong()
    }

    fun textAt(cues: List<CaptionCue>, timeMs: Long): String =
        cues.filter { timeMs >= it.startMs && timeMs < it.endMs }.joinToString("\n") { it.text }

    fun srt(cues: List<CaptionCue>): String = cues.sortedBy { it.startMs }.mapIndexed { index, cue ->
        "${index + 1}\n${format(cue.startMs)} --> ${format(cue.endMs)}\n${cue.text}"
    }.joinToString("\n\n") + if (cues.isEmpty()) "" else "\n"

    private fun format(ms: Long): String = java.lang.String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
}
