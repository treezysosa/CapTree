package studio.retro.ai.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class BackendClientTest {
    private fun server():Pair<MockWebServer,OkHttpClient> {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server=MockWebServer().apply { useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false); start() }
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        return server to OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).followRedirects(false).callTimeout(10,TimeUnit.SECONDS).build()
    }
    private fun health(feature:String,supported:Boolean=true)=MockResponse().setHeader("Content-Type","application/json").setBody(JSONObject().put("api_version",2).put("features",JSONArray().put(JSONObject().put("id",feature).put("supported",supported).put("reason","Unsupported hardware"))).toString())

    @Test fun generationNeverUploadsLocalProjectPathsAndPurgesAfterDownload():Unit=runBlocking {
        val (server,http)=server(); val output=File.createTempFile("retro-result",".png")
        try {
            server.enqueue(health("text-to-image")); server.enqueue(MockResponse().setBody("{\"id\":\"job\"}"))
            server.enqueue(MockResponse().setBody("{\"status\":\"completed\",\"seed\":42}"))
            server.enqueue(MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(byteArrayOf(1,2,3))))
            server.enqueue(MockResponse().setBody("{}"))
            val recipe=JSONObject().put("tool","photo").put("prompt","retro console").put("inputs",JSONArray().put("PRIVATE_PATH")).put("clips",JSONArray().put(JSONObject().put("path","PRIVATE_PATH"))).put("backend","PRIVATE_ORIGIN").put("consent",true)
            val seed=BackendClient(server.url("/").toString(),"test-token",http).generate(recipe,null,output) { _,_-> }
            assertEquals(42,seed); assertArrayEquals(byteArrayOf(1,2,3),output.readBytes())
            server.takeRequest(); val upload=server.takeRequest()
            assertEquals("Bearer test-token",upload.getHeader("Authorization")); val body=upload.body.readUtf8()
            assertTrue(body.contains("retro console")); assertFalse(body.contains("PRIVATE_PATH")); assertFalse(body.contains("PRIVATE_ORIGIN"))
            server.takeRequest(); server.takeRequest(); assertEquals("/v1/jobs/job/purge",server.takeRequest().path)
        } finally { server.shutdown(); output.delete() }
    }
    @Test fun unsupportedSpeechIsRejectedBeforeAudioUpload():Unit=runBlocking {
        val (server,http)=server(); val audio=File.createTempFile("retro-audio",".wav"); val output=File.createTempFile("retro-caption",".srt").apply { delete() }
        try {
            audio.writeBytes(ByteArray(44)); server.enqueue(health("transcription",false))
            try { BackendClient(server.url("/").toString(),"",http).transcribe(audio,"auto",output) { _,_-> }; fail("Expected unsupported feature rejection") } catch(expected:IllegalArgumentException) { assertTrue(expected.message!!.contains("Unsupported")) }
            assertEquals(1,server.requestCount); assertFalse(output.exists())
        } finally { server.shutdown(); audio.delete(); output.delete() }
    }
    @Test fun failedTranscriptionRequestsCancellationAndPurge():Unit=runBlocking {
        val (server,http)=server(); val audio=File.createTempFile("retro-audio",".wav"); val output=File.createTempFile("retro-caption",".srt")
        try {
            audio.writeBytes(ByteArray(44)); server.enqueue(health("transcription")); server.enqueue(MockResponse().setBody("{\"id\":\"speech\"}"))
            server.enqueue(MockResponse().setBody("{\"status\":\"failed\",\"error\":\"Speech failed\"}")); server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setBody("{}"))
            try { BackendClient(server.url("/").toString(),"",http).transcribe(audio,"en",output) { _,_-> }; fail("Expected inference failure") } catch(expected:IllegalStateException) { assertEquals("Speech failed",expected.message) }
            assertFalse(output.exists()); server.takeRequest(); assertTrue(server.takeRequest().body.readUtf8().contains("selected.wav")); server.takeRequest()
            assertEquals("DELETE",server.takeRequest().method); assertEquals("/v1/jobs/speech/purge",server.takeRequest().path)
        } finally { server.shutdown(); audio.delete(); output.delete() }
    }
    @Test(expected=IllegalArgumentException::class) fun cleartextBackendIsRejected() { BackendClient("http://example.com","") }
    @Test(expected=IllegalArgumentException::class) fun credentialsInBackendUrlAreRejected() { BackendClient("https://name:secret@example.com","") }
}
