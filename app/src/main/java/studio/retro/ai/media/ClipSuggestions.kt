package studio.retro.ai.media

data class SuggestedClip(val startMs:Long,val endMs:Long,val score:Int,val reason:String)

object ClipSuggestions {
    /** Ranks measured scene/audio/caption activity. Scores are heuristic, not AI probabilities. */
    fun rank(startMs:Long,endMs:Long,cuts:List<Long>,quiet:List<Long>,captions:List<CaptionCue>,audioAvailable:Boolean):List<SuggestedClip> {
        require(startMs>=0 && endMs>startMs)
        val span=(endMs-startMs).coerceAtMost(8000)
        val anchors=(listOf(startMs,endMs-span)+cuts.map { it-span/2 }+captions.map { (it.startMs+it.endMs-span)/2 }+(startMs until endMs step 8000)).map { it.coerceIn(startMs,endMs-span) }.distinct()
        val candidates=anchors.map { begin ->
            val end=begin+span; val seconds=span/1000.0
            val quietFraction=(quiet.count { it in begin until end }*0.5/seconds).coerceIn(0.0,1.0)
            val scene=(cuts.count { it in begin until end }/3.0).coerceIn(0.0,1.0)
            val speech=(captions.count { it.endMs>begin && it.startMs<end }/3.0).coerceIn(0.0,1.0)
            val score=if(audioAvailable)(0.5*(1-quietFraction)+0.3*scene+0.2*speech) else (0.7*scene+0.3*speech)
            val reason=buildList { if(scene>0)add("scene changes"); if(audioAvailable)add(if(quietFraction<0.25)"less quiet audio" else "some quiet audio"); if(speech>0)add("caption activity"); if(!audioAvailable)add("scene-only scan") }.joinToString(" · ")
            SuggestedClip(begin,end,(score*100).toInt(),reason)
        }.sortedByDescending { it.score }
        val result=ArrayList<SuggestedClip>()
        for(candidate in candidates) {
            if(result.none { other -> val overlap=(minOf(other.endMs,candidate.endMs)-maxOf(other.startMs,candidate.startMs)).coerceAtLeast(0); overlap>span*0.6 })result+=candidate
            if(result.size==5)break
        }
        return result
    }
}
