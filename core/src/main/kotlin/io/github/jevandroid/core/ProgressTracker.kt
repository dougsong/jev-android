package io.github.jevandroid.core

/** Geometry/focus-only fingerprint changes do not establish visible task progress. */
internal fun samePage(first: UiSnapshot, second: UiSnapshot): Boolean =
    first.packageName == second.packageName && first.elements == second.elements && first.apps == second.apps

/** Per-run facts supplied to the planner, independent of any application's workflow. */
internal class ProgressTracker {
    private var observed: UiSnapshot? = null
    private var lastActionPage: UiSnapshot? = null
    private val outcomes = mutableListOf<ActionFeedback>()
    private val exclusions = linkedSetOf<ActionSignature>()

    fun observe(snapshot: UiSnapshot): ActionFeedback? {
        if (observed?.let { !samePage(it, snapshot) } == true) exclusions.clear()
        observed = snapshot
        val last = outcomes.lastOrNull()
        // A delayed result can appear after the settling window or during the next model call.
        if (last?.outcome == ObservationOutcome.NO_VISIBLE_CHANGE &&
            lastActionPage?.let { !samePage(it, snapshot) } == true) {
            val updated = last.copy(outcome = ObservationOutcome.UI_CHANGED)
            outcomes[outcomes.lastIndex] = updated
            return updated
        }
        return null
    }

    fun record(decision: Decision, before: UiSnapshot, after: UiSnapshot, observedForMillis: Long): ActionFeedback {
        if (!samePage(before, after)) exclusions.clear()
        observed = after
        val changed = !samePage(before, after)
        if (!changed && decision.operation != Operation.WAIT)
            exclusions += ActionSignature(decision.operation, decision.target, decision.textKey)
        val feedback = ActionFeedback(
            decision, before.elements.singleOrNull { it.id == decision.target },
            if (changed) ObservationOutcome.UI_CHANGED else ObservationOutcome.NO_VISIBLE_CHANGE,
            observedForMillis,
        )
        outcomes += feedback
        if (outcomes.size > 10) outcomes.removeAt(0)
        lastActionPage = before
        return feedback
    }

    fun context(reason: String?): DecisionContext = DecisionContext(
        lastAction = outcomes.lastOrNull(), excludedActions = exclusions.toList(),
        replanReason = reason, recentOutcomes = outcomes.toList(),
    )

    /** Check private state, never the context collection that an external provider received. */
    fun isExcluded(decision: Decision): Boolean = exclusions.any { it.matches(decision) }
}
