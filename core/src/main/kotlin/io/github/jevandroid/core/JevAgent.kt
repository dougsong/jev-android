package io.github.jevandroid.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

class JevAgent(
    private val runtime: DeviceRuntime,
    private val provider: DecisionProvider,
    private val verifier: OutcomeVerifier? = null,
    private val gate: ActionGate = ActionGate { _, _, _ -> true },
) {
    private val running = Mutex()

    /**
     * Cancel the calling coroutine to stop. Provider/runtime exceptions propagate, without retrying mutations.
     * At most three consecutive pre-dispatch stale decisions are refreshed. These consume decision cycles
     * but are not recorded as executed steps, because no action reached the UI. Accepted actions are
     * observed before replanning; identical ineffective actions on an unchanged page are not dispatched again.
     */
    suspend fun run(task: Task, onEvent: (AgentEvent) -> Unit = {}): RunResult {
        check(running.tryLock()) { "This agent is already running" }
        var steps = 0
        try {
            val result = withTimeoutOrNull(task.timeoutMillis) {
                val history = mutableListOf<StepRecord>()
                val progress = ProgressTracker()
                var consecutiveRefreshes = 0
                var consecutiveReplans = 0
                var replanReason: String? = null
                repeat(task.maxSteps) { cycle ->
                    currentCoroutineContext().ensureActive()
                    val snapshot = runtime.observe(task)
                    progress.observe(snapshot)?.let { onEvent(AgentEvent.Evaluated(it)) }
                    onEvent(AgentEvent.Observed(steps, snapshot.packageName, snapshot.elements.size))
                    val context = progress.context(replanReason)
                    val decision = provider.decideWithContext(task, snapshot, history.takeLast(10), context)
                    DecisionRules.validate(task, snapshot, decision)
                    onEvent(AgentEvent.Chosen(steps, decision))
                    currentCoroutineContext().ensureActive()
                    val problem = when {
                        progress.isExcluded(decision) ->
                            "This action was already accepted without visible change on this page. " +
                                "Inspect the current page and prior results; choose a different supported action, WAIT for loading, or report completion/blockage."
                        decision.confidence < task.minimumConfidence ->
                            "The proposed action is below the confidence threshold. Inspect the current page and prior results " +
                                "to choose a supported next action or explain blockage; do not raise confidence without new evidence."
                        else -> null
                    }
                    if (problem != null) {
                        if (consecutiveReplans >= 2) return@withTimeoutOrNull RunResult(
                            Status.BLOCKED, steps,
                            "No supported next action after two page-based replans; no action was sent for the discarded proposals",
                        )
                        consecutiveReplans++
                        replanReason = problem
                        onEvent(AgentEvent.Replanning(steps, problem, consecutiveReplans))
                        delay(250)
                        return@repeat
                    }
                    consecutiveReplans = 0
                    replanReason = null
                    when (decision.operation) {
                        Operation.BLOCKED -> return@withTimeoutOrNull RunResult(Status.BLOCKED, steps,
                            decision.summary?.let { "Provider stopped: $it" } ?: "Provider cannot progress")
                        Operation.DONE -> {
                            val fresh = runtime.observe(task)
                            val verified = verifier?.verify(task, fresh) == true
                            return@withTimeoutOrNull RunResult(
                                if (verified) Status.VERIFIED else Status.UNVERIFIED, steps,
                                if (verified) "Outcome verified" else "Provider reported done; outcome not verified",
                            )
                        }
                        else -> {
                            if (!gate.allow(task, snapshot, decision))
                                return@withTimeoutOrNull RunResult(Status.BLOCKED, steps, "Host policy stopped action")
                            currentCoroutineContext().ensureActive()
                            val actionResult = runtime.executeWithResult(task, snapshot, decision)
                            if (actionResult == ActionResult.StaleBeforeDispatch) {
                                if (consecutiveRefreshes >= 3) return@withTimeoutOrNull RunResult(
                                    Status.BLOCKED, steps,
                                    "UI kept changing before execution after three refreshes; no action was sent for the stale decisions",
                                )
                                if (cycle + 1 >= task.maxSteps) return@withTimeoutOrNull RunResult(
                                    Status.LIMIT_REACHED, steps, "Decision budget exhausted before the stale action could be refreshed",
                                )
                                consecutiveRefreshes++
                                onEvent(AgentEvent.Refreshing(steps, decision.operation, consecutiveRefreshes))
                                delay(250)
                                return@repeat
                            }
                            consecutiveRefreshes = 0
                            val accepted = actionResult == ActionResult.Accepted
                            steps++
                            val record = StepRecord(steps, decision.operation, decision.target, accepted)
                            history += record
                            onEvent(AgentEvent.Executed(record))
                            // Failed or uncertain mutations are never blindly repeated.
                            if (actionResult is ActionResult.Rejected)
                                return@withTimeoutOrNull RunResult(Status.BLOCKED, steps, actionResult.reason)
                            // Observe first; accepted=true acknowledges dispatch, not task success.
                            val settleBudget = if (decision.operation == Operation.WAIT) 600L else task.settleTimeoutMillis
                            var observedFor = 0L
                            var after: UiSnapshot
                            do {
                                val pause = minOf(250L, settleBudget - observedFor)
                                if (pause > 0) { delay(pause); observedFor += pause }
                                currentCoroutineContext().ensureActive()
                                after = runtime.observe(task)
                            } while (samePage(snapshot, after) && observedFor < settleBudget)
                            onEvent(AgentEvent.Evaluated(progress.record(decision, snapshot, after, observedFor)))
                        }
                    }
                }
                RunResult(Status.LIMIT_REACHED, steps, "Step budget exhausted")
            } ?: RunResult(Status.TIMED_OUT, steps, "Task deadline reached")
            onEvent(AgentEvent.Finished(result))
            return result
        } finally { running.unlock() }
    }
}
