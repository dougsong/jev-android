package io.github.jevandroid

import io.github.jevandroid.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JevProtocolTest {
    private val task = Task("Fill field", setOf("test.app"), mapOf("greeting" to "hello"))
    private val snapshot = UiSnapshot("v1", "test.app", listOf(
        Element("7", "Message", "EditText", "", null, setOf(Operation.SET_TEXT)),
        Element("8", "Save", "Button", "", null, setOf(Operation.CLICK)),
        Element("9", "Hold", "Button", "", null, setOf(Operation.LONG_CLICK)),
        Element("10", "Sustained hold", "Button", "", null, setOf(Operation.LONG_PRESS)),
    ))
    private fun request() = JevProtocol.request(task, snapshot, emptyList(), "jev-latest")
    private fun response(request: JSONObject, op: String): JSONObject {
        val questions = request.getJSONObject("questions")
        val answers = JSONObject()
        questions.keys().forEach { name ->
            val choices = questions.getJSONObject(name).getJSONObject("criteria").keys().asSequence().toList()
            val selected = if (name == "operation") op else choices.first()
            answers.put(name, JSONObject().put("choice", selected).put("confidence", 0.9)
                .put("probabilities", JSONObject().apply { choices.forEach { put(it, if (it == selected) 1.0 else 0.0) } }))
        }
        return JSONObject().put("answers", answers)
    }
    @Test fun batchesCompatibleTargetsInOneRequest() {
        val q = request().getJSONObject("questions")
        assertEquals(setOf("7"), q.getJSONObject("set_text_target").getJSONObject("criteria").keys().asSequence().toSet())
        assertEquals(setOf("8"), q.getJSONObject("click_target").getJSONObject("criteria").keys().asSequence().toSet())
        assertEquals(setOf("9"), q.getJSONObject("long_click_target").getJSONObject("criteria").keys().asSequence().toSet())
        assertEquals(setOf("10"), q.getJSONObject("long_press_target").getJSONObject("criteria").keys().asSequence().toSet())
        assertTrue(q.has("text_value"))
    }
    @Test fun parsesLongClickOnlyForAdvertisedTarget() {
        val request = request()
        val response = response(request, "LONG_CLICK")
        response.getJSONObject("answers").getJSONObject("long_click_target").put("confidence", 0.7)
        assertEquals(Decision(Operation.LONG_CLICK, "9", null, 0.7), JevProtocol.parse(response, request))
    }
    @Test fun longClickCannotChooseClickOnlyTarget() {
        val request = request()
        val response = response(request, "LONG_CLICK")
        response.getJSONObject("answers").getJSONObject("long_click_target").put("choice", "8")
        try { JevProtocol.parse(response, request); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun longClickWithoutSupportedNodesIsNeitherOfferedNorAccepted() {
        val request = JevProtocol.request(task, snapshot.copy(elements = snapshot.elements.filter { it.id != "9" }),
            emptyList(), "jev-latest")
        val questions = request.getJSONObject("questions")
        assertFalse(questions.has("long_click_target"))
        assertFalse(questions.getJSONObject("operation").getJSONObject("criteria").has("LONG_CLICK"))
        try { JevProtocol.parse(response(request, "LONG_CLICK"), request); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun parsesTimedLongPressAndExposesCallerDuration() {
        val request = JevProtocol.request(task.copy(longPressDurationMillis = 2_500), snapshot, emptyList(), "jev-latest")
        assertEquals(2_500L, request.getJSONObject("state").getLong("long_press_duration_millis"))
        assertEquals(Decision(Operation.LONG_PRESS, "10", null, 0.9), JevProtocol.parse(response(request, "LONG_PRESS"), request))
    }
    @Test fun timedLongPressCannotChooseUnadvertisedClickTarget() {
        val request = request()
        val response = response(request, "LONG_PRESS")
        response.getJSONObject("answers").getJSONObject("long_press_target").put("choice", "8")
        try { JevProtocol.parse(response, request); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun timedLongPressWithoutSupportedNodesIsNeitherOfferedNorAccepted() {
        val request = JevProtocol.request(task, snapshot.copy(elements = snapshot.elements.filter { it.id != "10" }),
            emptyList(), "jev-latest")
        val questions = request.getJSONObject("questions")
        assertFalse(questions.has("long_press_target"))
        assertFalse(questions.getJSONObject("operation").getJSONObject("criteria").has("LONG_PRESS"))
        try { JevProtocol.parse(response(request, "LONG_PRESS"), request); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun selectedTargetConfidenceIsRespected() {
        val request = request()
        val response = response(request, "SET_TEXT")
        response.getJSONObject("answers").getJSONObject("set_text_target").put("confidence", 0.1)
        val decision = JevProtocol.parse(response, request)
        assertEquals("greeting", decision.textKey); assertEquals(0.1, decision.confidence, 0.0)
    }
    @Test fun unusedHeadsCannotExecute() {
        val request = request()
        val response = response(request, "CLICK")
        response.getJSONObject("answers").put("set_text_target", JSONObject().put("choice", "malicious"))
        val d = JevProtocol.parse(response, request)
        assertEquals("8", d.target); assertNull(d.textKey)
    }
    @Test fun invalidProbabilityDistributionIsRejected() {
        val request = request()
        val response = response(request, "CLICK")
        response.getJSONObject("answers").getJSONObject("click_target").getJSONObject("probabilities").put("8", 0.2)
        try { JevProtocol.parse(response, request); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun unknownOperationIsRejected() {
        val request = request()
        val response = response(request, "CLICK")
        response.getJSONObject("answers").getJSONObject("operation").put("choice", "SHELL")
        try { JevProtocol.parse(response, request); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun textOperationAbsentWithoutSuppliedValues() {
        val q = JevProtocol.request(task.copy(textValues = emptyMap()), snapshot, emptyList(), "jev-latest").getJSONObject("questions")
        assertFalse(q.has("text_value")); assertFalse(q.getJSONObject("operation").getJSONObject("criteria").has("SET_TEXT"))
    }
    @Test fun requestFiltersOffAllowlistUiAndAppsAndBoundsHistory() {
        val history = (1..30).map { StepRecord(it, Operation.CLICK, "element-$it", true) }
        val observed = snapshot.copy(packageName = "other.app", apps = mapOf("test.app" to "Test", "other.app" to "Other"))
        val request = JevProtocol.request(task, observed, history, "jev-latest")
        assertEquals(0, request.getJSONObject("state").getJSONArray("elements").length())
        assertEquals(10, request.getJSONObject("state").getJSONArray("recent_actions").length())
        val questions = request.getJSONObject("questions")
        assertFalse(questions.has("click_target"))
        assertFalse(questions.has("long_click_target"))
        assertFalse(questions.has("long_press_target"))
        assertEquals(setOf("test.app"), questions.getJSONObject("open_app_target").getJSONObject("criteria").keySet())
    }
}
