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
    private val choices = DeepSeekChoices.create(task, snapshot)

    private fun alias(operation: Operation, target: String? = null): String = choices.actions.entries.single {
        it.value.operation == operation && it.value.target == target
    }.key

    private fun arguments(
        actionId: Any = alias(Operation.SET_TEXT, "7"),
        textKey: Any = "greeting",
    ) = JSONObject().put("action_id", actionId).put("text_key", textKey).put("confidence", 0.9)

    private fun envelope(arguments: String) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("index", 0).put("finish_reason", "tool_calls")
        .put("message", JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "call_fixture").put("type", "function")
                .put("function", JSONObject().put("name", "select_action").put("arguments", arguments)))))))

    private fun message(response: JSONObject): JSONObject =
        response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")

    private fun tool(response: JSONObject): JSONObject = message(response).getJSONArray("tool_calls").getJSONObject(0)

    private fun parse(arguments: JSONObject): Decision =
        DeepSeekProtocol.parse(envelope(arguments.toString()).toString(), task, snapshot, choices)

    private fun rejected(raw: String, expectedReason: DeepSeekRejectionReason? = null) {
        try {
            DeepSeekProtocol.parse(raw, task, snapshot, choices)
            fail("Expected rejection")
        } catch (e: DeepSeekResponseException) {
            if (expectedReason != null) assertEquals(expectedReason, e.reason)
            assertEquals(1, e.attempts)
            assertTrue(e.message!!.contains("[${e.reason.name}]"))
            assertTrue(e.message!!.contains(e.reason.explanation))
            assertTrue(e.message!!.contains("no action sent for this decision"))
            assertNull(e.cause)
            assertFalse(e.stackTraceToString().contains("private-api-key-and-ui-text"))
            assertFalse(e.stackTraceToString().contains("sensitive-value"))
        }
    }

    @Test fun requestForcesOneStrictToolWithBoundedActionIds() {
        val request = DeepSeekProtocol.request(task, snapshot, emptyList(), "deepseek-flash", choices = choices)
        assertEquals("deepseek-flash", request.getString("model"))
        assertFalse(request.getBoolean("stream"))
        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertFalse(request.has("response_format"))
        assertEquals(1024, request.getInt("max_tokens"))
        assertEquals(0.0, request.getDouble("temperature"), 0.0)
        val forcedTool = request.getJSONObject("tool_choice")
        assertEquals("function", forcedTool.getString("type"))
        assertEquals("select_action", forcedTool.getJSONObject("function").getString("name"))
        val tools = request.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertEquals("function", tools.getJSONObject(0).getString("type"))
        val function = tools.getJSONObject(0).getJSONObject("function")
        assertEquals("select_action", function.getString("name"))
        assertTrue(function.getBoolean("strict"))
        val schema = function.getJSONObject("parameters")
        assertEquals("object", schema.getString("type"))
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(setOf("action_id", "text_key", "confidence"), schema.getJSONArray("required").toList().toSet())
        val properties = schema.getJSONObject("properties")
        assertEquals(setOf("action_id", "text_key", "confidence"), properties.keySet())
        assertEquals("string", properties.getJSONObject("action_id").getString("type"))
        assertEquals(choices.actions.keys, properties.getJSONObject("action_id").getJSONArray("enum").toList().toSet())
        assertEquals("string", properties.getJSONObject("text_key").getString("type"))
        assertEquals(setOf("", "greeting"), properties.getJSONObject("text_key").getJSONArray("enum").toList().toSet())
        assertEquals("number", properties.getJSONObject("confidence").getString("type"))
    }

    @Test fun requestDescribesChoicesWithoutRawNodeIds() {
        val request = DeepSeekProtocol.request(task, snapshot, emptyList(), "deepseek-flash", choices = choices)
        val messages = request.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("select_action"))
        val state = JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals("hello", state.getJSONObject("text_values").getString("greeting"))
        assertFalse(state.toString().contains("Not allowed"))
        val elements = state.getJSONArray("elements")
        assertEquals(snapshot.elements.size, elements.length())
        for (index in 0 until elements.length()) assertFalse(elements.getJSONObject(index).has("id"))
        val save = (0 until elements.length()).map { elements.getJSONObject(it) }.single { it.getString("label") == "Save" }
        assertEquals(alias(Operation.CLICK, "8"), save.getJSONObject("actions").getString("CLICK"))
        assertFalse(state.has("action_choices"))
    }

    @Test fun offAllowlistPageCannotExposeUiOrOfferNodeActions() {
        val observed = snapshot.copy(packageName = "other.app")
        val catalog = DeepSeekChoices.create(task, observed)
        val request = DeepSeekProtocol.request(task, observed, emptyList(), "deepseek-flash", choices = catalog)
        val state = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(0, state.getJSONArray("elements").length())
        assertEquals(setOf(Operation.WAIT, Operation.DONE, Operation.BLOCKED, Operation.OPEN_APP),
            catalog.actions.values.map { it.operation }.toSet())
    }

    @Test fun textWithoutCallerValuesIsNotOfferedAndHasOnlyEmptySentinel() {
        val emptyTask = task.copy(textValues = emptyMap())
        val catalog = DeepSeekChoices.create(emptyTask, snapshot)
        val request = DeepSeekProtocol.request(emptyTask, snapshot, emptyList(), "deepseek-flash", choices = catalog)
        assertFalse(catalog.actions.values.any { it.operation == Operation.SET_TEXT })
        val textSchema = request.getJSONArray("tools").getJSONObject(0).getJSONObject("function")
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("text_key")
        assertEquals(listOf(""), textSchema.getJSONArray("enum").toList())
    }

    @Test fun requestBoundsActionHistory() {
        val history = (1..30).map { StepRecord(it, Operation.CLICK, "element-$it", true) }
        val request = DeepSeekProtocol.request(task, snapshot, history, "deepseek-flash", choices = choices)
        val state = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        val recent = state.getJSONArray("recent_actions")
        assertEquals(10, recent.length())
    }

    @Test fun parsesOnlyCallerSuppliedTextKey() {
        assertEquals(Decision(Operation.SET_TEXT, "7", "greeting", 0.9), parse(arguments()))
    }

    @Test fun parsesEverySupportedNonTextAction() {
        listOf(Operation.CLICK to "8", Operation.LONG_CLICK to "10", Operation.LONG_PRESS to "11",
            Operation.SCROLL_FORWARD to "9", Operation.SCROLL_BACKWARD to "9",
            Operation.OPEN_APP to "test.app").forEach { (operation, target) ->
            assertEquals(Decision(operation, target, null, 0.9), parse(arguments(alias(operation, target), "")))
        }
        listOf(Operation.BACK, Operation.WAIT, Operation.DONE, Operation.BLOCKED).forEach { operation ->
            assertEquals(Decision(operation, null, null, 0.9), parse(arguments(alias(operation), "")))
        }
    }

    @Test fun rawPathsLabelsPackagesAndUnrecognizedAliasesAreRejected() {
        for (target in listOf("7", "8", "0/2/1", "Save", "test.app", "a_unknown", "", "sensitive-value")) {
            rejected(envelope(arguments(target, "").toString()).toString(), DeepSeekRejectionReason.INVALID_TARGET)
        }
    }

    @Test fun actionAliasCannotBeOverriddenWithOperationOrTarget() {
        for ((field, value) in listOf("operation" to "LONG_PRESS", "target" to "7", "x" to "100", "text" to "hello")) {
            rejected(envelope(arguments(alias(Operation.CLICK, "8"), "").put(field, value).toString()).toString(),
                DeepSeekRejectionReason.INVALID_SCHEMA)
        }
    }

    @Test fun timedLongPressUsesCallerDurationAndCannotSupplyItsOwn() {
        val request = DeepSeekProtocol.request(task.copy(longPressDurationMillis = 2_500), snapshot, emptyList(), "deepseek-flash")
        val state = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(2_500L, state.getLong("long_press_duration_millis"))
        rejected(envelope(arguments(alias(Operation.LONG_PRESS, "11"), "").put("duration_millis", 9_999).toString()).toString(),
            DeepSeekRejectionReason.INVALID_SCHEMA)
    }

    @Test fun nonTextActionsRejectAnyNonemptyTextKey() {
        for (operation in listOf(Operation.CLICK, Operation.LONG_CLICK, Operation.LONG_PRESS, Operation.BACK, Operation.DONE)) {
            val action = choices.actions.entries.first { it.value.operation == operation }
            rejected(envelope(arguments(action.key, "greeting").toString()).toString(), DeepSeekRejectionReason.INVALID_TEXT_KEY)
        }
    }

    @Test fun textActionsRejectEmptySentinelLiteralValueAndUnknownKeys() {
        for (textKey in listOf("", "hello", "sensitive-value")) {
            rejected(envelope(arguments(textKey = textKey).toString()).toString(), DeepSeekRejectionReason.INVALID_TEXT_KEY)
        }
    }

    @Test fun missingAndAdditionalFieldsAreRejected() {
        for (field in listOf("action_id", "text_key", "confidence")) {
            rejected(envelope(arguments().apply { remove(field) }.toString()).toString(), DeepSeekRejectionReason.INVALID_SCHEMA)
        }
        rejected(envelope(arguments().put("explanation", "sensitive-value").toString()).toString(),
            DeepSeekRejectionReason.INVALID_SCHEMA)
    }

    @Test fun wrongJsonTypesAreRejectedWithoutCoercion() {
        for ((field, value) in listOf("action_id" to 7, "action_id" to JSONArray(), "action_id" to JSONObject.NULL,
            "text_key" to false, "text_key" to JSONObject.NULL)) {
            rejected(envelope(arguments().put(field, value).toString()).toString(), DeepSeekRejectionReason.INVALID_SCHEMA)
        }
        for (value in listOf("0.9", JSONObject.NULL, true)) {
            rejected(envelope(arguments().put("confidence", value).toString()).toString(), DeepSeekRejectionReason.INVALID_CONFIDENCE)
        }
    }

    @Test fun invalidConfidenceIsRejected() {
        for (number in listOf("-0.1", "1.1", "1e999", "NaN", "Infinity")) {
            rejected(envelope(arguments().toString().replace("\"confidence\":0.9", "\"confidence\":$number")).toString())
        }
    }

    @Test fun confidenceBoundsAreAccepted() {
        assertEquals(0.0, parse(arguments().put("confidence", 0)).confidence, 0.0)
        assertEquals(1.0, parse(arguments().put("confidence", 1)).confidence, 0.0)
    }

    @Test fun zeroOrMultipleChoicesAreRejected() {
        rejected(JSONObject().put("choices", JSONArray()).toString(), DeepSeekRejectionReason.INVALID_ENVELOPE)
        val multiple = envelope(arguments().toString())
        multiple.getJSONArray("choices").put(multiple.getJSONArray("choices").get(0))
        rejected(multiple.toString(), DeepSeekRejectionReason.INVALID_ENVELOPE)
    }

    @Test fun assistantRoleAndContentTypeAreValidated() {
        val wrongRole = envelope(arguments().toString())
        message(wrongRole).put("role", "user")
        rejected(wrongRole.toString(), DeepSeekRejectionReason.INVALID_ENVELOPE)
        for (content in listOf(JSONObject(), JSONArray(), false, 1)) {
            val response = envelope(arguments().toString())
            message(response).put("content", content)
            rejected(response.toString(), DeepSeekRejectionReason.INVALID_ENVELOPE)
        }
    }

    @Test fun optionalContentIsNeverInterpretedAsAnotherAction() {
        for (content in listOf(JSONObject.NULL, "", " ", "sensitive-value", "{\"operation\":\"CLICK\",\"target\":\"8\"}")) {
            val response = envelope(arguments().toString())
            message(response).put("content", content)
            assertEquals(Decision(Operation.SET_TEXT, "7", "greeting", 0.9),
                DeepSeekProtocol.parse(response.toString(), task, snapshot, choices))
        }
        val response = envelope(arguments().toString())
        message(response).remove("content")
        assertEquals(Operation.SET_TEXT, DeepSeekProtocol.parse(response.toString(), task, snapshot, choices).operation)
    }

    @Test fun exactlyOneKnownFunctionCallIsRequired() {
        val wrongName = envelope(arguments().toString())
        tool(wrongName).getJSONObject("function").put("name", "shell")
        rejected(wrongName.toString(), DeepSeekRejectionReason.UNEXPECTED_TOOLS)
        val multiple = envelope(arguments().toString())
        message(multiple).getJSONArray("tool_calls").put(tool(multiple))
        rejected(multiple.toString(), DeepSeekRejectionReason.UNEXPECTED_TOOLS)
        for (value in listOf(JSONObject(), false, "sensitive-value")) {
            val response = envelope(arguments().toString())
            message(response).put("tool_calls", value)
            rejected(response.toString(), DeepSeekRejectionReason.UNEXPECTED_TOOLS)
        }
    }

    @Test fun malformedToolIdentityAndFunctionAreTerminal() {
        for (id in listOf("", " ", JSONObject.NULL, 1)) {
            val response = envelope(arguments().toString())
            tool(response).put("id", id)
            rejected(response.toString(), DeepSeekRejectionReason.UNEXPECTED_TOOLS)
        }
        for (field in listOf("id", "type", "function")) {
            val response = envelope(arguments().toString())
            tool(response).remove(field)
            rejected(response.toString(), DeepSeekRejectionReason.UNEXPECTED_TOOLS)
        }
        val wrongType = envelope(arguments().toString())
        tool(wrongType).put("type", "computer")
        rejected(wrongType.toString(), DeepSeekRejectionReason.UNEXPECTED_TOOLS)
        val missingName = envelope(arguments().toString())
        tool(missingName).getJSONObject("function").remove("name")
        rejected(missingName.toString(), DeepSeekRejectionReason.UNEXPECTED_TOOLS)
    }

    @Test fun missingOrEmptyToolCallsAreClassifiedAsEmpty() {
        for (calls in listOf(JSONObject.NULL, JSONArray())) {
            val response = envelope(arguments().toString())
            message(response).put("tool_calls", calls)
            rejected(response.toString(), DeepSeekRejectionReason.EMPTY_CONTENT)
        }
        val missing = envelope(arguments().toString())
        message(missing).remove("tool_calls")
        rejected(missing.toString(), DeepSeekRejectionReason.EMPTY_CONTENT)
    }

    @Test fun blankArgumentsAreClassifiedAsEmpty() {
        for (empty in listOf("", " \n\t")) {
            rejected(envelope(empty).toString(), DeepSeekRejectionReason.EMPTY_CONTENT)
        }
    }

    @Test fun argumentsMustBeAJsonStringNotAnAlreadyParsedObject() {
        for (value in listOf(arguments(), JSONArray(), false, 1, JSONObject.NULL)) {
            val response = envelope(arguments().toString())
            tool(response).getJSONObject("function").put("arguments", value)
            rejected(response.toString())
        }
        val missing = envelope(arguments().toString())
        tool(missing).getJSONObject("function").remove("arguments")
        rejected(missing.toString())
    }

    @Test fun malformedOrExtraArgumentsAreRejected() {
        for (content in listOf("[]", "null", "```json\n${arguments()}\n```", "${arguments()} extra", "${arguments()} {}",
            arguments().toString().dropLast(1), arguments().toString().replace('"', '\''),
            "/* comment */${arguments()}", arguments().toString().dropLast(1) + ",}")) {
            rejected(envelope(content).toString(), DeepSeekRejectionReason.INVALID_JSON)
        }
        rejected(envelope(arguments().toString()).toString() + " extra", DeepSeekRejectionReason.INVALID_ENVELOPE)
    }

    @Test fun duplicateKeysAndExcessiveNestingAreRejected() {
        rejected(envelope(arguments().toString().dropLast(1) + ",\"action_id\":\"sensitive-value\"}").toString(),
            DeepSeekRejectionReason.INVALID_JSON)
        val response = envelope(arguments().toString()).toString()
        rejected(response.dropLast(1) + ",\"choices\":[]}", DeepSeekRejectionReason.INVALID_ENVELOPE)
        rejected(envelope("{\"nested\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}").toString(),
            DeepSeekRejectionReason.INVALID_JSON)
    }

    @Test fun responseErrorsNeverExposeRawContentOrCauses() {
        rejected("private-api-key-and-ui-text", DeepSeekRejectionReason.INVALID_ENVELOPE)
        rejected(envelope("private-api-key-and-ui-text").toString(), DeepSeekRejectionReason.INVALID_JSON)
    }

    @Test fun nullOptionalEnvelopeMetadataDoesNotRejectAValidDecision() {
        val response = envelope(arguments().toString()).put("error", JSONObject.NULL)
        for (field in listOf("function_call", "refusal")) message(response).put(field, JSONObject.NULL)
        assertEquals(Decision(Operation.SET_TEXT, "7", "greeting", 0.9),
            DeepSeekProtocol.parse(response.toString(), task, snapshot, choices))
    }

    @Test fun legacyFunctionMetadataIsTerminal() {
        for (value in listOf(JSONObject(), false, "")) {
            val response = envelope(arguments().toString())
            message(response).put("function_call", value)
            rejected(response.toString(), DeepSeekRejectionReason.UNEXPECTED_TOOLS)
        }
    }

    @Test fun apiErrorsAndRefusalsHaveTerminalCategories() {
        rejected(envelope(arguments().toString()).put("error", JSONObject().put("message", "sensitive-value")).toString(),
            DeepSeekRejectionReason.RESPONSE_ERROR)
        for (refusal in listOf("sensitive-value", "")) {
            val response = envelope(arguments().toString())
            message(response).put("refusal", refusal)
            rejected(response.toString(), DeepSeekRejectionReason.REFUSAL)
        }
    }

    @Test fun incompleteCompletionsHaveSeparateSanitizedCategories() {
        for ((finish, reason) in listOf(
            "length" to DeepSeekRejectionReason.TRUNCATED,
            "content_filter" to DeepSeekRejectionReason.FILTERED,
            "stop" to DeepSeekRejectionReason.INCOMPLETE_COMPLETION,
            "insufficient_system_resource" to DeepSeekRejectionReason.INCOMPLETE_COMPLETION,
            "sensitive-value" to DeepSeekRejectionReason.INCOMPLETE_COMPLETION,
        )) {
            val response = envelope(arguments().toString())
            response.getJSONArray("choices").getJSONObject(0).put("finish_reason", finish)
            rejected(response.toString(), reason)
        }
        val missing = envelope(arguments().toString())
        missing.getJSONArray("choices").getJSONObject(0).remove("finish_reason")
        rejected(missing.toString(), DeepSeekRejectionReason.INVALID_ENVELOPE)
    }

    @Test fun refusalAndUnsupportedToolCannotBeRepairedAsTruncation() {
        val refusal = envelope(arguments().toString())
        refusal.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length")
        message(refusal).put("refusal", "sensitive-value")
        rejected(refusal.toString(), DeepSeekRejectionReason.REFUSAL)
        val unknownTool = envelope(arguments().toString())
        unknownTool.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length")
        tool(unknownTool).getJSONObject("function").put("name", "shell")
        rejected(unknownTool.toString(), DeepSeekRejectionReason.UNEXPECTED_TOOLS)
    }

    @Test fun plainJsonContentIsNotACommandFallback() {
        val response = envelope(arguments().toString())
        response.getJSONArray("choices").getJSONObject(0).put("finish_reason", "stop")
        message(response).remove("tool_calls")
        message(response).put("content", arguments().toString())
        rejected(response.toString(), DeepSeekRejectionReason.EMPTY_CONTENT)
    }

    @Test fun onlyCorrectableDecisionRejectionsAllowRepair() {
        assertEquals(setOf(DeepSeekRejectionReason.EMPTY_CONTENT, DeepSeekRejectionReason.TRUNCATED,
            DeepSeekRejectionReason.INVALID_JSON, DeepSeekRejectionReason.INVALID_SCHEMA,
            DeepSeekRejectionReason.UNSUPPORTED_OPERATION, DeepSeekRejectionReason.INVALID_TARGET,
            DeepSeekRejectionReason.INVALID_TEXT_KEY, DeepSeekRejectionReason.INVALID_CONFIDENCE),
            DeepSeekRejectionReason.entries.filter { it.canRepair }.toSet())
    }

    @Test fun repairPromptContainsOnlyFixedDiagnosticAndPreservesStateAndSchema() {
        val original = DeepSeekProtocol.request(task, snapshot, emptyList(), "deepseek-flash", choices = choices)
        val repaired = DeepSeekProtocol.request(task, snapshot, emptyList(), "deepseek-flash",
            DeepSeekRejectionReason.INVALID_TARGET, choices)
        assertEquals(1024, repaired.getInt("max_tokens"))
        val originalMessages = original.getJSONArray("messages")
        val repairedMessages = repaired.getJSONArray("messages")
        assertEquals(2, repairedMessages.length())
        assertEquals(originalMessages.getJSONObject(1).toString(), repairedMessages.getJSONObject(1).toString())
        assertEquals(original.getJSONArray("tools").toString(), repaired.getJSONArray("tools").toString())
        assertEquals(original.getJSONObject("tool_choice").toString(), repaired.getJSONObject("tool_choice").toString())
        val instructions = repairedMessages.getJSONObject(0).getString("content")
        assertTrue(instructions.startsWith(originalMessages.getJSONObject(0).getString("content")))
        assertTrue(instructions.contains("INVALID_TARGET"))
        assertTrue(instructions.contains(DeepSeekRejectionReason.INVALID_TARGET.explanation))
    }
}
