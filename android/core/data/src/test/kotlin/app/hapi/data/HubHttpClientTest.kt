package app.hapi.data

import app.hapi.data.sse.OkHttpSseTransport
import app.hapi.data.sse.TransportEvent
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

class HubHttpClientTest {
    private lateinit var server: MockWebServer
    private val clients = mutableListOf<OkHttpClient>()

    @BeforeTest
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        server.shutdown()
    }

    private fun client(allowUntrustedHttps: Boolean): OkHttpClient =
        hubHttpClientBuilder(allowUntrustedHttps).build().also(clients::add)

    @Test
    fun `strict mode rejects a self signed certificate`() {
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url(server.url("/health")).build()

        assertFailsWith<SSLHandshakeException> {
            client(false).newCall(request).execute().close()
        }
    }

    @Test
    fun `opt in accepts a self signed certificate`() {
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url(server.url("/health")).build()

        client(true).newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body?.string())
        }
    }

    @Test
    fun `opt in still rejects a hostname mismatch`() {
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder()
            .url(server.url("/health").newBuilder().host("127.0.0.1").build())
            .build()

        assertFailsWith<SSLPeerUnverifiedException> {
            client(true).newCall(request).execute().close()
        }
    }

    @Test
    fun `opt in streams SSE over a self signed certificate`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: hello\n\n"),
        )
        val events = runBlocking {
            withTimeout(10_000) {
                OkHttpSseTransport(baseClient = client(true))
                    .open(server.url("/api/events").toString(), null)
                    .take(2)
                    .toList()
            }
        }

        assertEquals(TransportEvent.Connected, events[0])
        assertEquals("hello", assertIs<TransportEvent.Event>(events[1]).event.data)
    }
}
