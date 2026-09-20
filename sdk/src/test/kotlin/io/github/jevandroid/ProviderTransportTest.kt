package io.github.jevandroid

import io.github.jevandroid.core.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProviderTransportTest {
    private lateinit var server: MockWebServer
    private val task = Task("Wait for loading", setOf("test.app"))
    private val snapshot = UiSnapshot("v1", "test.app", emptyList())

    @Before fun startServer() { server = MockWebServer(); server.start() }
    @After fun stopServer() { server.shutdown() }

    private fun request() = Request.Builder().url(server.url("/request")).header("Authorization", "Bearer test-key").build()
    private fun response(): String {
        val choices = DeepSeekChoices.create(task, snapshot)
        val arguments = JSONObject().put("action_id", choices.actions.entries.single { it.value.operation == Operation.WAIT }.key)
            .put("text_key", "").put("confidence", 0.8)
            .put("summary", "The page is loading.").put("expected_change", "Loading completes.")
        val call = JSONObject().put("id", "call_fixture").put("type", "function")
            .put("function", JSONObject().put("name", "select_action").put("arguments", arguments.toString()))
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
            .put("message", JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put("tool_calls", JSONArray().put(call))))).toString()
    }

    private suspend fun fails(expected: String, action: suspend () -> Unit) {
        try { action(); fail("Expected failure") } catch (e: IOException) {
            // Coroutine stacktrace recovery may wrap a sanitized exception in another
            // exception with the same message. Check every cause/suppressed exception
            // instead of requiring no cause, so raw network errors still fail this test.
            val pending = java.util.ArrayDeque<Throwable>().apply { add(e) }
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (pending.isNotEmpty()) {
                val failure = pending.removeFirst()
                assertTrue("Cyclic exception chain", seen.add(failure))
                assertTrue(failure is IOException)
                assertEquals(expected, failure.message)
                failure.cause?.let { pending.add(it) }
                failure.suppressed.forEach { pending.add(it) }
            }
            val diagnostic = e.stackTraceToString()
            listOf("secret-key", "raw-ui-text", "raw secret from network", "test-key", "fixture-key").forEach {
                assertFalse("Diagnostic exposed a fixture secret", diagnostic.contains(it))
            }
        }
    }

    @Test fun deepSeekProviderSendsBearerAndParsesStrictSelection() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        val provider = DeepSeekProvider("fixture-key", "fixture-model", ProviderTransport(), server.url("/chat/completions").toString())
        assertEquals(Decision(Operation.WAIT, confidence = 0.8,
            summary = "The page is loading.", expectedChange = "Loading completes."), provider.decide(task, snapshot, emptyList()))
        val received = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", received.method)
        assertEquals("/chat/completions", received.path)
        assertEquals("Bearer fixture-key", received.getHeader("Authorization"))
        assertTrue(received.getHeader("Content-Type")!!.startsWith("application/json"))
        val payload = received.body.readUtf8()
        assertEquals("fixture-model", JSONObject(payload).getString("model"))
        assertTrue(JSONObject(payload).getJSONArray("tools").getJSONObject(0).getJSONObject("function").getBoolean("strict"))
        assertFalse(payload.contains("fixture-key"))
    }

    @Test fun transportDisablesRedirectsRetriesAndSetsDeadline() {
        val client = ProviderTransport.client()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(25_000, client.callTimeoutMillis)
    }

    @Test fun redirectIsRejectedWithoutSendingAnotherRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/redirected")))
        server.enqueue(MockResponse().setBody("must not be requested"))
        fails("DeepSeek HTTP 307") { ProviderTransport().execute(request(), "DeepSeek") }
        assertEquals(1, server.requestCount)
    }

    @Test fun httpFailuresDoNotExposeResponseContent() = runBlocking {
        for (status in listOf(401, 429, 500)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("secret-key raw-ui-text"))
            fails("DeepSeek HTTP $status") { ProviderTransport().execute(request(), "DeepSeek") }
        }
    }

    @Test fun acceptsResponseAtByteLimit() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(32)))
        assertEquals("x".repeat(32), ProviderTransport(maxResponseBytes = 32).execute(request(), "DeepSeek"))
    }

    @Test fun rejectsOversizeContentLength() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(33)))
        fails("DeepSeek response too large") {
            ProviderTransport(maxResponseBytes = 32).execute(request(), "DeepSeek")
        }
    }

    @Test fun rejectsOversizeChunkedResponseWithoutContentLength() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("x".repeat(33), 8))
        fails("DeepSeek response too large") {
            ProviderTransport(maxResponseBytes = 32).execute(request(), "DeepSeek")
        }
    }

    @Test fun countsUtf8BytesInsteadOfCharacters() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("\u4e2d".repeat(11), 8))
        fails("DeepSeek response too large") {
            ProviderTransport(maxResponseBytes = 32).execute(request(), "DeepSeek")
        }
    }

    @Test fun connectionFailureIsNotRetried() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        fails("DeepSeek request failed") { ProviderTransport().execute(request(), "DeepSeek") }
        assertEquals(1, server.requestCount)
    }

    @Test fun coroutineCancellationCancelsUnderlyingCallAndIgnoresLateCallback() = runBlocking {
        val call = RecordingCall(request())
        val transport = ProviderTransport(Call.Factory { call })
        val job = launch(start = CoroutineStart.UNDISPATCHED) { transport.execute(request(), "DeepSeek") }
        assertTrue(call.isExecuted())
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        call.callback!!.onFailure(call, IOException("raw secret from network"))
        assertTrue(job.isCancelled)
    }

    @Test fun networkFailureHasNoOriginalExceptionOrSecret() = runBlocking {
        val transport = ProviderTransport(Call.Factory { request ->
            RecordingCall(request, failImmediately = true)
        })
        fails("DeepSeek request failed") { transport.execute(request(), "DeepSeek") }
    }

    @Test fun invalidApiKeyFailsBeforeBuildingUnsafeHeader() {
        try { DeepSeekProvider("secret\nvalue"); fail() } catch (e: IllegalArgumentException) {
            assertEquals("Invalid API key", e.message)
            assertNull(e.cause)
        }
    }

    private class RecordingCall(private val original: Request, private val failImmediately: Boolean = false) : Call {
        var callback: Callback? = null
        private var executed = false
        private var cancelled = false
        override fun request() = original
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) {
            executed = true
            callback = responseCallback
            if (failImmediately) responseCallback.onFailure(this, IOException("secret-key raw-ui-text"))
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = RecordingCall(original, failImmediately)
    }
}
