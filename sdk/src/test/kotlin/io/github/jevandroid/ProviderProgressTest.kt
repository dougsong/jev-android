package io.github.jevandroid

import io.github.jevandroid.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderProgressTest {
    private val task = Task("Find the results", setOf("test.app"), mapOf("first" to "alpha", "second" to "beta"))
    private val field = Element("0.1", "Search", "EditText", "alpha", null, setOf(Operation.SET_TEXT))
    private val button = Element("0.2", "Search", "Button", "", false,
        setOf(Operation.CLICK, Operation.LONG_PRESS), "test.app:id/search", selected = false)
    private val alternate = Element("0.3", "Search results", "Button", "", null, setOf(Operation.CLICK))
    private val snapshot = UiSnapshot("same-page", "test.app", listOf(field, button, alternate), mapOf("test.app" to "Test"))
    private val feedback = ActionFeedback(Decision(Operation.CLICK, button.id,
        summary = "Search is available.", expectedChange = "Results become visible."),
        button, ObservationOutcome.NO_VISIBLE_CHANGE, 1_500)
    private val context = DecisionContext(feedback, listOf(ActionSignature(Operation.CLICK, button.id)),
        "The attempted click produced no visible change.", listOf(feedback))

    private fun deepSeekState(request: JSONObject): JSONObject =
        JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))

    private fun envelope(alias: String, textKey: String = ""): String {
        val arguments = JSONObject().put("action_id", alias).put("text_key", textKey).put("confidence", 0.9)
            .put("summary", "Another matching control is visible.").put("expected_change", "Results become visible.")
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
            .put("message", JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(
                JSONObject().put("id", "context_fixture").put("type", "function").put("function", JSONObject()
                    .put("name", "select_action").put("arguments", arguments.toString()))))))).toString()
    }

    private fun alias(choices: DeepSeekChoices, operation: Operation, target: String? = null): String =
        choices.actions.entries.single { it.value.operation == operation && it.value.target == target }.key

    private fun jevResponse(request: JSONObject, operation: Operation, target: String? = null, key: String? = null): JSONObject {
        val questions = request.getJSONObject("questions")
        val answers = JSONObject()
        questions.keys().forEach { name ->
            val ids = questions.getJSONObject(name).getJSONObject("criteria").keySet()
            val selection = when (name) {
                "operation" -> operation.name
                operation.name.lowercase() + "_target" -> requireNotNull(target)
                "text_value" -> key ?: ids.first()
                else -> ids.first()
            }
            answers.put(name, JSONObject().put("choice", selection).put("confidence", 0.9)
                .put("probabilities", JSONObject(ids.associateWith { if (it == selection) 1.0 else 0.0 })))
        }
        return JSONObject().put("answers", answers)
    }

    @Test fun bothProvidersReceiveThePreviousTargetAndObservedEffectWithTheCurrentPage() {
        val deepSeek = DeepSeekProtocol.request(task, snapshot, emptyList(), "fixture", context = context)
        val jev = JevProtocol.request(task, snapshot, emptyList(), "fixture", context)
        val states = listOf(deepSeekState(deepSeek), jev.getJSONObject("state"))
        assertEquals(states[0].getJSONObject("progress").toString(), states[1].getJSONObject("progress").toString())
        for (state in states) {
            assertEquals(3, state.getJSONArray("elements").length())
            val progress = state.getJSONObject("progress")
            assertEquals(context.replanReason, progress.getString("replan_reason"))
            val action = progress.getJSONObject("last_action")
            assertEquals("CLICK", action.getString("operation"))
            assertTrue(action.getBoolean("dispatch_accepted"))
            assertEquals("NO_VISIBLE_CHANGE", action.getString("outcome"))
            assertEquals(1_500L, action.getLong("observed_for_millis"))
            assertEquals("Results become visible.", action.getString("expected_change"))
            val target = action.getJSONObject("target")
            assertEquals("Search", target.getString("label"))
            assertEquals("Button", target.getString("role"))
            assertEquals("", target.getString("value"))
            assertFalse(target.getBoolean("checked"))
            assertFalse(target.getBoolean("selected"))
            assertEquals("test.app:id/search", target.getString("resource_id"))
        }
        assertTrue(deepSeek.getJSONArray("messages").getJSONObject(0).getString("content").contains(ProviderProgress.instructions))
        assertTrue(jev.getJSONObject("questions").getJSONObject("operation").getString("instructions").contains(ProviderProgress.instructions))
    }

    @Test fun selectedStateIsVisibleToBothProvidersAndChangesDeepSeekAliases() {
        val observed = snapshot.copy(elements = listOf(button.copy(selected = true)))
        val current = DeepSeekChoices.create(task, observed)
        val before = DeepSeekChoices.create(task, observed.copy(elements = listOf(button)))
        assertTrue(before.actions.keys.intersect(current.actions.keys).isEmpty())
        val states = listOf(deepSeekState(DeepSeekProtocol.request(task, observed, emptyList(), "fixture")),
            JevProtocol.request(task, observed, emptyList(), "fixture").getJSONObject("state"))
        states.forEach { assertTrue(it.getJSONArray("elements").getJSONObject(0).getBoolean("selected")) }
    }

    @Test fun waitDoesNotEraseEarlierOutcomeAndOutcomeHistoryIsBounded() {
        val wait = ActionFeedback(Decision(Operation.WAIT), null, ObservationOutcome.NO_VISIBLE_CHANGE, 600)
        val recent = (1..20).map { feedback.copy(observedForMillis = it.toLong()) } + wait
        val progress = ProviderProgress.describe(context.copy(lastAction = wait, recentOutcomes = recent))
        assertTrue(progress.getJSONObject("last_action").isNull("target"))
        val outcomes = progress.getJSONArray("recent_outcomes")
        assertEquals(10, outcomes.length())
        assertEquals(12L, outcomes.getJSONObject(0).getLong("observed_for_millis"))
        assertEquals("CLICK", outcomes.getJSONObject(8).getString("operation"))
        assertEquals("WAIT", outcomes.getJSONObject(9).getString("operation"))
    }

    @Test fun exclusionRemovesTheAttemptedActionWhilePreservingOtherCurrentChoices() {
        val choices = DeepSeekChoices.create(task, snapshot, context)
        assertFalse(choices.actions.values.any { it.operation == Operation.CLICK && it.target == button.id })
        assertTrue(choices.actions.values.any { it.operation == Operation.LONG_PRESS && it.target == button.id })
        assertTrue(choices.actions.values.any { it.operation == Operation.CLICK && it.target == alternate.id })
        assertTrue(choices.actions.values.any { it.operation == Operation.WAIT })
        val questions = JevProtocol.request(task, snapshot, emptyList(), "fixture", context).getJSONObject("questions")
        assertEquals(setOf(alternate.id), questions.getJSONObject("click_target").getJSONObject("criteria").keySet())
        assertEquals(setOf(button.id), questions.getJSONObject("long_press_target").getJSONObject("criteria").keySet())
    }

    @Test fun differentExclusionsCannotRemapAliasesOnTheSamePage() {
        val original = DeepSeekChoices.create(task, snapshot)
        val current = DeepSeekChoices.create(task, snapshot, context)
        assertTrue(original.actions.keys.intersect(current.actions.keys).isEmpty())
        try {
            DeepSeekProtocol.parse(envelope(alias(original, Operation.CLICK, button.id)), task, snapshot, current)
            fail("A stale catalog must not select another action after filtering")
        } catch (failure: DeepSeekResponseException) {
            assertEquals(DeepSeekRejectionReason.INVALID_TARGET, failure.reason)
        }
        val exclusions = listOf(ActionSignature(Operation.CLICK, button.id), ActionSignature(Operation.BACK))
        assertEquals(DeepSeekChoices.create(task, snapshot, context.copy(excludedActions = exclusions)).actions,
            DeepSeekChoices.create(task, snapshot, context.copy(excludedActions = exclusions.reversed() + exclusions.first())).actions)
    }

    @Test fun aDifferentSuppliedTextRemainsAvailableForTheSameField() {
        val excluded = context.copy(excludedActions = listOf(ActionSignature(Operation.SET_TEXT, field.id, "first")))
        val choices = DeepSeekChoices.create(task, snapshot, excluded)
        val textAlias = alias(choices, Operation.SET_TEXT, field.id)
        val allowed = choices.describe().getJSONArray("elements").getJSONObject(0).getJSONArray("allowed_text_keys")
        assertEquals(listOf("second"), allowed.toList())
        assertEquals("second", DeepSeekProtocol.parse(envelope(textAlias, "second"), task, snapshot, choices).textKey)
        try {
            DeepSeekProtocol.parse(envelope(textAlias, "first"), task, snapshot, choices)
            fail("An excluded text tuple must not execute")
        } catch (failure: DeepSeekResponseException) {
            assertEquals(DeepSeekRejectionReason.INVALID_TEXT_KEY, failure.reason)
        }
        val request = JevProtocol.request(task, snapshot, emptyList(), "fixture", excluded)
        assertEquals(setOf("second"), request.getJSONObject("questions").getJSONObject("text_value").getJSONObject("criteria").keySet())
        assertEquals("second", JevProtocol.parse(jevResponse(request, Operation.SET_TEXT, field.id, "second"), request).textKey)
    }

    @Test fun jevRejectsAnExcludedTextPairEvenWhenThatKeyIsAllowedOnAnotherField() {
        val secondField = field.copy(id = "0.4", label = "Another field")
        val observed = snapshot.copy(elements = snapshot.elements + secondField)
        val excluded = context.copy(excludedActions = listOf(ActionSignature(Operation.SET_TEXT, field.id, "first")))
        val request = JevProtocol.request(task, observed, emptyList(), "fixture", excluded)
        assertEquals(setOf("first", "second"), request.getJSONObject("questions").getJSONObject("text_value").getJSONObject("criteria").keySet())
        try {
            JevProtocol.parse(jevResponse(request, Operation.SET_TEXT, field.id, "first"), request)
            fail("Independent target/value heads must not bypass the exact tuple exclusion")
        } catch (_: IllegalArgumentException) { }
        assertEquals(secondField.id, JevProtocol.parse(jevResponse(request, Operation.SET_TEXT, secondField.id, "first"), request).target)
    }

    @Test fun excludingEveryTextValueRemovesOnlyThatFieldsSetTextAction() {
        val excluded = context.copy(excludedActions = task.textValues.keys.map { ActionSignature(Operation.SET_TEXT, field.id, it) })
        val choices = DeepSeekChoices.create(task, snapshot, excluded)
        assertFalse(choices.actions.values.any { it.operation == Operation.SET_TEXT })
        assertTrue(choices.actions.values.any { it.operation == Operation.CLICK })
        val questions = JevProtocol.request(task, snapshot, emptyList(), "fixture", excluded).getJSONObject("questions")
        assertFalse(questions.has("set_text_target"))
        assertFalse(questions.has("text_value"))
    }

    @Test fun excludedOpenAppAndBackAreNotOffered() {
        val excluded = context.copy(excludedActions = listOf(ActionSignature(Operation.OPEN_APP, "test.app"), ActionSignature(Operation.BACK)))
        val choices = DeepSeekChoices.create(task, snapshot, excluded)
        assertFalse(choices.actions.values.any { it.operation in setOf(Operation.OPEN_APP, Operation.BACK) })
        val questions = JevProtocol.request(task, snapshot, emptyList(), "fixture", excluded).getJSONObject("questions")
        assertFalse(questions.has("open_app_target"))
        assertFalse(questions.getJSONObject("operation").getJSONObject("criteria").has("BACK"))
    }

    @Test fun deepSeekProviderDispatchesTheContextualInterfaceAndBindsItsResponseToFilteredChoices() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val choices = DeepSeekChoices.create(task, snapshot, context)
            server.enqueue(MockResponse().setBody(envelope(alias(choices, Operation.CLICK, alternate.id))))
            val provider: DecisionProvider = DeepSeekProvider("fixture-key", "fixture-model", ProviderTransport(), server.url("/decision").toString())
            val result = provider.decideWithContext(task, snapshot, emptyList(), context)
            assertEquals(alternate.id, result.target)
            assertEquals("Another matching control is visible.", result.summary)
            val request = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("NO_VISIBLE_CHANGE", deepSeekState(request).getJSONObject("progress").getJSONObject("last_action").getString("outcome"))
            val offered = request.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters")
                .getJSONObject("properties").getJSONObject("action_id").getJSONArray("enum").toList().toSet()
            assertEquals(choices.actions.keys, offered)
        }
    }
}
