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
     * but are not recorded as executed steps, because no action reached the UI.
     */
    suspend fun run(task: Task, onEvent: (AgentEvent) -> Unit = {}): RunResult {
        check(running.tryLock()) { "This agent is already running" }
        var steps = 0
        try {
            val result = withTimeoutOrNull(task.timeoutMillis) {
                val history = mutableListOf<StepRecord>()
                var unchanged = 0
                var previous: String? = null
                var consecutiveRefreshes = 0
                repeat(task.maxSteps) { cycle ->
                    currentCoroutineContext().ensureActive()
                    val snapshot = runtime.observe(task)
                    unchanged = if (snapshot.fingerprint == previous) unchanged + 1 else 0
                    previous = snapshot.fingerprint
                    if (unchanged >= 5) return@withTimeoutOrNull RunResult(Status.BLOCKED, steps, "No UI progress in five cycles")
                    onEvent(AgentEvent.Observed(steps, snapshot.packageName, snapshot.elements.size))
                    val decision = provider.decide(task, snapshot, history.takeLast(10))
                    DecisionRules.validate(task, snapshot, decision)
                    onEvent(AgentEvent.Chosen(steps, decision))
                    if (decision.confidence < task.minimumConfidence)
                        return@withTimeoutOrNull RunResult(Status.BLOCKED, steps, "Decision confidence below threshold")
                    currentCoroutineContext().ensureActive()
                    when (decision.operation) {
                        Operation.BLOCKED -> return@withTimeoutOrNull RunResult(Status.BLOCKED, steps, "Provider cannot progress")
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
                            delay(if (decision.operation == Operation.WAIT) 600 else 250)
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
