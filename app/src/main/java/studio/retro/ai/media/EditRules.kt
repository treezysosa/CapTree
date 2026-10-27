package studio.retro.ai.media

object EditRules {
    fun frameTimes(startMs: Long, endMs: Long, fps: Int, speed: Float): List<Long> {
        require(startMs >= 0 && endMs > startMs) { "Choose an end after the start." }
        require(fps in 1..24 && speed in 0.25f..4f)
        val count = ((endMs - startMs) * fps / (1000.0 * speed)).toInt().coerceAtLeast(1)
        require(count <= 240) { "Use a shorter clip, lower FPS, or higher speed (240 frame limit)." }
        return List(count) { startMs + (it * 1000.0 * speed / fps).toLong() }
    }
    fun outputSize(width: Int, height: Int, longest: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && longest in 64..1920)
        val scale = minOf(1.0, longest.toDouble() / maxOf(width, height))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
    fun validateTrim(start: Long, end: Long, duration: Long) {
        require(start >= 0 && end > start && end <= duration) { "Trim must fit inside the video." }
    }
    fun dimensions(aspect: String): Pair<Int, Int> = when (aspect) { "9:16" -> 432 to 768; "16:9" -> 768 to 432; else -> 512 to 512 }
}
