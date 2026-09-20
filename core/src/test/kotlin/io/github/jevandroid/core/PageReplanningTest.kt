package io.github.jevandroid.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PageReplanningTest {
    private val task = Task("Save message", setOf("example.app"), mapOf("first" to "hello", "second" to "world"))
    private val initial = UiSnapshot("one", "example.app", listOf(
        Element("container", "Form", "Container", "", null, setOf(Operation.CLICK)),
        Element("save", "Save", "Button", "", null, setOf(Operation.CLICK)),
        Element("input", "Message", "EditText", "", null, setOf(Operation.SET_TEXT)),
    ))
    private class Runtime(var page: UiSnapshot) : DetailedDeviceRuntime {
        val sent = mutableListOf<Decision>()
        var observations = 0
        var onObserve: (Runtime) -> Unit = {}
        var onExecute: (Runtime, Decision) -> ActionResult = { _, _ -> ActionResult.Accepted }
        override suspend fun observe(task: Task): UiSnapshot { observations++; onObserve(this); return page }
        override suspend fun execute(task: Task, snapshot: UiSnapshot, decision: Decision): Boolean = error("Detailed result required")
        override suspend fun executeWithResult(task: Task, snapshot: UiSnapshot, decision: Decision): ActionResult {
            sent += decision
            return onExecute(this, decision)
        }
    }
    private fun planner(block: (UiSnapshot, DecisionContext) -> Decision) = object : ContextualDecisionProvider {
        override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>, context: DecisionContext) =
            block(snapshot, context)
    }

    @Test fun ineffectiveContainerClickIsObservedAndModelChoosesTheSaveButton() = runTest {
        val runtime = Runtime(initial)
        runtime.onExecute = { device, d ->
            if (d.target == "save") device.page = initial.copy(elements = initial.elements +
                Element("result", "Saved", "Text", "", null, emptySet()))
            ActionResult.Accepted
        }
        var calls = 0
        val provider = planner { page, context ->
            when (++calls) {
                1 -> Decision(Operation.CLICK, "container", summary = "Open the form", expectedChange = "Show input controls")
                2 -> {
                    assertEquals(ObservationOutcome.NO_VISIBLE_CHANGE, context.lastAction?.outcome)
                    assertEquals("Form", context.lastAction?.target?.label)
                    assertEquals(1_500L, context.lastAction?.observedForMillis)
                    assertTrue(context.excludedActions.single().matches(Decision(Operation.CLICK, "container")))
                    assertEquals(initial, page)
                    Decision(Operation.CLICK, "save", summary = "Use the visible Save button", expectedChange = "Saved confirmation")
                }
                else -> {
                    assertEquals(ObservationOutcome.UI_CHANGED, context.lastAction?.outcome)
                    assertEquals(2, context.recentOutcomes.size)
                    assertEquals(ObservationOutcome.NO_VISIBLE_CHANGE, context.recentOutcomes.first().outcome)
                    Decision(Operation.DONE)
                }
            }
        }
        val result = JevAgent(runtime, provider, OutcomeVerifier { _, s -> s.elements.any { it.label == "Saved" } }).run(task)
        assertEquals(Status.VERIFIED, result.status)
        assertEquals(listOf("container", "save"), runtime.sent.map { it.target })
    }

    @Test fun legacyProviderRepeatingNoEffectActionIsNeverRedispatched() = runTest {
        val runtime = Runtime(initial)
        val events = mutableListOf<AgentEvent>()
        val provider = object : DecisionProvider {
            override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>) = Decision(Operation.CLICK, "save")
        }
        val result = JevAgent(runtime, provider).run(task, events::add)
        assertEquals(Status.BLOCKED, result.status)
        assertEquals(1, runtime.sent.size)
        assertEquals(2, events.filterIsInstance<AgentEvent.Replanning>().size)
        assertTrue(result.message.contains("two page-based replans"))
    }

    @Test fun ignoredExclusionProducesExplicitFeedbackThenAcceptsADifferentPlan() = runTest {
        val runtime = Runtime(initial)
        var calls = 0
        val provider = planner { _, context ->
            when (++calls) {
                1, 2 -> Decision(Operation.CLICK, "container")
                3 -> {
                    assertTrue(context.replanReason!!.contains("already accepted without visible change"))
                    Decision(Operation.CLICK, "save")
                }
                else -> Decision(Operation.DONE)
            }
        }
        val result = JevAgent(runtime, provider).run(task)
        assertEquals(Status.UNVERIFIED, result.status)
        assertEquals(listOf("container", "save"), runtime.sent.map { it.target })
    }

    @Test fun moreThanFiveWaitsCanReachAVisibleResult() = runTest {
        val runtime = Runtime(initial)
        runtime.onExecute = { device, _ ->
            if (device.sent.size == 7) device.page = initial.copy(elements = emptyList())
            ActionResult.Accepted
        }
        val provider = planner { page, context ->
            assertTrue(context.excludedActions.isEmpty())
            Decision(if (page.elements.isEmpty()) Operation.DONE else Operation.WAIT)
        }
        assertEquals(Status.VERIFIED, JevAgent(runtime, provider, OutcomeVerifier { _, s -> s.elements.isEmpty() }).run(task).status)
        assertEquals(7, runtime.sent.size)
    }

    @Test fun waitsPreserveExclusionsAndPastActionEffects() = runTest {
        val runtime = Runtime(initial)
        var calls = 0
        val provider = planner { _, context ->
            when (++calls) {
                1 -> Decision(Operation.CLICK, "container")
                in 2..7 -> Decision(Operation.WAIT)
                else -> {
                    assertEquals(Operation.WAIT, context.lastAction?.decision?.operation)
                    assertEquals("container", context.recentOutcomes.first().decision.target)
                    assertEquals(ActionSignature(Operation.CLICK, "container"), context.excludedActions.single())
                    Decision(Operation.DONE)
                }
            }
        }
        assertEquals(Status.UNVERIFIED, JevAgent(runtime, provider).run(task).status)
    }

    @Test fun anAlternativeSuppliedTextIsNotExcluded() = runTest {
        val runtime = Runtime(initial)
        var calls = 0
        val provider = planner { _, context ->
            when (++calls) {
                1 -> Decision(Operation.SET_TEXT, "input", "first")
                2 -> {
                    assertFalse(context.excludedActions.single().matches(Decision(Operation.SET_TEXT, "input", "second")))
                    Decision(Operation.SET_TEXT, "input", "second")
                }
                else -> Decision(Operation.DONE)
            }
        }
        JevAgent(runtime, provider).run(task)
        assertEquals(listOf("first", "second"), runtime.sent.map { it.textKey })
    }

    @Test fun geometryOnlyChangesCannotReenableAnIneffectiveClick() = runTest {
        val runtime = Runtime(initial)
        runtime.onObserve = { it.page = it.page.copy(fingerprint = "geometry-${it.observations}", gestureFingerprint = "g-${it.observations}") }
        val result = JevAgent(runtime, planner { _, _ -> Decision(Operation.CLICK, "save") }).run(task)
        assertEquals(Status.BLOCKED, result.status)
        assertEquals(1, runtime.sent.size)
    }

    @Test fun lateVisibleChangeClearsExclusionsAndUpdatesTheLastObservation() = runTest {
        val runtime = Runtime(initial)
        var calls = 0
        val provider = planner { _, context ->
            when (++calls) {
                1 -> Decision(Operation.CLICK, "container")
                2 -> {
                    // The screen changes during this request; a real runtime discards its stale decision.
                    runtime.page = initial.copy(elements = initial.elements.map { it.copy(value = "loaded") })
                    Decision(Operation.CLICK, "container") // filtered locally without dispatch
                }
                3 -> {
                    assertTrue(context.excludedActions.isEmpty())
                    assertEquals(ObservationOutcome.UI_CHANGED, context.lastAction?.outcome)
                    Decision(Operation.CLICK, "container")
                }
                else -> Decision(Operation.DONE)
            }
        }
        assertEquals(Status.UNVERIFIED, JevAgent(runtime, provider).run(task).status)
        assertEquals(2, runtime.sent.size)
    }

    @Test fun delayedChangeWithinSettlingWindowIsVisibleToNextDecision() = runTest {
        val runtime = Runtime(initial)
        val update = launch { delay(900); runtime.page = initial.copy(elements = emptyList()) }
        val provider = planner { _, context ->
            if (context.lastAction == null) Decision(Operation.CLICK, "save") else {
                assertEquals(ObservationOutcome.UI_CHANGED, context.lastAction.outcome)
                assertEquals(1_000L, context.lastAction.observedForMillis)
                Decision(Operation.DONE)
            }
        }
        JevAgent(runtime, provider).run(task)
        update.join()
        assertEquals(1, runtime.sent.size)
    }

    @Test fun lowConfidenceProposalIsReconsideredWithPageFeedbackBeforeAnyAction() = runTest {
        val runtime = Runtime(initial)
        var calls = 0
        val provider = planner { _, context ->
            when (++calls) {
                1 -> Decision(Operation.CLICK, "container", confidence = 0.2)
                2 -> {
                    assertTrue(context.replanReason!!.contains("confidence threshold"))
                    assertTrue(runtime.sent.isEmpty())
                    Decision(Operation.CLICK, "save")
                }
                else -> Decision(Operation.DONE)
            }
        }
        JevAgent(runtime, provider).run(task)
        assertEquals(listOf("save"), runtime.sent.map { it.target })
    }

    @Test fun rejectedActionNeverEntersReplanningOrSettling() = runTest {
        val runtime = Runtime(initial)
        runtime.onExecute = { _, _ -> ActionResult.Rejected("Outcome uncertain") }
        val result = JevAgent(runtime, planner { _, _ -> Decision(Operation.CLICK, "save") }).run(task)
        assertEquals(Status.BLOCKED, result.status)
        assertEquals("Outcome uncertain", result.message)
        assertEquals(1, runtime.observations)
        assertEquals(1, runtime.sent.size)
    }

    @Test fun settlingRespectsTheTaskDeadlineWithoutRepeatingTheSubmittedAction() = runTest {
        val runtime = Runtime(initial)
        val result = JevAgent(runtime, planner { _, _ -> Decision(Operation.CLICK, "save") })
            .run(task.copy(timeoutMillis = 1_000))
        assertEquals(Status.TIMED_OUT, result.status)
        assertEquals(1, result.steps)
        assertEquals(1, runtime.sent.size)
    }

    @Test fun cancellationDuringSettlingNeverDispatchesAgain() = runTest {
        val runtime = Runtime(initial)
        val sent = CompletableDeferred<Unit>()
        val job = launch {
            JevAgent(runtime, planner { _, _ -> Decision(Operation.CLICK, "save") }).run(task) {
                if (it is AgentEvent.Executed) sent.complete(Unit)
            }
        }
        sent.await(); job.cancelAndJoin()
        assertEquals(1, runtime.sent.size)
        assertEquals(1, runtime.observations)
    }

    @Test fun decisionBudgetIncludesDiscardedProposals() = runTest {
        val runtime = Runtime(initial)
        val result = JevAgent(runtime, planner { _, _ -> Decision(Operation.CLICK, "save") }).run(task.copy(maxSteps = 2))
        assertEquals(Status.LIMIT_REACHED, result.status)
        assertEquals(1, runtime.sent.size)
    }

    @Test fun explanationAndSettlingBoundsAreValidated() {
        for (d in listOf(Decision(Operation.DONE, summary = " "), Decision(Operation.DONE, expectedChange = "x".repeat(301)))) {
            try { DecisionRules.validate(task, initial, d); fail() } catch (_: IllegalArgumentException) { }
        }
        for (duration in listOf(-1L, 10_001L)) {
            try { task.copy(settleTimeoutMillis = duration); fail() } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun selectedTabChangeIsObservableWithoutAnyTextChange() = runTest {
        val runtime = Runtime(initial)
        runtime.onExecute = { device, _ ->
            device.page = initial.copy(elements = initial.elements.map { it.copy(selected = true) })
            ActionResult.Accepted
        }
        val provider = planner { _, context ->
            if (context.lastAction == null) Decision(Operation.CLICK, "save") else {
                assertEquals(ObservationOutcome.UI_CHANGED, context.lastAction.outcome)
                Decision(Operation.DONE)
            }
        }
        JevAgent(runtime, provider).run(task)
        assertEquals(1, runtime.sent.size)
    }

    @Test fun providerCannotRemoveTheCoresIndependentRepeatGuard() = runTest {
        val runtime = Runtime(initial)
        var calls = 0
        val provider = planner { _, context ->
            when (++calls) {
                1 -> Decision(Operation.CLICK, "container")
                2 -> Decision(Operation.CLICK, "save")
                else -> {
                    // Two entries produce a mutable ArrayList via Kotlin's toList().
                    (context.excludedActions as MutableList<ActionSignature>).clear()
                    Decision(Operation.CLICK, "container")
                }
            }
        }
        assertEquals(Status.BLOCKED, JevAgent(runtime, provider).run(task).status)
        assertEquals(listOf("container", "save"), runtime.sent.map { it.target })
    }
}
