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

    /** Cancel the calling coroutine to stop. Provider/runtime exceptions propagate, without retrying mutations. */
    suspend fun run(task: Task, onEvent: (AgentEvent) -> Unit = {}): RunResult {
        check(running.tryLock()) { "This agent is already running" }
        var steps = 0
        try {
            val result = withTimeoutOrNull(task.timeoutMillis) {
                val history = mutableListOf<StepRecord>()
                var unchanged = 0
                var previous: String? = null
                repeat(task.maxSteps) {
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
                            val accepted = runtime.execute(task, snapshot, decision)
                            steps++
                            val record = StepRecord(steps, decision.operation, decision.target, accepted)
                            history += record
                            onEvent(AgentEvent.Executed(record))
                            // Failed or uncertain mutations are never blindly repeated.
                            if (!accepted) return@withTimeoutOrNull RunResult(Status.BLOCKED, steps, "Action rejected or stale UI; inspect before restarting")
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
