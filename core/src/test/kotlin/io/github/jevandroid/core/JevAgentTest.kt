package io.github.jevandroid.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class JevAgentTest {
    private val task = Task("Save text", setOf("test.app"), mapOf("message" to "hello"))
    private val snapshot = UiSnapshot("v1", "test.app", listOf(
        Element("1", "Save", "Button", "", null, setOf(Operation.CLICK)),
        Element("2", "Text", "EditText", "", null, setOf(Operation.SET_TEXT)),
    ))
    private class FakeRuntime(var snapshot: UiSnapshot, val accepted: Boolean = true) : DeviceRuntime {
        var mutations = 0
        override suspend fun observe(task: Task) = snapshot
        override suspend fun execute(task: Task, snapshot: UiSnapshot, decision: Decision): Boolean { mutations++; return accepted }
    }
    private class ResultRuntime(
        var snapshot: UiSnapshot,
        val action: (ResultRuntime, UiSnapshot, Decision) -> ActionResult,
    ) : DetailedDeviceRuntime {
        val attempts = mutableListOf<Decision>()
        override suspend fun observe(task: Task) = snapshot
        override suspend fun execute(task: Task, snapshot: UiSnapshot, decision: Decision): Boolean =
            error("Agent must use the detailed result")
        override suspend fun executeWithResult(task: Task, snapshot: UiSnapshot, decision: Decision): ActionResult {
            attempts += decision
            return action(this, snapshot, decision)
        }
    }
    private fun provider(d: Decision) = object : DecisionProvider {
        override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>) = d
    }

    @Test fun doneWithoutEvidenceIsUnverified() = runTest {
        val result = JevAgent(FakeRuntime(snapshot), provider(Decision(Operation.DONE))).run(task)
        assertEquals(Status.UNVERIFIED, result.status)
    }
    @Test fun verifierSeesFreshObservation() = runTest {
        val runtime = FakeRuntime(snapshot)
        val p = object : DecisionProvider {
            override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision {
                runtime.snapshot = snapshot.copy(fingerprint = "v2")
                return Decision(Operation.DONE)
            }
        }
        assertEquals(Status.VERIFIED, JevAgent(runtime, p, OutcomeVerifier { _, s -> s.fingerprint == "v2" }).run(task).status)
    }
    @Test fun rejectsFabricatedTargetBeforeMutation() = runTest {
        val runtime = FakeRuntime(snapshot)
        try { JevAgent(runtime, provider(Decision(Operation.CLICK, "999"))).run(task); fail() }
        catch (_: IllegalArgumentException) { assertEquals(0, runtime.mutations) }
    }
    @Test fun supportedLongClickPassesThroughGateAndExecutesOnce() = runTest {
        val observed = snapshot.copy(elements = snapshot.elements +
            Element("3", "Hold", "Button", "", null, setOf(Operation.CLICK, Operation.LONG_CLICK)))
        val runtime = FakeRuntime(observed)
        val decision = Decision(Operation.LONG_CLICK, "3")
        var gated: Decision? = null
        val result = JevAgent(runtime, provider(decision), gate = ActionGate { _, _, chosen ->
            gated = chosen; true
        }).run(task.copy(maxSteps = 1))
        assertEquals(decision, gated)
        assertEquals(1, runtime.mutations)
        assertEquals(Status.LIMIT_REACHED, result.status)
    }
    @Test fun clickOnlyNodeCannotBeLongClicked() = runTest {
        val runtime = FakeRuntime(snapshot)
        try { JevAgent(runtime, provider(Decision(Operation.LONG_CLICK, "1"))).run(task); fail() }
        catch (_: IllegalArgumentException) { assertEquals(0, runtime.mutations) }
    }
    @Test fun longClickOutsideAllowlistIsRejectedBeforeMutation() = runTest {
        val observed = snapshot.copy(packageName = "other.app", elements = listOf(
            Element("3", "Hold", "Button", "", null, setOf(Operation.LONG_CLICK)),
        ))
        val runtime = FakeRuntime(observed)
        try { JevAgent(runtime, provider(Decision(Operation.LONG_CLICK, "3"))).run(task); fail() }
        catch (_: IllegalArgumentException) { assertEquals(0, runtime.mutations) }
    }
    @Test fun longClickCannotCarryText() {
        val observed = snapshot.copy(elements = listOf(
            Element("3", "Hold", "Button", "", null, setOf(Operation.LONG_CLICK)),
        ))
        try { DecisionRules.validate(task, observed, Decision(Operation.LONG_CLICK, "3", "message")); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectedLongClickIsNotRetried() = runTest {
        val observed = snapshot.copy(elements = listOf(
            Element("3", "Hold", "Button", "", null, setOf(Operation.LONG_CLICK)),
        ))
        val runtime = FakeRuntime(observed, false)
        assertEquals(Status.BLOCKED, JevAgent(runtime, provider(Decision(Operation.LONG_CLICK, "3"))).run(task).status)
        assertEquals(1, runtime.mutations)
    }
    @Test fun supportedTimedLongPressExecutesOnceWithCallerDuration() = runTest {
        val observed = snapshot.copy(elements = listOf(
            Element("3", "Hold", "Button", "", null, setOf(Operation.CLICK, Operation.LONG_PRESS)),
        ))
        val runtime = FakeRuntime(observed)
        val result = JevAgent(runtime, provider(Decision(Operation.LONG_PRESS, "3")), gate = ActionGate { supplied, _, _ ->
            assertEquals(2_500, supplied.longPressDurationMillis); true
        }).run(task.copy(maxSteps = 1, longPressDurationMillis = 2_500))
        assertEquals(Status.LIMIT_REACHED, result.status)
        assertEquals(1, runtime.mutations)
    }
    @Test fun timedLongPressMustBeExplicitlyAdvertisedBeforeMutation() = runTest {
        val runtime = FakeRuntime(snapshot)
        try { JevAgent(runtime, provider(Decision(Operation.LONG_PRESS, "1"))).run(task); fail() }
        catch (_: IllegalArgumentException) { assertEquals(0, runtime.mutations) }
    }
    @Test fun timedLongPressOutsideAllowlistIsRejectedBeforeMutation() = runTest {
        val runtime = FakeRuntime(snapshot.copy(packageName = "other.app", elements = listOf(
            Element("3", "Hold", "Button", "", null, setOf(Operation.LONG_PRESS)),
        )))
        try { JevAgent(runtime, provider(Decision(Operation.LONG_PRESS, "3"))).run(task); fail() }
        catch (_: IllegalArgumentException) { assertEquals(0, runtime.mutations) }
    }
    @Test fun longPressDurationIsBounded() {
        assertEquals(2_000, task.longPressDurationMillis)
        assertEquals(500, task.copy(longPressDurationMillis = 500).longPressDurationMillis)
        assertEquals(5_000, task.copy(longPressDurationMillis = 5_000).longPressDurationMillis)
        for (duration in listOf(-1L, 0L, 499L, 5_001L, Long.MAX_VALUE)) {
            try { task.copy(longPressDurationMillis = duration); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun lowConfidenceStopsBeforeMutation() = runTest {
        val runtime = FakeRuntime(snapshot)
        val result = JevAgent(runtime, provider(Decision(Operation.CLICK, "1", confidence = 0.2))).run(task)
        assertEquals(Status.BLOCKED, result.status); assertEquals(0, runtime.mutations)
    }
    @Test fun hostGateStopsAction() = runTest {
        val runtime = FakeRuntime(snapshot)
        JevAgent(runtime, provider(Decision(Operation.CLICK, "1")), gate = ActionGate { _, _, _ -> false }).run(task)
        assertEquals(0, runtime.mutations)
    }
    @Test fun rejectedMutationIsNotRetried() = runTest {
        val runtime = FakeRuntime(snapshot, false)
        assertEquals(Status.BLOCKED, JevAgent(runtime, provider(Decision(Operation.CLICK, "1"))).run(task).status)
        assertEquals(1, runtime.mutations)
    }
    @Test fun staleDecisionIsDiscardedAndFreshTargetIsChosenWithoutRecordingAnAction() = runTest {
        val fresh = snapshot.copy(fingerprint = "v2", elements = listOf(snapshot.elements.first().copy(id = "3")))
        var dispatched = 0
        val runtime = ResultRuntime(snapshot) { device, _, _ ->
            if (device.attempts.size == 1) {
                device.snapshot = fresh
                ActionResult.StaleBeforeDispatch
            } else {
                dispatched++
                ActionResult.Accepted
            }
        }
        val histories = mutableListOf<List<StepRecord>>()
        val observations = mutableListOf<UiSnapshot>()
        val p = object : DecisionProvider {
            override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision {
                observations += snapshot
                histories += history.toList()
                return if (history.isEmpty()) Decision(Operation.CLICK, snapshot.elements.first().id)
                    else Decision(Operation.DONE)
            }
        }
        val gatedTargets = mutableListOf<String?>()
        val events = mutableListOf<AgentEvent>()
        val result = JevAgent(runtime, p, gate = ActionGate { _, _, decision ->
            gatedTargets += decision.target; true
        }).run(task, events::add)
        assertEquals(Status.UNVERIFIED, result.status)
        assertEquals(1, result.steps)
        assertEquals(1, dispatched)
        assertEquals(listOf("1", "3"), runtime.attempts.map { it.target })
        assertEquals(listOf("1", "3"), gatedTargets)
        assertEquals(listOf("v1", "v2", "v2"), observations.map { it.fingerprint })
        assertTrue(histories[0].isEmpty())
        assertTrue(histories[1].isEmpty())
        assertEquals(listOf(StepRecord(1, Operation.CLICK, "3", true)), histories[2])
        assertEquals(listOf(AgentEvent.Refreshing(0, Operation.CLICK, 1)), events.filterIsInstance<AgentEvent.Refreshing>())
        assertEquals(1, events.filterIsInstance<AgentEvent.Executed>().size)
    }
    @Test fun continuouslyStaleUiStopsAfterThreeRefreshesWithoutAnyExecutedStep() = runTest {
        val runtime = ResultRuntime(snapshot) { _, _, _ -> ActionResult.StaleBeforeDispatch }
        val events = mutableListOf<AgentEvent>()
        val result = JevAgent(runtime, provider(Decision(Operation.CLICK, "1"))).run(task, events::add)
        assertEquals(Status.BLOCKED, result.status)
        assertTrue(result.message.contains("three refreshes"))
        assertEquals(0, result.steps)
        assertEquals(4, runtime.attempts.size)
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<AgentEvent.Refreshing>().map { it.attempt })
        assertTrue(events.filterIsInstance<AgentEvent.Executed>().isEmpty())
    }
    @Test fun staleRefreshesStillConsumeTheDecisionCycleBudget() = runTest {
        val runtime = ResultRuntime(snapshot) { _, _, _ -> ActionResult.StaleBeforeDispatch }
        val events = mutableListOf<AgentEvent>()
        val result = JevAgent(runtime, provider(Decision(Operation.CLICK, "1")))
            .run(task.copy(maxSteps = 2), events::add)
        assertEquals(Status.LIMIT_REACHED, result.status)
        assertEquals(0, result.steps)
        assertEquals(2, runtime.attempts.size)
        assertEquals(1, events.filterIsInstance<AgentEvent.Refreshing>().size)
        assertTrue(events.filterIsInstance<AgentEvent.Executed>().isEmpty())
    }
    @Test fun acceptedActionResetsTheConsecutiveRefreshLimit() = runTest {
        val runtime = ResultRuntime(snapshot) { device, _, _ ->
            device.snapshot = device.snapshot.copy(fingerprint = "v${device.attempts.size + 1}",
                elements = snapshot.elements.map { it.copy(value = "state ${device.attempts.size}") })
            if (device.attempts.size % 4 == 0) ActionResult.Accepted else ActionResult.StaleBeforeDispatch
        }
        val p = object : DecisionProvider {
            override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>) =
                if (history.size == 2) Decision(Operation.DONE) else Decision(Operation.CLICK, "1")
        }
        val events = mutableListOf<AgentEvent>()
        val result = JevAgent(runtime, p).run(task, events::add)
        assertEquals(Status.UNVERIFIED, result.status)
        assertEquals(2, result.steps)
        assertEquals(8, runtime.attempts.size)
        assertEquals(listOf(1, 2, 3, 1, 2, 3), events.filterIsInstance<AgentEvent.Refreshing>().map { it.attempt })
        assertEquals(2, events.filterIsInstance<AgentEvent.Executed>().size)
    }
    @Test fun uncertainSubmittedLongPressAndInputAreTerminalAndKeepTheirReason() = runTest {
        val observed = snapshot.copy(elements = listOf(
            snapshot.elements.first().copy(operations = setOf(Operation.LONG_PRESS, Operation.SET_TEXT)),
        ))
        for (decision in listOf(Decision(Operation.LONG_PRESS, "1"), Decision(Operation.SET_TEXT, "1", "message"))) {
            val reason = "Submitted ${decision.operation}; result could not be confirmed"
            val runtime = ResultRuntime(observed) { _, _, _ -> ActionResult.Rejected(reason) }
            val events = mutableListOf<AgentEvent>()
            val result = JevAgent(runtime, provider(decision)).run(task, events::add)
            assertEquals(Status.BLOCKED, result.status)
            assertEquals(reason, result.message)
            assertEquals(1, result.steps)
            assertEquals(1, runtime.attempts.size)
            assertTrue(events.filterIsInstance<AgentEvent.Refreshing>().isEmpty())
            assertFalse(events.filterIsInstance<AgentEvent.Executed>().single().record.accepted)
        }
    }
    @Test fun refreshedDecisionMustPassTheHostGateAgain() = runTest {
        val runtime = ResultRuntime(snapshot) { _, _, _ -> ActionResult.StaleBeforeDispatch }
        var gateCalls = 0
        val result = JevAgent(runtime, provider(Decision(Operation.CLICK, "1")), gate = ActionGate { _, _, _ ->
            ++gateCalls == 1
        }).run(task)
        assertEquals(Status.BLOCKED, result.status)
        assertEquals("Host policy stopped action", result.message)
        assertEquals(0, result.steps)
        assertEquals(2, gateCalls)
        assertEquals(1, runtime.attempts.size)
    }
    @Test fun cancellationDuringRefreshDelayNeverReexecutesTheOldDecision() = runTest {
        val runtime = ResultRuntime(snapshot) { _, _, _ -> ActionResult.StaleBeforeDispatch }
        val refreshing = CompletableDeferred<Unit>()
        val job = launch {
            JevAgent(runtime, provider(Decision(Operation.CLICK, "1"))).run(task) {
                if (it is AgentEvent.Refreshing) refreshing.complete(Unit)
            }
        }
        refreshing.await()
        job.cancelAndJoin()
        assertEquals(1, runtime.attempts.size)
    }
    @Test fun deadlineStillAppliesWhileRequestingAReplacementDecision() = runTest {
        val runtime = ResultRuntime(snapshot) { _, _, _ -> ActionResult.StaleBeforeDispatch }
        var providerCalls = 0
        val p = object : DecisionProvider {
            override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision {
                if (++providerCalls == 1) return Decision(Operation.CLICK, "1")
                awaitCancellation()
            }
        }
        val result = JevAgent(runtime, p).run(task.copy(timeoutMillis = 1_000))
        assertEquals(Status.TIMED_OUT, result.status)
        assertEquals(0, result.steps)
        assertEquals(2, providerCalls)
        assertEquals(1, runtime.attempts.size)
    }
    @Test fun cancellationDuringModelCallNeverMutates() = runTest {
        val runtime = FakeRuntime(snapshot)
        val entered = CompletableDeferred<Unit>()
        val p = object : DecisionProvider {
            override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision {
                entered.complete(Unit); awaitCancellation()
            }
        }
        val job = launch { JevAgent(runtime, p).run(task) }
        entered.await(); job.cancelAndJoin()
        assertEquals(0, runtime.mutations)
    }
    @Test fun timeoutStopsWaitingProvider() = runTest {
        val runtime = FakeRuntime(snapshot)
        val p = object : DecisionProvider {
            override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision { awaitCancellation() }
        }
        assertEquals(Status.TIMED_OUT, JevAgent(runtime, p).run(task.copy(timeoutMillis = 1000)).status)
        assertEquals(0, runtime.mutations)
    }
    @Test fun cannotWriteOutsideAllowlist() {
        try { DecisionRules.validate(task, snapshot.copy(packageName = "other.app"), Decision(Operation.SET_TEXT, "2", "message")); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectsUnknownTextValue() {
        try { DecisionRules.validate(task, snapshot, Decision(Operation.SET_TEXT, "2", "invented")); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun unchangedWaitsUseTheDecisionBudgetWithoutTheOldFiveCycleBlock() = runTest {
        val runtime = FakeRuntime(snapshot)
        assertEquals(Status.LIMIT_REACHED, JevAgent(runtime, provider(Decision(Operation.WAIT))).run(task.copy(maxSteps = 9)).status)
        assertEquals(9, runtime.mutations)
    }
    @Test fun stepBudgetIsBounded() = runTest {
        val runtime = FakeRuntime(snapshot)
        assertEquals(Status.LIMIT_REACHED, JevAgent(runtime, provider(Decision(Operation.WAIT))).run(task.copy(maxSteps = 2)).status)
        assertEquals(2, runtime.mutations)
    }
}
