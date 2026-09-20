package io.github.jevandroid.sample

import io.github.jevandroid.core.*

/** Compact observable feedback and provider explanations for the sample's run log. */
internal object AgentEventFormatter {
    private const val MAX_EXPLANATION_LENGTH = 240

    fun format(event: AgentEvent, finishedMessage: String? = null): String = when (event) {
        is AgentEvent.Observed -> "Observed ${event.packageName}: ${event.elementCount} elements"
        is AgentEvent.Chosen -> buildString {
            append("Selected ${event.decision.operation}, confidence=${event.decision.confidence}")
            brief(event.decision.summary)?.let { append("\nReason: $it") }
            brief(event.decision.expectedChange)?.let { append("\nExpected change: $it") }
        }
        is AgentEvent.Refreshing ->
            "UI changed before ${event.operation}; no action sent. Observing again (${event.attempt}/3)."
        is AgentEvent.Executed ->
            "Step ${event.record.step}: Android accepted=${event.record.accepted}; task completion is checked separately."
        is AgentEvent.Evaluated -> {
            val effect = when (event.feedback.outcome) {
                ObservationOutcome.UI_CHANGED -> "the visible UI changed; this does not by itself confirm completion"
                ObservationOutcome.NO_VISIBLE_CHANGE -> "no visible UI change; reconsider the current page before another action"
            }
            "After ${event.feedback.decision.operation}: $effect (observed for ${event.feedback.observedForMillis} ms)."
        }
        is AgentEvent.Replanning ->
            "Replanning (${event.attempt}): ${brief(event.reason).orEmpty()} No action sent."
        is AgentEvent.Finished -> "${event.result.status}: ${finishedMessage ?: event.result.message}"
    }

    // Provider text is displayed as plain, bounded text, never interpreted as instructions.
    private fun brief(value: String?): String? = value?.replace(Regex("\\s+"), " ")?.trim()
        ?.takeIf { it.isNotEmpty() }?.take(MAX_EXPLANATION_LENGTH)
}
