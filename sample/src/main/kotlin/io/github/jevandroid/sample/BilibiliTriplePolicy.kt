package io.github.jevandroid.sample

import io.github.jevandroid.core.*

/** Per-run policy for the sample's one-shot Bilibili hold, not a general Bilibili integration. */
internal class BilibiliTriplePolicy(
    private val maxPostHoldObservations: Int = 4,
) : ActionGate, OutcomeVerifier {
    init { require(maxPostHoldObservations in 1..4) }

    private data class Controls(val like: Element, val coin: Element, val favorite: Element) {
        val all: List<Element> get() = listOf(like, coin, favorite)
        val allChecked: Boolean get() = all.all { it.checked == true }
        val allUnchecked: Boolean get() = all.all { it.checked == false }
        fun describe(): String = "Like=${state(like)}, Coin=${state(coin)}, Favorite=${state(favorite)}"
        private fun state(element: Element) = if (element.checked == true) "checked" else "unchecked"
    }

    private data class HoldContext(val target: String, val title: String, val durationMillis: Long)
    private data class Attempt(val context: HoldContext?, val accepted: Boolean)

    private var pending: HoldContext? = null
    private var attempt: Attempt? = null
    private var postHoldObservations = 0
    private var explanation: String? = null
    private var verified = false

    /** After the hold, observe locally; the model cannot request another interaction. */
    fun provider(delegate: DecisionProvider): DecisionProvider = object : ContextualDecisionProvider {
        override suspend fun decide(
            task: Task, snapshot: UiSnapshot, history: List<StepRecord>, context: DecisionContext,
        ): Decision {
            val sent = attempt ?: return delegate.decideWithContext(task, snapshot, history, context)
            if (!sent.accepted || sent.context == null) {
                explanation = "The hold was rejected or its target could not be confirmed; no further interaction was sent. Inspect before restarting."
                return Decision(Operation.BLOCKED)
            }
            val contextProblem = contextProblem(task, snapshot, sent.context)
            val observedTitle = videoTitle(snapshot)
            if (snapshot.packageName != PACKAGE || PACKAGE !in task.allowedPackages ||
                (observedTitle != null && observedTitle != sent.context.title)) {
                explanation = contextProblem
                return Decision(Operation.BLOCKED)
            }
            val controls = controls(snapshot)
            if (contextProblem == null && controls?.allChecked == true) return Decision(Operation.DONE)
            postHoldObservations++
            if (postHoldObservations < maxPostHoldObservations) return Decision(Operation.WAIT)
            if (contextProblem != null) {
                explanation = contextProblem
                return Decision(Operation.BLOCKED)
            }
            explanation = "One hold was sent to Like (frame_like), but the triple outcome was not confirmed: " +
                (controls?.describe() ?: "the three controls or their checked states are unavailable") +
                ". No second hold or separate action was sent. Inspect before restarting."
            return Decision(Operation.BLOCKED)
        }
    }

    override suspend fun allow(task: Task, snapshot: UiSnapshot, decision: Decision): Boolean {
        pending = null
        if (attempt != null) {
            if (decision.operation == Operation.WAIT) return true
            return deny("A hold has already been attempted. A second hold and all further interactions are blocked; inspect before restarting.")
        }
        if (decision.operation == Operation.LONG_CLICK)
            return deny("Native LONG_CLICK is disabled for this scenario; only one timed hold on Like is allowed.")
        if (decision.operation == Operation.CLICK && snapshot.elements.any {
                it.id == decision.target && it.resourceId in CONTROL_IDS
            }) return deny("Separate Like, Coin, and Favorite clicks are disabled for this scenario.")
        if (decision.operation != Operation.LONG_PRESS) return true
        if (snapshot.packageName != PACKAGE || PACKAGE !in task.allowedPackages)
            return deny("The timed hold is restricted to the Bilibili video page.")
        val controls = controls(snapshot)
            ?: return deny("The Like, Coin, and Favorite buttons and their checked states could not be identified uniquely. No hold was sent.")
        if (decision.target != controls.like.id || Operation.LONG_PRESS !in controls.like.operations)
            return deny("Only a timed hold on Like (frame_like) is allowed. No hold was sent.")
        if (!controls.allUnchecked)
            return deny("Some triple-action controls are already selected (${controls.describe()}). No hold was sent, to avoid undoing or spending again.")
        val title = videoTitle(snapshot)
            ?: return deny("The current video title could not be identified uniquely. No hold was sent.")
        pending = HoldContext(controls.like.id, title, task.longPressDurationMillis)
        explanation = null
        return true
    }

    /** A stale decision spends no hold budget. Executed consumes it even if Android rejected the hold. */
    fun onEvent(event: AgentEvent): String? {
        if (event is AgentEvent.Refreshing && event.operation == Operation.LONG_PRESS) pending = null
        if (event !is AgentEvent.Executed || event.record.operation != Operation.LONG_PRESS) return null
        val context = pending?.takeIf { it.target == event.record.target }
        if (attempt == null) attempt = Attempt(context, event.record.accepted)
        pending = null
        return if (context != null)
            "Bilibili hold target: Like (frame_like); requested=${context.durationMillis} ms; Android accepted=${event.record.accepted}. Checking Like, Coin, and Favorite on the same video; no repeat is allowed."
        else "A timed hold was recorded without a confirmed Like target. Further interactions are blocked."
    }

    /** Verifies the three selected controls on the held video, not search-result ordering. */
    override suspend fun verify(task: Task, snapshot: UiSnapshot): Boolean {
        val sent = attempt
        verified = sent?.accepted == true && sent.context != null &&
            contextProblem(task, snapshot, sent.context) == null && controls(snapshot)?.allChecked == true
        if (!verified) explanation = "The three selected controls were not independently confirmed on the video held in this run; outcome remains unverified."
        return verified
    }

    /** Use in the sample's Finished event instead of the generic gate/provider result text. */
    fun messageFor(result: RunResult): String = when {
        result.status == Status.VERIFIED && verified ->
            "Like, Coin, and Favorite are all checked on the same video after one hold. Search-result ordering was not independently verified."
        result.status == Status.BLOCKED || result.status == Status.UNVERIFIED -> explanation ?: result.message
        else -> result.message
    }

    private fun deny(reason: String): Boolean { explanation = reason; return false }

    private fun contextProblem(task: Task, snapshot: UiSnapshot, context: HoldContext): String? = when {
        snapshot.packageName != PACKAGE || PACKAGE !in task.allowedPackages ->
            "The foreground left Bilibili after the hold; a system feature may have intercepted it. No further interaction was sent."
        videoTitle(snapshot) != context.title ->
            "The original video title is no longer uniquely visible after the hold. The outcome is uncertain; no further interaction was sent."
        else -> null
    }

    private fun controls(snapshot: UiSnapshot): Controls? {
        if (snapshot.packageName != PACKAGE) return null
        val found = CONTROL_IDS.map { id ->
            val matches = snapshot.elements.filter { it.resourceId == id }
            matches.singleOrNull()?.takeIf { it.role == "android.widget.Button" && it.checked != null }
                ?: return null
        }
        if (found.map { it.id }.distinct().size != found.size) return null
        return Controls(found[0], found[1], found[2])
    }

    private fun videoTitle(snapshot: UiSnapshot): String? = snapshot.elements
        .map { it.label }
        .filter { it.endsWith("，展开") && it.removeSuffix("，展开").isNotBlank() }
        .singleOrNull()

    companion object {
        private const val PACKAGE = "tv.danmaku.bili"
        private val CONTROL_IDS = listOf("frame_like", "frame_coin", "frame_fav").map { "$PACKAGE:id/$it" }
    }
}
