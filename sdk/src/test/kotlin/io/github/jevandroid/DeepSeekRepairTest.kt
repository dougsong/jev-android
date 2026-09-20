package io.github.jevandroid

import io.github.jevandroid.core.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DeepSeekRepairTest {
    private val task = Task("Save", setOf("test.app"))
    private val snapshot = UiSnapshot("v1", "test.app", listOf(
        Element("0.1", "Save", "Button", "Save", null, setOf(Operation.CLICK)),
    ))
    private val choices = DeepSeekChoices.create(task, snapshot)
    private fun selection(operation: Operation): String = JSONObject()
        .put("action_id", choices.actions.entries.single { it.value.operation == operation }.key)
        .put("text_key", "").put("confidence", 0.9)
        .put("summary", "Save is visible.").put("expected_change", "Saved status is visible.").toString()
    private val valid = selection(Operation.CLICK)

    private fun envelope(content: String, finish: String = "tool_calls", refusal: String? = null): String {
        val call = JSONObject().put("id", "call_fixture").put("type", "function")
            .put("function", JSONObject().put("name", "select_action").put("arguments", content))
        val message = JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
            .put("tool_calls", JSONArray().put(call))
        refusal?.let { message.put("refusal", it) }
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", message))).toString()
    }

    private fun provider(calls: ScriptedCalls) = DeepSeekProvider("fixture-key", "fixture-model",
        ProviderTransport(calls), "https://example.invalid/chat/completions")

    private fun payload(call: RecordingCall): JSONObject = JSONObject(Buffer().also {
        requireNotNull(call.request().body).writeTo(it)
    }.readUtf8())

    @Test fun malformedDecisionGetsOneCorrectionWithSameStateAndNoRawOutput() = runBlocking {
        val history = listOf(StepRecord(1, Operation.WAIT, null, true))
        val bad = JSONObject(valid).put("explanation", "private-invalid-model-output").toString()
        val calls = ScriptedCalls(listOf(Reply(envelope(bad)), Reply(envelope(valid))))
        assertEquals(Decision(Operation.CLICK, "0.1", confidence = 0.9,
            summary = "Save is visible.", expectedChange = "Saved status is visible."), provider(calls).decide(task, snapshot, history))
        assertEquals(2, calls.calls.size)
        val first = payload(calls.calls[0])
        val second = payload(calls.calls[1])
        fun state(request: JSONObject): String = (0 until request.getJSONArray("messages").length())
            .map { request.getJSONArray("messages").getJSONObject(it) }
            .single { it.getString("role") == "user" }.getString("content")
        assertEquals(state(first), state(second))
        assertFalse(second.toString().contains("private-invalid-model-output"))
        assertFalse(second.toString().contains("fixture-key"))
        assertTrue(second.toString().contains("INVALID_SCHEMA"))
        assertEquals(1024, second.getInt("max_tokens"))
    }

    @Test fun emptyAndTruncatedResponsesCanBeRegeneratedBeforeExecution() = runBlocking {
        for (bad in listOf(envelope("  "), envelope(valid, "length"))) {
            val calls = ScriptedCalls(listOf(Reply(bad), Reply(envelope(valid))))
            assertEquals(Operation.CLICK, provider(calls).decide(task, snapshot, emptyList()).operation)
            assertEquals(2, calls.calls.size)
        }
    }

    @Test fun twoInvalidResponsesStopWithSpecificSanitizedReason() = runBlocking {
        val calls = ScriptedCalls(listOf(Reply(envelope("private-invalid-model-output")),
            Reply(envelope(JSONObject(valid).put("action_id", "private-invented-target").toString()))))
        try { provider(calls).decide(task, snapshot, emptyList()); fail("Expected rejection") }
        catch (error: DeepSeekResponseException) {
            assertEquals(DeepSeekRejectionReason.INVALID_TARGET, error.reason)
            assertEquals(2, error.attempts)
            assertTrue(error.message!!.contains("INVALID_TARGET"))
            for (sensitive in listOf("private-invalid-model-output", "private-invented-target", "fixture-key"))
                assertFalse(error.stackTraceToString().contains(sensitive))
        }
        assertEquals(2, calls.calls.size)
    }

    @Test fun refusalFilteringAndToolsRemainTerminal() = runBlocking {
        val tools = JSONObject(envelope(valid)).apply {
            getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                .put("tool_calls", JSONArray().put(JSONObject().put("name", "private-tool-name")))
        }.toString()
        for ((raw, reason) in listOf(
            envelope(valid, refusal = "private-refusal") to DeepSeekRejectionReason.REFUSAL,
            envelope(valid, "content_filter") to DeepSeekRejectionReason.FILTERED,
            tools to DeepSeekRejectionReason.UNEXPECTED_TOOLS,
        )) {
            val calls = ScriptedCalls(listOf(Reply(raw)))
            try { provider(calls).decide(task, snapshot, emptyList()); fail("Expected rejection") }
            catch (error: DeepSeekResponseException) {
                assertEquals(reason, error.reason)
                assertEquals(1, error.attempts)
                assertFalse(error.stackTraceToString().contains("private-"))
            }
            assertEquals(1, calls.calls.size)
        }
    }

    @Test fun httpAndNetworkFailuresAreNotRetried() = runBlocking {
        for (reply in listOf(Reply("private-server-error", code = 429), Reply(networkFailure = true))) {
            val calls = ScriptedCalls(listOf(reply))
            try { provider(calls).decide(task, snapshot, emptyList()); fail("Expected request failure") }
            catch (error: IOException) { assertFalse(error.stackTraceToString().contains("private-")) }
            assertEquals(1, calls.calls.size)
        }
    }

    @Test fun cancellationDuringCorrectionCancelsTheSecondRequest() = runBlocking {
        val calls = ScriptedCalls(listOf(Reply(envelope("")), Reply()))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { provider(calls).decide(task, snapshot, emptyList()) }
        assertEquals(2, calls.calls.size)
        job.cancelAndJoin()
        assertTrue(calls.calls[1].isCanceled())
        calls.calls[1].callback!!.onFailure(calls.calls[1], IOException("private-late-error"))
        assertTrue(job.isCancelled)
        assertEquals(2, calls.calls.size)
    }

    @Test fun noUiActionIsSentUntilTheCorrectedDecisionPassesValidation() = runBlocking {
        val saved = snapshot.copy(elements = snapshot.elements + Element("0.2", "Saved", "TextView", "", null, emptySet()))
        val savedChoices = DeepSeekChoices.create(task, saved)
        val done = JSONObject(valid).put("action_id", savedChoices.actions.entries.single {
            it.value.operation == Operation.DONE
        }.key).toString()
        val calls = ScriptedCalls(listOf(Reply(envelope(JSONObject(valid).put("action_id", "not-a-candidate").toString())),
            Reply(envelope(valid)), Reply(envelope(done))))
        var mutations = 0
        val runtime = object : DeviceRuntime {
            override suspend fun observe(task: Task) = if (mutations == 0) snapshot else saved
            override suspend fun execute(task: Task, snapshot: UiSnapshot, decision: Decision): Boolean {
                assertEquals(2, calls.calls.size)
                assertEquals("0.1", decision.target)
                mutations++
                return true
            }
        }
        val result = JevAgent(runtime, provider(calls), OutcomeVerifier { _, _ -> mutations == 1 }).run(task)
        assertEquals(Status.VERIFIED, result.status)
        assertEquals(1, result.steps)
        assertEquals(1, mutations)
        assertEquals(3, calls.calls.size)
    }

    private data class Reply(val body: String? = null, val code: Int = 200, val networkFailure: Boolean = false)
    private class ScriptedCalls(private val replies: List<Reply>) : Call.Factory {
        val calls = mutableListOf<RecordingCall>()
        override fun newCall(request: Request): Call {
            check(calls.size < replies.size) { "Unexpected extra model request" }
            return RecordingCall(request, replies[calls.size]).also(calls::add)
        }
    }
    private class RecordingCall(private val original: Request, private val reply: Reply) : Call {
        var callback: Callback? = null
        private var executed = false
        private var cancelled = false
        override fun request() = original
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) {
            executed = true
            callback = responseCallback
            if (reply.networkFailure) responseCallback.onFailure(this, IOException("private-network-details"))
            else reply.body?.let { body -> responseCallback.onResponse(this, Response.Builder().request(original)
                .protocol(Protocol.HTTP_1_1).code(reply.code).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()) }
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = RecordingCall(original, reply)
    }
}
