package studio.retro.ai.media

import org.json.JSONObject

data class SemanticRange(val startMs:Long,val endMs:Long,val score:Double)
data class HighlightResult(val sampleCount:Int,val model:String,val ranges:List<SemanticRange>)

object SemanticHighlights {
    /** Centers of equal bins, relative to the selected trim. Never sample outside it. */
    fun sampleTimes(durationMs:Long):List<Long> {
        require(durationMs in 1000..120000) { "Choose a trim between one second and two minutes." }
        val count=minOf(24,((durationMs+999)/1000).toInt())
        return List(count) { index -> (2L*index+1)*durationMs/(2L*count) }
    }
    fun parse(text:String,durationMs:Long):HighlightResult {
        require(text.length<=65536) { "Highlight results exceed 64 KB." }
        val root=JSONObject(text)
        require(root.getInt("version")==1 && root.getLong("duration_ms")==durationMs && root.getString("metric")=="cosine_similarity") { "Unsupported highlight result or mismatched trim." }
        val count=root.getInt("sample_count"); require(count in 1..24)
        val model=root.getString("model"); require(model=="openai/clip-vit-base-patch32") { "Unsupported visual model." }
        val array=root.getJSONArray("ranges"); require(array.length() in 1..5) { "No valid highlight ranges were returned." }
        val ranges=List(array.length()) { i ->
            val r=array.getJSONObject(i); val start=r.getLong("start_ms"); val end=r.getLong("end_ms"); val score=r.getDouble("score")
            require(start>=0 && end>start && end<=durationMs && score.isFinite() && score in -1.0..1.0) { "Invalid highlight range or score." }
            SemanticRange(start,end,score)
        }
        require(ranges.map { it.startMs to it.endMs }.distinct().size==ranges.size) { "Duplicate highlight ranges." }
        return HighlightResult(count,model,ranges)
    }
}
