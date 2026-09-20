package io.github.jevandroid.sample

import io.github.jevandroid.core.*
import org.junit.Assert.*
import org.junit.Test

class AgentEventFormatterTest {
    @Test fun showsBriefDecisionReasonAndExpectedChange() {
        val event = AgentEvent.Chosen(1, Decision(Operation.CLICK, "search", summary = "Search is visible.\nOpen it.",
            expectedChange = "A search input appears."))
        val message = AgentEventFormatter.format(event)
        assertTrue(message.contains("Reason: Search is visible. Open it."))
        assertTrue(message.contains("Expected change: A search input appears."))
    }

    @Test fun optionalAndOversizedProviderExplanationsStayCompact() {
        val withoutExplanation = AgentEventFormatter.format(AgentEvent.Chosen(1, Decision(Operation.WAIT)))
        assertFalse(withoutExplanation.contains("Reason:"))
        assertFalse(withoutExplanation.contains("Expected change:"))
        val longExplanation = AgentEventFormatter.format(AgentEvent.Chosen(1,
            Decision(Operation.WAIT, summary = "x".repeat(1_000), expectedChange = " \n ")))
        assertEquals(240, longExplanation.substringAfter("Reason: ").length)
        assertFalse(longExplanation.contains("Expected change:"))
    }

    @Test fun distinguishesAndroidAcceptanceFromObservedProgressAndCompletion() {
        val executed = AgentEventFormatter.format(AgentEvent.Executed(StepRecord(1, Operation.CLICK, "button", true)))
        assertTrue(executed.contains("Android accepted=true"))
        assertTrue(executed.contains("completion is checked separately"))
        fun evaluate(outcome: ObservationOutcome) = AgentEventFormatter.format(AgentEvent.Evaluated(
            ActionFeedback(Decision(Operation.CLICK, "button"), null, outcome, 1_500)))
        val unchanged = evaluate(ObservationOutcome.NO_VISIBLE_CHANGE)
        assertTrue(unchanged.contains("no visible UI change"))
        assertTrue(unchanged.contains("reconsider the current page"))
        assertTrue(unchanged.contains("1500 ms"))
        val changed = evaluate(ObservationOutcome.UI_CHANGED)
        assertTrue(changed.contains("visible UI changed"))
        assertTrue(changed.contains("does not by itself confirm completion"))
    }

    @Test fun replanningReportsThatTheRejectedDecisionWasNotDispatched() {
        val message = AgentEventFormatter.format(AgentEvent.Replanning(2,
            "The candidate was already tried on this unchanged page.", 1))
        assertTrue(message.contains("Replanning (1)"))
        assertTrue(message.contains("unchanged page"))
        assertTrue(message.endsWith("No action sent."))
    }

    @Test fun finishedMessagesPreserveTheScenarioVerifierExplanation() {
        val result = RunResult(Status.VERIFIED, 2, "Outcome verified")
        assertEquals("VERIFIED: Scenario outcome checked", AgentEventFormatter.format(
            AgentEvent.Finished(result), "Scenario outcome checked"))
        assertEquals("VERIFIED: Outcome verified", AgentEventFormatter.format(AgentEvent.Finished(result)))
    }
}
