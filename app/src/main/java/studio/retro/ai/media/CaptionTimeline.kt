package studio.retro.ai.media

/** Maps timeline cues through source ranges, including children of a split clip. */
object CaptionTimeline {
    fun retime(cues:List<CaptionCue>,before:List<ClipEdit>,after:List<ClipEdit>):List<CaptionCue> {
        before.forEach { it.validated() }; after.forEach { it.validated() }
        val oldOffsets=offsets(before); val newOffsets=offsets(after)
        val destinations=after.indices.groupBy { after[it].originId to after[it].path }
        val result=ArrayList<CaptionCue>()
        for(cue in cues) {
            val pieces=ArrayList<CaptionCue>()
            for((index,clip) in before.withIndex()) {
                val begin=maxOf(cue.startMs,oldOffsets[index]); val end=minOf(cue.endMs,oldOffsets[index]+clip.durationMs)
                if(end<=begin)continue
                val sourceStart=clip.startMs+begin-oldOffsets[index]; val sourceEnd=clip.startMs+end-oldOffsets[index]
                for(destination in destinations[clip.originId to clip.path].orEmpty()) {
                    val target=after[destination]
                    val retainedStart=maxOf(sourceStart,target.startMs); val retainedEnd=minOf(sourceEnd,target.endMs)
                    if(retainedEnd>retainedStart)pieces+=CaptionCue(newOffsets[destination]+retainedStart-target.startMs,newOffsets[destination]+retainedEnd-target.startMs,cue.text)
                }
            }
            // Rejoin adjacent fragments of this cue after a split that leaves playback intact.
            val merged=ArrayList<CaptionCue>()
            for(piece in pieces.sortedBy { it.startMs }) {
                val previous=merged.lastOrNull()
                if(previous!=null && previous.endMs>=piece.startMs)merged[merged.lastIndex]=previous.copy(endMs=maxOf(previous.endMs,piece.endMs)) else merged+=piece
            }
            result+=merged
            require(result.size<=2000) { "This edit would exceed 2000 caption fragments. Simplify the captions first." }
        }
        return result.sortedWith(compareBy<CaptionCue>{it.startMs}.thenBy{it.endMs})
    }
    private fun offsets(clips:List<ClipEdit>):LongArray {
        var offset=0L
        return LongArray(clips.size) { index -> offset.also { offset+=clips[index].durationMs } }
    }
}
