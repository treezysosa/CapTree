package studio.retro.ai.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class BackendClient(endpoint:String, private val token:String) {
    private val base=endpoint.toHttpUrl().also { require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query==null && it.fragment==null) { "Use an HTTPS backend URL without credentials, query, or fragment." } }
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private fun request(path:String)=Request.Builder().url(base.newBuilder().encodedPath(base.encodedPath.trimEnd('/')+path).build()).apply { if(token.isNotBlank())header("Authorization","Bearer $token") }
    private suspend fun execute(request:Request):Response = suspendCancellableCoroutine { continuation ->
        val call=client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive)continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call:Call,response:Response) { if(continuation.isActive)continuation.resumeWith(Result.success(response)) else response.close() }
        })
    }
    private suspend fun json(request:Request):JSONObject = execute(request).use { response ->
        val body=response.body?.string().orEmpty()
        require(response.isSuccessful) { "Backend ${response.code}: ${runCatching { JSONObject(body).optString("detail") }.getOrDefault(body).take(240)}" }
        JSONObject(body)
    }
    suspend fun health():JSONObject = json(request("/v1/health").get().build())
    suspend fun generate(recipe:JSONObject, input:File?, output:File, progress:suspend(Int?,String)->Unit) {
        val payload=JSONObject(recipe.toString()).apply { remove("inputs"); remove("backend"); remove("consent") }
        val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("payload",payload.toString()).apply { input?.let { addFormDataPart("image","input.png",it.asRequestBody("application/octet-stream".toMediaType())) } }.build()
        var id:String?=null
        try {
            id=json(request("/v1/jobs").post(body).build()).getString("id")
            withTimeout(8*60*1000L) {
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val status=json(request("/v1/jobs/$id").get().build())
                    when(status.getString("status")) {
                        "completed"->break
                        "failed"->error(status.optString("error","Inference failed."))
                        "cancelled"->throw CancellationException("Server cancelled generation.")
                    }
                    progress(if(status.isNull("progress"))null else status.getInt("progress"),status.optString("stage","Remote inference")); delay(1500)
                }
            }
            execute(request("/v1/jobs/$id/result").get().build()).use { response ->
                require(response.isSuccessful) { "Cannot download generated media (${response.code})." }
                response.body!!.byteStream().use { source -> output.outputStream().use { sink -> val buffer=ByteArray(65536); var total=0L; while(true) { currentCoroutineContext().ensureActive(); val n=source.read(buffer); if(n<0)break; total+=n; require(total<=200L*1024*1024) { "Remote output exceeds 200 MB." }; sink.write(buffer,0,n) } } }
            }
        } catch(e:Throwable) {
            if(id!=null) withContext(NonCancellable) { runCatching { execute(request("/v1/jobs/$id").delete().build()).close() } }
            throw e
        } finally {
            if(id!=null) withContext(NonCancellable) { runCatching { execute(request("/v1/jobs/$id/purge").delete().build()).close() } }
        }
    }
}
