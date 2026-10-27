package studio.retro.ai.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class BackendClient(endpoint:String,private val token:String,private val client:OkHttpClient=defaultClient()) {
    private val base=endpoint.toHttpUrl().also { require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query==null && it.fragment==null) { "Use an HTTPS URL without credentials, query, or fragment." } }
    private var verifiedHealth:JSONObject?=null
    private fun request(path:String)=Request.Builder().url(base.newBuilder().encodedPath(base.encodedPath.trimEnd('/')+path).build()).apply { if(token.isNotBlank())header("Authorization","Bearer $token") }
    private suspend fun execute(request:Request):Response=suspendCancellableCoroutine { continuation ->
        val call=client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive)continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call:Call,response:Response) { if(continuation.isActive)continuation.resumeWith(Result.success(response)) else response.close() }
        })
    }
    private suspend fun json(request:Request):JSONObject=execute(request).use { response ->
        val body=response.body?.string().orEmpty()
        require(body.length<=1_000_000) { "Backend JSON response is too large." }
        require(response.isSuccessful) { "Backend ${response.code}: ${runCatching { JSONObject(body).optString("detail") }.getOrDefault(body).take(240)}" }
        JSONObject(body)
    }
    suspend fun health():JSONObject=json(request("/v1/health").get().build()).also { verifiedHealth=it }
    suspend fun requireCapability(feature:String) {
        val status=verifiedHealth ?: health()
        require(status.optInt("api_version",1)>=2) { "Update your self-hosted backend to v0.3 for readiness checks and subtitles." }
        val features=status.optJSONArray("features") ?: error("Backend omitted its feature report.")
        val entry=(0 until features.length()).map { features.getJSONObject(it) }.firstOrNull { it.optString("id")==feature } ?: error("Backend does not support $feature.")
        require(entry.optBoolean("supported")) { entry.optString("reason","Backend feature is unsupported.") }
    }
    suspend fun generate(recipe:JSONObject,input:File?,output:File,progress:suspend(Int?,String)->Unit):Long {
        val tool=recipe.getString("tool")
        val feature=when(tool) { "photo"->if(input==null)"text-to-image" else "image-to-image"; "aigif"->"text-to-gif"; else->if(input==null)"text-to-video" else "image-to-video" }
        requireCapability(feature)
        // Explicit fields prevent project paths and other local metadata leaving the phone.
        val payload=JSONObject().apply { for(key in listOf("tool","prompt","negative","style","aspect","seed","duration","motion"))if(recipe.has(key))put(key,recipe.get(key)) }
        return submit("/v1/jobs",payload,input,"image","application/octet-stream",output,if(tool=="photo")"image/png" else if(tool=="aigif")"image/gif" else "video/mp4",progress)
    }
    suspend fun transcribe(audio:File,language:String,output:File,progress:suspend(Int?,String)->Unit) {
        requireCapability("transcription")
        require(audio.length() in 1..64L*1024*1024) { "Selected audio must be smaller than 64 MB." }
        submit("/v1/transcriptions",JSONObject().put("tool","transcribe").put("language",language),audio,"audio","audio/wav",output,"application/x-subrip",progress)
    }
    private suspend fun submit(path:String,payload:JSONObject,input:File?,field:String,mime:String,output:File,expectedMime:String,progress:suspend(Int?,String)->Unit):Long {
        val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("payload",payload.toString()).apply { input?.let { addFormDataPart(field,if(field=="audio")"selected.wav" else "input.png",it.asRequestBody(mime.toMediaType())) } }.build()
        var id:String?=null; var actualSeed=-1L; var complete=false
        try {
            progress(null,if(field=="audio")"SENDING SELECTED AUDIO..." else "SENDING GENERATION REQUEST...")
            id=json(request(path).post(body).build()).getString("id")
            withTimeout(8*60*1000L) {
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val status=json(request("/v1/jobs/$id").get().build())
                    when(status.getString("status")) {
                        "completed"->{ actualSeed=status.optLong("seed",-1); break }
                        "failed"->error(status.optString("error","Remote inference failed."))
                        "cancelled"->throw CancellationException("Server cancelled this job.")
                    }
                    progress(if(status.isNull("progress"))null else status.getInt("progress"),status.optString("stage","Remote inference")); delay(1500)
                }
            }
            progress(null,"DOWNLOADING CREATION...")
            execute(request("/v1/jobs/$id/result").get().build()).use { response ->
                require(response.isSuccessful) { "Cannot download result (${response.code})." }
                require(response.header("Content-Type").orEmpty().substringBefore(';').trim()==expectedMime) { "Backend returned an unexpected media type." }
                val limit=if(field=="audio")1_000_000L else 200L*1024*1024
                response.body!!.byteStream().use { source -> output.outputStream().use { sink -> val buffer=ByteArray(65536); var total=0L; while(true) { currentCoroutineContext().ensureActive(); val n=source.read(buffer); if(n<0)break; total+=n; require(total<=limit) { "Remote output is too large." }; sink.write(buffer,0,n) } } }
            }
            complete=true
            return actualSeed
        } finally {
            if(!complete)output.delete()
            if(id!=null)withContext(NonCancellable) {
                runCatching { withTimeout(10000) { if(!complete)execute(request("/v1/jobs/$id").delete().build()).close(); execute(request("/v1/jobs/$id/purge").delete().build()).close() } }
            }
        }
    }
    companion object {
        private fun defaultClient()=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(90,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
}
