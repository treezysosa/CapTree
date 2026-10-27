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
    @Test fun faceRestorationUsesOnlyTheProcessedImageAndExplicitStrength():Unit=runBlocking {
        val (server,http)=server(); val input=File.createTempFile("PRIVATE-PROCESSED-PATH",".png"); val output=File.createTempFile("retro-face-result",".png")
        try {
            input.writeBytes(byteArrayOf(9,8,7)); server.enqueue(health("face-restoration"))
            server.enqueue(MockResponse().setBody("{\"id\":\"face\"}")); server.enqueue(MockResponse().setBody("{\"status\":\"completed\"}"))
            server.enqueue(MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(byteArrayOf(1,2,3))))
            server.enqueue(MockResponse().setBody("{}"))
            BackendClient(server.url("/").toString(),"test-token",http).restoreFace(input,0.7f,output) { _,_-> }
            assertArrayEquals(byteArrayOf(1,2,3),output.readBytes()); server.takeRequest()
            val upload=server.takeRequest(); val body=upload.body.readUtf8()
            assertEquals("/v1/face-restorations",upload.path); assertTrue(body.contains("restore-face")); assertTrue(body.contains("strength"))
            assertTrue(body.contains("filename=\"input.png\"")); assertFalse(body.contains(input.path)); assertFalse(body.contains("PRIVATE-PROCESSED-PATH"))
            server.takeRequest(); server.takeRequest(); assertEquals("/v1/jobs/face/purge",server.takeRequest().path)
        } finally { server.shutdown(); input.delete(); output.delete() }
    }
    @Test fun faceCapabilityIsCheckedBeforeAnyPhotoUpload():Unit=runBlocking {
        val (server,http)=server(); val input=File.createTempFile("retro-face",".png"); val output=File.createTempFile("retro-face",".png").apply { delete() }
        try {
            input.writeBytes(byteArrayOf(1,2)); server.enqueue(health("face-restoration",false))
            try { BackendClient(server.url("/").toString(),"",http).restoreFace(input,0.5f,output) { _,_-> }; fail("Unsupported server must reject restoration") } catch(expected:IllegalArgumentException) { assertTrue(expected.message!!.contains("Unsupported")) }
            assertEquals(1,server.requestCount); assertFalse(output.exists())
        } finally { server.shutdown(); input.delete(); output.delete() }
    }
    @Test(expected=IllegalArgumentException::class) fun credentialsInBackendUrlAreRejected() { BackendClient("https://name:secret@example.com","") }
    @Test fun semanticHighlightsSendOnlyThumbnailsRelativeTimesAndDescription():Unit=runBlocking {
        val (server,http)=server(); val frame=File.createTempFile("PRIVATE-VIDEO-LOCATION",".jpg"); val output=File.createTempFile("retro-highlights",".json")
        try {
            frame.writeBytes(byteArrayOf(1,2,3)); server.enqueue(health("semantic-highlights")); server.enqueue(MockResponse().setBody("{\"id\":\"visual\"}")); server.enqueue(MockResponse().setBody("{\"status\":\"completed\"}"))
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("{\"ranges\":[]}")); server.enqueue(MockResponse().setBody("{}"))
            BackendClient(server.url("/").toString(),"test-token",http).highlights("a guitar",2000,1000,listOf(500),listOf(frame),output) { _,_-> }
            server.takeRequest(); val request=server.takeRequest(); val body=request.body.readUtf8()
            assertEquals("/v1/highlights",request.path); assertTrue(body.contains("a guitar")); assertTrue(body.contains("timestamps_ms")); assertTrue(body.contains("filename=\"0000.jpg\"")); assertTrue(body.contains("image/jpeg"))
            assertFalse(body.contains(frame.name)); assertFalse(body.contains(frame.path)); assertFalse(body.contains("audio")); assertFalse(body.contains("inputs")); assertFalse(body.contains("backend"))
            server.takeRequest(); server.takeRequest(); assertEquals("/v1/jobs/visual/purge",server.takeRequest().path)
        } finally { server.shutdown(); frame.delete(); output.delete() }
    }
    @Test fun unsupportedHighlightsNeverUploadPrivateFrames():Unit=runBlocking {
        val (server,http)=server(); val frame=File.createTempFile("retro-frame",".jpg"); val output=File.createTempFile("retro-highlights",".json").apply { delete() }
        try {
            frame.writeBytes(byteArrayOf(1)); server.enqueue(health("semantic-highlights",false))
            try { BackendClient(server.url("/").toString(),"",http).highlights("a guitar",2000,1000,listOf(500),listOf(frame),output) { _,_-> }; fail("Must reject before uploading") } catch(expected:IllegalArgumentException) { assertTrue(expected.message!!.contains("Unsupported")) }
            assertEquals(1,server.requestCount); assertFalse(output.exists())
        } finally { server.shutdown(); frame.delete(); output.delete() }
    }
}
