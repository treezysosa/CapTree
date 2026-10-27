package studio.retro.ai.data

import org.json.JSONArray
import org.json.JSONObject
import studio.retro.ai.EditorState
import studio.retro.ai.media.HistorySnapshot

data class EditorCheckpoint(val current:EditorState,val history:HistorySnapshot<EditorState>,val historyLimited:Boolean=false)

/** Private draft values. Backend settings and upload confirmations never belong to a checkpoint. */
object EditorCheckpoints {
    const val MAX_BYTES=1_000_000
    val tools=setOf("upscale","video","photo","clipper","enhance","gif","aigif")
    fun empty(tool:String)=EditorState(tool=tool,style=if(tool=="photo")"Photorealistic" else "Cinematic",aspect=if(tool=="clipper")"Original" else "1:1")
    fun durable(state:EditorState)=state.copy(previewRequestMs=null,suggestions=emptyList(),suggestedClips=emptyList(),semanticClips=emptyList(),semanticSampleCount=0)
    fun meaningful(checkpoint:EditorCheckpoint):Boolean=(listOf(checkpoint.current)+checkpoint.history.past+checkpoint.history.future).any { durable(it).copy(playheadMs=0)!=empty(it.tool) }
    private fun state(value:EditorState):JSONObject {
        require(value.tool in tools)
        val clean=durable(value)
        val recipe=clean.recipe(Preferences()).apply { remove("backend"); remove("consent"); remove("quality") }
        return JSONObject().put("recipe",recipe).put("raw_start",clean.start).put("raw_end",clean.end).put("raw_seed",clean.seed).put("playhead",clean.playheadMs)
    }
    private fun read(value:JSONObject,tool:String):EditorState {
        val recipe=value.getJSONObject("recipe")
        require(recipe.getString("tool")==tool) { "Draft history belongs to another tool." }
        val restored=EditorState.restore(tool,recipe.toString())
        val position=value.optLong("playhead",0).coerceAtLeast(0)
        return restored.copy(start=value.getString("raw_start"),end=value.getString("raw_end"),seed=value.getString("raw_seed"),playheadMs=position,previewRequestMs=position)
    }
    fun encode(value:EditorCheckpoint):Pair<String,EditorCheckpoint> {
        require(value.current.tool in tools && value.history.past.size+value.history.future.size<=30)
        val current=state(value.current).toString()
        var bytes=current.toByteArray(Charsets.UTF_8).size+256
        require(bytes<=MAX_BYTES) { "This draft is too large to auto-save. Reduce caption or prompt text; current edits remain in memory." }
        val past=ArrayDeque<Pair<EditorState,String>>(); val future=ArrayDeque<Pair<EditorState,String>>()
        var limited=value.historyLimited
        // Encode one candidate at a time; never build a multi-megabyte history just to trim it.
        for(distance in 0 until maxOf(value.history.past.size,value.history.future.size)) {
            for((source,target) in listOf(value.history.past to past,value.history.future to future)) {
                val candidate=source.getOrNull(source.lastIndex-distance) ?: continue
                require(candidate.tool==value.current.tool) { "Draft history belongs to another tool." }
                val text=state(candidate).toString(); val size=text.toByteArray(Charsets.UTF_8).size+1
                if(bytes+size<=MAX_BYTES) { target.addFirst(candidate to text); bytes+=size } else limited=true
            }
        }
        val encoded="{\"version\":1,\"tool\":${JSONObject.quote(value.current.tool)},\"current\":$current,\"past\":[${past.joinToString(",") { it.second }}],\"future\":[${future.joinToString(",") { it.second }}],\"history_limited\":$limited}"
        require(encoded.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)
        return encoded to EditorCheckpoint(durable(value.current),HistorySnapshot(past.map { durable(it.first) },future.map { durable(it.first) }),limited)
    }
    fun decode(text:String):EditorCheckpoint {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "Draft exceeds the supported size." }
        val root=JSONObject(text); require(root.getInt("version")==1) { "Unsupported automatic draft version." }
        val tool=root.getString("tool"); require(tool in tools) { "Unknown draft tool." }
        fun history(key:String):List<EditorState> { val a=root.getJSONArray(key); require(a.length()<=30); return List(a.length()) { read(a.getJSONObject(it),tool) } }
        val past=history("past"); val future=history("future"); require(past.size+future.size<=30)
        return EditorCheckpoint(read(root.getJSONObject("current"),tool),HistorySnapshot(past,future),root.optBoolean("history_limited"))
    }
    fun paths(value:EditorCheckpoint):Set<String> = (listOf(value.current)+value.history.past+value.history.future).flatMap { it.inputs+(it.clips+it.captionSourceClips).map { clip -> clip.path } }.toSet()
    fun media(value:EditorCheckpoint)=JSONArray(paths(value).sorted()).toString()
    fun mediaPaths(text:String):Set<String> { val a=JSONArray(text); return (0 until a.length()).map { a.getString(it) }.toSet() }
}
