package io.github.jevandroid.core

enum class Operation { CLICK, SET_TEXT, SCROLL_FORWARD, SCROLL_BACKWARD, OPEN_APP, BACK, WAIT, DONE, BLOCKED }

data class Element(
    val id: String,
    val label: String,
    val role: String,
    val value: String,
    val checked: Boolean?,
    val operations: Set<Operation>,
)

data class UiSnapshot(
    val fingerprint: String,
    val packageName: String,
    val elements: List<Element>,
    val apps: Map<String, String> = emptyMap(),
)

data class Task(
    val goal: String,
    val allowedPackages: Set<String>,
    /** Named literal values. Providers select a key instead of inventing text. */
    val textValues: Map<String, String> = emptyMap(),
    val maxSteps: Int = 30,
    val timeoutMillis: Long = 120_000,
    val minimumConfidence: Double = 0.65,
) {
    init {
        require(goal.isNotBlank() && goal.length <= 4000)
        require(allowedPackages.isNotEmpty() && allowedPackages.size <= 50)
        require(allowedPackages.all { it.isNotBlank() })
        require(maxSteps in 1..200 && timeoutMillis in 1_000..1_800_000)
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        require(textValues.size <= 50 && textValues.all { it.key.isNotBlank() && it.value.length <= 4000 })
    }
}

data class Decision(
    val operation: Operation,
    val target: String? = null,
    val textKey: String? = null,
    /** Provider-specific score; DeepSeek self-reports it, so it is not a calibrated probability. */
    val confidence: Double = 1.0,
)

data class StepRecord(val step: Int, val operation: Operation, val target: String?, val accepted: Boolean)
enum class Status { VERIFIED, UNVERIFIED, BLOCKED, LIMIT_REACHED, TIMED_OUT }
data class RunResult(val status: Status, val steps: Int, val message: String)

sealed interface AgentEvent {
    data class Observed(val step: Int, val packageName: String, val elementCount: Int) : AgentEvent
    data class Chosen(val step: Int, val decision: Decision) : AgentEvent
    data class Executed(val record: StepRecord) : AgentEvent
    data class Finished(val result: RunResult) : AgentEvent
}

interface DecisionProvider {
    suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision
}

interface DeviceRuntime {
    suspend fun observe(task: Task): UiSnapshot
    /** Must reject stale snapshots, invalid targets and actions outside the allowlist. */
    suspend fun execute(task: Task, snapshot: UiSnapshot, decision: Decision): Boolean
}

fun interface OutcomeVerifier {
    suspend fun verify(task: Task, snapshot: UiSnapshot): Boolean
}

/** Host-owned policy; called before any action. A false result stops the run. */
fun interface ActionGate {
    suspend fun allow(task: Task, snapshot: UiSnapshot, decision: Decision): Boolean
}

object DecisionRules {
    fun validate(task: Task, snapshot: UiSnapshot, d: Decision) {
        require(d.confidence.isFinite() && d.confidence in 0.0..1.0) { "Invalid confidence" }
        when (d.operation) {
            Operation.OPEN_APP -> require(d.target in task.allowedPackages && d.target in snapshot.apps)
            Operation.CLICK, Operation.SET_TEXT, Operation.SCROLL_FORWARD, Operation.SCROLL_BACKWARD -> {
                require(snapshot.packageName in task.allowedPackages) { "Package outside allowlist" }
                require(snapshot.elements.any { it.id == d.target && d.operation in it.operations }) { "Invalid target" }
                if (d.operation == Operation.SET_TEXT) require(d.textKey in task.textValues) { "Unknown text key" }
            }
            Operation.BACK -> require(snapshot.packageName in task.allowedPackages)
            else -> require(d.target == null) { "Unexpected target" }
        }
        if (d.operation != Operation.SET_TEXT) require(d.textKey == null)
    }
}
