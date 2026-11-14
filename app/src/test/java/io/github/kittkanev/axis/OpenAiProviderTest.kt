package io.github.kittkanev.axis

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiProviderTest {
    private val server = MockWebServer()
    private lateinit var provider: OpenAiProvider

    @Before
    fun startHttpsServer() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
        provider = OpenAiProvider(client)
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun sendsExpectedRequestAndParsesAssistantReply() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Привет"}}]}"""))
        assertEquals("Привет", complete())
        val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/chat/completions", request.path)
        assertEquals("Bearer example-key", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("test-model", payload.getString("model"))
        assertFalse(payload.getBoolean("stream"))
        assertEquals("user", payload.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("Привет?", payload.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun reportsAuthorizationRateLimitAndUnexpectedReplyWithoutExposingResponseBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("example-key"))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("<html>example-key</html>"))
        val authorization = error()
        assertTrue(authorization.contains("ключ"))
        assertFalse(authorization.contains("example-key"))
        assertTrue(error().contains("лимиты"))
        assertTrue(error().contains("формате"))
    }

    @Test
    fun stopsWaitingWhenCoroutineIsCancelled() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val request = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { complete() }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
    }

    private suspend fun complete() = provider.complete(
        server.url("/api/v1/"), "test-model", "example-key", listOf(Message(Role.USER, "Привет?")),
    )

    private suspend fun error(): String {
        val failure = runCatching { complete() }.exceptionOrNull() as AxisException
        return checkNotNull(failure.message)
    }
}
