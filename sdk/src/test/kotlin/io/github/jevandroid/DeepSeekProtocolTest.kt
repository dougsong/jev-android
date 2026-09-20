package io.github.jevandroid

import io.github.jevandroid.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekProtocolTest {
    private val task = Task("Fill field", setOf("test.app"), mapOf("greeting" to "hello"))
    private val snapshot = UiSnapshot("v1", "test.app", listOf(
        Element("7", "Message", "EditText", "", null, setOf(Operation.SET_TEXT)),
        Element("8", "Save", "Button", "", null, setOf(Operation.CLICK)),
        Element("9", "List", "ListView", "", null, setOf(Operation.SCROLL_FORWARD, Operation.SCROLL_BACKWARD)),
        Element("10", "Hold", "Button", "", null, setOf(Operation.LONG_CLICK)),
        Element("11", "Sustained hold", "Button", "", null, setOf(Operation.LONG_PRESS)),
    ), mapOf("test.app" to "Test", "other.app" to "Not allowed"))

    private fun content(operation: String = "SET_TEXT", target: Any = "7", textKey: Any = "greeting") = JSONObject()
        .put("operation", operation).put("target", target).put("text_key", textKey).put("confidence", 0.9)

    private fun envelope(content: String) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("index", 0).put("finish_reason", "stop")
        .put("message", JSONObject().put("role", "assistant").put("content", content))))

    private fun parse(content: JSONObject) = DeepSeekProtocol.parse(envelope(content.toString()).toString(), task, snapshot)

    private fun rejected(raw: String, usedTask: Task = task, usedSnapshot: UiSnapshot = snapshot) {
        try {
            DeepSeekProtocol.parse(raw, usedTask, usedSnapshot)
            fail("Expected rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals("DeepSeek response rejected", e.message)
            assertNull(e.cause)
        }
    }

    @Test fun requestUsesJsonModeAndBoundedChoices() {
        val request = DeepSeekProtocol.request(task, snapshot, emptyList(), "deepseek-flash")
        assertEquals("deepseek-flash", request.getString("model"))
        assertFalse(request.getBoolean("stream"))
        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
        assertEquals(256, request.getInt("max_tokens"))
        assertEquals(0.0, request.getDouble("temperature"), 0.0)
        val messages = request.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("JSON"))
        val state = JSONObject(messages.getJSONObject(1).getString("content"))
        val candidates = state.getJSONObject("action_choices")
        assertEquals(listOf("8"), candidates.getJSONArray("CLICK").toList())
        assertEquals(listOf("10"), candidates.getJSONArray("LONG_CLICK").toList())
        assertEquals(listOf("11"), candidates.getJSONArray("LONG_PRESS").toList())
        assertEquals(listOf("7"), candidates.getJSONArray("SET_TEXT").toList())
        assertEquals(listOf("test.app"), candidates.getJSONArray("OPEN_APP").toList())
        assertFalse(state.getJSONObject("apps").has("other.app"))
        assertEquals("hello", state.getJSONObject("text_values").getString("greeting"))
    }

    @Test fun offAllowlistPageCannotExposeUiOrOfferActions() {
        val request = DeepSeekProtocol.request(task, snapshot.copy(packageName = "other.app"), emptyList(), "deepseek-flash")
        val state = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(0, state.getJSONArray("elements").length())
        val operations = state.getJSONObject("action_choices").keySet()
        assertEquals(setOf("WAIT", "DONE", "BLOCKED", "OPEN_APP"), operations)
    }

    @Test fun textWithoutCallerValuesIsNotOffered() {
        val request = DeepSeekProtocol.request(task.copy(textValues = emptyMap()), snapshot, emptyList(), "deepseek-flash")
        val state = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertFalse(state.getJSONObject("action_choices").has("SET_TEXT"))
    }

    @Test fun requestBoundsActionHistory() {
        val history = (1..30).map { StepRecord(it, Operation.CLICK, "element-$it", true) }
        val request = DeepSeekProtocol.request(task, snapshot, history, "deepseek-flash")
        val state = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        val recent = state.getJSONArray("recent_actions")
        assertEquals(10, recent.length())
        assertEquals("element-21", recent.getJSONObject(0).getString("target"))
    }

    @Test fun parsesOnlyCallerSuppliedTextKey() {
        assertEquals(Decision(Operation.SET_TEXT, "7", "greeting", 0.9), parse(content()))
    }

    @Test fun parsesEverySupportedNonTextAction() {
        listOf(Operation.CLICK to "8", Operation.LONG_CLICK to "10", Operation.LONG_PRESS to "11",
            Operation.SCROLL_FORWARD to "9", Operation.SCROLL_BACKWARD to "9",
            Operation.OPEN_APP to "test.app").forEach { (op, target) ->
            assertEquals(Decision(op, target, null, 0.9), parse(content(op.name, target, JSONObject.NULL)))
        }
        listOf(Operation.BACK, Operation.WAIT, Operation.DONE, Operation.BLOCKED).forEach { op ->
            assertEquals(Decision(op, null, null, 0.9), parse(content(op.name, JSONObject.NULL, JSONObject.NULL)))
        }
    }

    @Test fun longClickCannotChooseClickOnlyTarget() {
        rejected(envelope(content("LONG_CLICK", "8", JSONObject.NULL).toString()).toString())
    }

    @Test fun longClickWithoutSupportedNodesIsNeitherOfferedNorAccepted() {
        val observed = snapshot.copy(elements = snapshot.elements.filter { it.id != "10" })
        val request = DeepSeekProtocol.request(task, observed, emptyList(), "deepseek-flash")
        val state = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertFalse(state.getJSONObject("action_choices").has("LONG_CLICK"))
        rejected(envelope(content("LONG_CLICK", "8", JSONObject.NULL).toString()).toString(), usedSnapshot = observed)
    }

    @Test fun longClickOutsideAllowlistOrWithTextIsRejected() {
        rejected(envelope(content("LONG_CLICK", "10", JSONObject.NULL).toString()).toString(),
            usedSnapshot = snapshot.copy(packageName = "other.app"))
        rejected(envelope(content("LONG_CLICK", "10", "greeting").toString()).toString())
    }

    @Test fun timedLongPressUsesCallerDurationAndCannotSupplyItsOwn() {
        val request = DeepSeekProtocol.request(task.copy(longPressDurationMillis = 2_500), snapshot, emptyList(), "deepseek-flash")
        val state = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(2_500L, state.getLong("long_press_duration_millis"))
        rejected(envelope(content("LONG_PRESS", "11", JSONObject.NULL).put("duration_millis", 9_999).toString()).toString())
    }

    @Test fun timedLongPressRequiresExplicitCompatibleCandidate() {
        rejected(envelope(content("LONG_PRESS", "8", JSONObject.NULL).toString()).toString())
        rejected(envelope(content("LONG_PRESS", "10", JSONObject.NULL).toString()).toString())
        val observed = snapshot.copy(elements = snapshot.elements.filter { it.id != "11" })
        val request = DeepSeekProtocol.request(task, observed, emptyList(), "deepseek-flash")
        val state = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertFalse(state.getJSONObject("action_choices").has("LONG_PRESS"))
        rejected(envelope(content("LONG_PRESS", "11", JSONObject.NULL).toString()).toString(), usedSnapshot = observed)
    }

    @Test fun timedLongPressOutsideAllowlistOrWithTextIsRejected() {
        rejected(envelope(content("LONG_PRESS", "11", JSONObject.NULL).toString()).toString(),
            usedSnapshot = snapshot.copy(packageName = "other.app"))
        rejected(envelope(content("LONG_PRESS", "11", "greeting").toString()).toString())
    }

    @Test fun incompatibleAndUnknownCandidatesAreRejected() {
        listOf(
            content("CLICK", "7", JSONObject.NULL),
            content("CLICK", "missing", JSONObject.NULL),
            content("OPEN_APP", "other.app", JSONObject.NULL),
            content(textKey = "invented text"),
            content("SHELL", JSONObject.NULL, JSONObject.NULL),
            content("click", "8", JSONObject.NULL),
        ).forEach { rejected(envelope(it.toString()).toString()) }
    }

    @Test fun unavailableActionsAreRejected() {
        rejected(envelope(content().toString()).toString(), task.copy(textValues = emptyMap()))
        rejected(envelope(content("CLICK", "8", JSONObject.NULL).toString()).toString(),
            usedSnapshot = snapshot.copy(packageName = "other.app"))
        rejected(envelope(content("OPEN_APP", "test.app", JSONObject.NULL).toString()).toString(),
            usedSnapshot = snapshot.copy(apps = emptyMap()))
    }

    @Test fun unexpectedTargetOrTextKeyIsRejected() {
        listOf(content("WAIT", "7", JSONObject.NULL), content("BACK", "7", JSONObject.NULL),
            content("CLICK", "8", "greeting"), content("DONE", JSONObject.NULL, "greeting"))
            .forEach { rejected(envelope(it.toString()).toString()) }
    }

    @Test fun missingAndAdditionalFieldsAreRejected() {
        for (field in listOf("operation", "target", "text_key", "confidence")) {
            val value = content().apply { remove(field) }
            rejected(envelope(value.toString()).toString())
        }
        rejected(envelope(content().put("text", "unsupplied").toString()).toString())
        rejected(envelope(content().put("x", 100).toString()).toString())
    }

    @Test fun wrongJsonTypesAreRejectedWithoutCoercion() {
        listOf("operation" to 1, "target" to 7, "text_key" to false, "confidence" to "0.9",
            "confidence" to JSONObject.NULL, "target" to JSONArray()).forEach { (field, value) ->
            rejected(envelope(content().put(field, value).toString()).toString())
        }
    }

    @Test fun invalidConfidenceIsRejected() {
        listOf("-0.1", "1.1", "1e999", "NaN", "Infinity").forEach { number ->
            rejected(envelope(content().toString().replace("\"confidence\":0.9", "\"confidence\":$number")).toString())
        }
    }

    @Test fun confidenceBoundsAreAccepted() {
        assertEquals(0.0, parse(content().put("confidence", 0)).confidence, 0.0)
        assertEquals(1.0, parse(content().put("confidence", 1)).confidence, 0.0)
    }

    @Test fun incompleteOrFilteredCompletionsAreRejected() {
        for (finish in listOf("length", "content_filter", "tool_calls", "insufficient_system_resource")) {
            val response = envelope(content().toString())
            response.getJSONArray("choices").getJSONObject(0).put("finish_reason", finish)
            rejected(response.toString())
        }
        val missing = envelope(content().toString())
        missing.getJSONArray("choices").getJSONObject(0).remove("finish_reason")
        rejected(missing.toString())
    }

    @Test fun zeroOrMultipleChoicesAreRejected() {
        rejected(JSONObject().put("choices", JSONArray()).toString())
        val multiple = envelope(content().toString())
        multiple.getJSONArray("choices").put(multiple.getJSONArray("choices").get(0))
        rejected(multiple.toString())
    }

    @Test fun assistantRoleAndStringContentAreRequired() {
        val wrongRole = envelope(content().toString())
        wrongRole.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("role", "user")
        rejected(wrongRole.toString())
        val wrongContent = envelope(content().toString())
        wrongContent.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("content", content())
        rejected(wrongContent.toString())
    }

    @Test fun toolsRefusalAndErrorAreRejected() {
        for (field in listOf("tool_calls", "function_call", "refusal")) {
            val response = envelope(content().toString())
            response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put(field, "sensitive-value")
            rejected(response.toString())
        }
        rejected(envelope(content().toString()).put("error", "sensitive-value").toString())
    }

    @Test fun malformedOrExtraContentIsRejected() {
        listOf("", " ", "[]", "null", "```json\n${content()}\n```", "${content()} extra", "${content()} {}",
            content().toString().dropLast(1), content().toString().replace('"', '\''),
            "/* comment */${content()}", content().toString().dropLast(1) + ",}")
            .forEach { rejected(envelope(it).toString()) }
        rejected(envelope(content().toString()).toString() + " extra")
    }

    @Test fun duplicateKeysAndExcessiveNestingAreRejected() {
        rejected(envelope(content().toString().dropLast(1) + ",\"operation\":\"DONE\"}").toString())
        val response = envelope(content().toString()).toString()
        rejected(response.dropLast(1) + ",\"choices\":[]}")
        rejected(envelope("{\"nested\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}").toString())
    }

    @Test fun responseErrorsNeverExposeRawContentOrCauses() {
        rejected("private-api-key-and-ui-text")
        rejected(envelope("private-api-key-and-ui-text").toString())
    }
}
