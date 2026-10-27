package studio.retro.ai.media

import org.json.JSONObject
import kotlin.math.roundToLong

data class SpeechWord(val startMs:Long,val endMs:Long,val text:String)

object SpeechWords {
    fun parse(result:String,durationMs:Long):List<SpeechWord> {
        require(result.length<=1_000_000)
        val json=JSONObject(result); val array=json.optJSONArray("result")
        require(array!=null || json.optString("text").isBlank()) { "The speech engine omitted word timestamps." }
        if(array==null)return emptyList()
        require(array.length()<=10000)
        return List(array.length()) { i ->
            val item=array.getJSONObject(i); val start=item.getDouble("start"); val end=item.getDouble("end"); val word=item.getString("word").trim()
            require(start.isFinite() && end.isFinite() && start>=0 && end>=start && word.length in 1..100 && !word.contains('\n')) { "Invalid speech word or timing." }
            SpeechWord((start*1000).roundToLong().coerceIn(0,durationMs),(end*1000).roundToLong().coerceIn(0,durationMs),word)
        }.filter { it.endMs>it.startMs }
    }
    fun captions(words:List<SpeechWord>):List<CaptionCue> {
        require(words.size<=10000)
        val result=ArrayList<CaptionCue>(); var group=ArrayList<SpeechWord>()
        fun flush() {
            if(group.isNotEmpty()) { result+=CaptionCue(group.first().startMs,group.maxOf { it.endMs },group.joinToString(" ") { it.text }); group=ArrayList() }
        }
        for(word in words.sortedBy { it.startMs }) {
            require(word.startMs>=0 && word.endMs>word.startMs && word.text.isNotBlank() && word.text.length<=100)
            if(group.isNotEmpty() && (group.size>=8 || group.sumOf { it.text.length+1 }+word.text.length>42 || word.endMs-group.first().startMs>3500 || word.startMs-group.last().endMs>=500))flush()
            group+=word
        }
        flush(); require(result.size<=2000) { "Too many subtitle cues. Trim the selected range." }
        return result
    }
}
