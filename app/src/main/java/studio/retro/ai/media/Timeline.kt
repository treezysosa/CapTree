package studio.retro.ai.media

import java.util.UUID

data class ClipEdit(
    val path: String,
    val startMs: Long,
    val endMs: Long,
    val sourceDurationMs: Long,
    val id: String = UUID.randomUUID().toString()
) {
    val durationMs: Long get() = endMs - startMs
    fun validated(): ClipEdit {
        EditRules.validateTrim(startMs, endMs, sourceDurationMs)
        require(durationMs >= 40) { "Keep at least 40 milliseconds in each clip." }
        return this
    }
}

object Timeline {
    fun move(clips: List<ClipEdit>, index: Int, direction: Int): List<ClipEdit> {
        require(index in clips.indices)
        val destination = (index + direction).coerceIn(clips.indices)
        return clips.toMutableList().apply { add(destination, removeAt(index)) }
    }

    fun split(clips: List<ClipEdit>, index: Int, sourceTimeMs: Long): List<ClipEdit> {
        require(index in clips.indices)
        val clip = clips[index].validated()
        val left = clip.copy(endMs = sourceTimeMs).validated()
        val right = clip.copy(startMs = sourceTimeMs, id = UUID.randomUUID().toString()).validated()
        return clips.take(index) + listOf(left, right) + clips.drop(index + 1)
    }

    fun outputOffset(clips: List<ClipEdit>, index: Int): Long = clips.take(index).sumOf { it.durationMs }
}
