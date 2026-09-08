package io.github.amadeusb.callsheet

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.amadeusb.callsheet.sync.FailureKind
import io.github.amadeusb.callsheet.sync.HttpFailure
import io.github.amadeusb.callsheet.sync.SyncClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * [SyncClient] talks real HTTP, so it is tested against a real (if tiny)
 * server instead of the fake [io.github.amadeusb.callsheet.sync.Transport]
 * every other sync test uses. That fake stands in for exactly this class, so
 * this class needs its own coverage: headers, status mapping, reading the
 * body. Runs under Robolectric — not for any Android
 * API, but because org.json.JSONObject is an Android platform stub outside
 * Robolectric's runtime; a plain JUnit test would hit the stub, not a real
 * implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncClientTest {

    private lateinit var server: HttpServer

    @After
    fun teardown() {
        // stop(0) closes the listening socket immediately — no lingering
        // port, no lingering thread, so the rest of the suite is unaffected.
        if (::server.isInitialized) server.stop(0)
    }

    private fun start(handle: (HttpExchange) -> Unit): SyncClient {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sync") { exchange ->
            try {
                handle(exchange)
            } finally {
                exchange.close()
            }
        }
        server.executor = null
        server.start()
        return SyncClient("http://127.0.0.1:${server.address.port}", "geheimtoken")
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun expectFailure(client: SyncClient): HttpFailure {
        try {
            client.post(JSONObject())
            throw AssertionError("expected an HttpFailure")
        } catch (failure: HttpFailure) {
            return failure
        }
    }

    @Test
    fun `a 200 with a valid body is parsed and returned`() {
        val client = start { exchange ->
            exchange.requestBody.readBytes()
            respond(exchange, 200, """{"stand":5,"weitere":false}""")
        }
        val response = client.post(JSONObject().put("seit", 0))
        assertEquals(5, response.getInt("stand"))
        assertEquals(false, response.getBoolean("weitere"))
    }

    @Test
    fun `the bearer token from settings goes out on the wire`() {
        var seen: String? = null
        val client = start { exchange ->
            seen = exchange.requestHeaders.getFirst("Authorization")
            exchange.requestBody.readBytes()
            respond(exchange, 200, """{"stand":0,"weitere":false}""")
        }
        client.post(JSONObject())
        assertEquals("Bearer geheimtoken", seen)
    }

    @Test
    fun `the body the client sends is the JSON it was given`() {
        var seenBody: String? = null
        val client = start { exchange ->
            seenBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            respond(exchange, 200, """{"stand":0,"weitere":false}""")
        }
        val payload = JSONObject().put("seit", 42).put("hallo", "welt")
        client.post(payload)
        assertEquals(payload.toString(), seenBody)
    }

    @Test
    fun `401 becomes AUTH`() {
        val client = start { exchange ->
            exchange.requestBody.readBytes()
            respond(exchange, 401, "")
        }
        assertEquals(FailureKind.AUTH, expectFailure(client).kind)
    }

    @Test
    fun `404 becomes NOT_FOUND`() {
        val client = start { exchange ->
            exchange.requestBody.readBytes()
            respond(exchange, 404, "")
        }
        assertEquals(FailureKind.NOT_FOUND, expectFailure(client).kind)
    }

    @Test
    fun `413 becomes TOO_LARGE`() {
        val client = start { exchange ->
            exchange.requestBody.readBytes()
            respond(exchange, 413, "")
        }
        assertEquals(FailureKind.TOO_LARGE, expectFailure(client).kind)
    }

    @Test
    fun `429 becomes RATE_LIMITED`() {
        val client = start { exchange ->
            exchange.requestBody.readBytes()
            respond(exchange, 429, "")
        }
        assertEquals(FailureKind.RATE_LIMITED, expectFailure(client).kind)
    }

    @Test
    fun `a body that is not JSON becomes BAD_RESPONSE, not a crash`() {
        val client = start { exchange ->
            exchange.requestBody.readBytes()
            // The kind of body a proxy delivers instead of the server's
            // answer — an HTML error page rather than JSON.
            respond(exchange, 200, "<html><body>Bad Gateway</body></html>")
        }
        assertEquals(FailureKind.BAD_RESPONSE, expectFailure(client).kind)
    }

    @Test
    fun `an unlisted status code becomes SERVER`() {
        val client = start { exchange ->
            exchange.requestBody.readBytes()
            respond(exchange, 500, "")
        }
        assertEquals(FailureKind.SERVER, expectFailure(client).kind)
    }
}
