package io.github.jevandroid.sample

import io.github.jevandroid.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BilibiliTriplePolicyTest {
    private val task = Task("Search testv and hold Like once", setOf("tv.danmaku.bili"))
    private val hold = Decision(Operation.LONG_PRESS, "like")
    private fun button(id: String, checked: Boolean? = false) = Element(
        id, "A label that is not used to identify controls", "android.widget.Button", "", checked,
        setOf(Operation.CLICK, Operation.LONG_PRESS, Operation.LONG_CLICK),
        resourceId = "tv.danmaku.bili:id/frame_${if (id == "favorite") "fav" else id}",
    )
    private fun snapshot(like: Boolean? = false, coin: Boolean? = false, favorite: Boolean? = false,
        title: String = "A specific video，展开") = UiSnapshot("screen", "tv.danmaku.bili", listOf(
        button("like", like), button("coin", coin), button("favorite", favorite),
        Element("title", title, "android.widget.TextView", "A specific video", null, emptySet()),
        Element("search", "Search", "android.widget.Button", "", null, setOf(Operation.CLICK)),
    ))
    private fun executed(policy: BilibiliTriplePolicy, accepted: Boolean = true, target: String = "like") =
        policy.onEvent(AgentEvent.Executed(StepRecord(1, Operation.LONG_PRESS, target, accepted)))
    private fun noModel() = object : DecisionProvider {
        override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision =
            error("The provider must not be called after a hold")
    }

    @Test fun navigationStillUsesTheProviderAndGate() = runBlocking {
        val policy = BilibiliTriplePolicy()
        var calls = 0
        val click = Decision(Operation.CLICK, "search")
        val provider = policy.provider(object : DecisionProvider {
            override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision {
                calls++; return click
            }
        })
        assertEquals(click, provider.decide(task, snapshot(), emptyList()))
        assertEquals(1, calls)
        assertTrue(policy.allow(task, snapshot(), click))
        assertTrue(policy.allow(task, snapshot(), Decision(Operation.OPEN_APP, "tv.danmaku.bili")))
    }

    @Test fun nativeLongClickAndSeparateControlClicksAreBlocked() = runBlocking {
        for (id in listOf("like", "coin", "favorite")) {
            assertFalse(BilibiliTriplePolicy().allow(task, snapshot(), Decision(Operation.CLICK, id)))
            assertFalse(BilibiliTriplePolicy().allow(task, snapshot(), Decision(Operation.LONG_CLICK, id)))
        }
    }

    @Test fun onlyTheUniqueLikeButtonCanReceiveTheHold() = runBlocking {
        assertTrue(BilibiliTriplePolicy().allow(task, snapshot(), hold))
        assertFalse(BilibiliTriplePolicy().allow(task, snapshot(), hold.copy(target = "coin")))
        assertFalse(BilibiliTriplePolicy().allow(task, snapshot(), hold.copy(target = "favorite")))
        val duplicate = snapshot().let { it.copy(elements = it.elements + button("like").copy(id = "duplicate")) }
        assertFalse(BilibiliTriplePolicy().allow(task, duplicate, hold))
        val mislabeled = snapshot().let { state -> state.copy(elements = state.elements.map {
            if (it.id == "like") it.copy(resourceId = null, label = "点赞，6461个点赞") else it
        }) }
        assertFalse(BilibiliTriplePolicy().allow(task, mislabeled, hold))
    }

    @Test fun missingUnknownOrWrongRoleControlsPreventHolding() = runBlocking {
        assertFalse(BilibiliTriplePolicy().allow(task, snapshot(coin = null), hold))
        val missing = snapshot().let { it.copy(elements = it.elements.filterNot { node -> node.id == "favorite" }) }
        assertFalse(BilibiliTriplePolicy().allow(task, missing, hold))
        val wrongRole = snapshot().let { state -> state.copy(elements = state.elements.map {
            if (it.id == "like") it.copy(role = "android.widget.TextView") else it
        }) }
        assertFalse(BilibiliTriplePolicy().allow(task, wrongRole, hold))
        assertFalse(BilibiliTriplePolicy().allow(task, snapshot().copy(packageName = "other.app"), hold))
        assertFalse(BilibiliTriplePolicy().allow(task.copy(allowedPackages = setOf("other.app")), snapshot(), hold))
    }

    @Test fun preexistingSelectionsPreventAnotherHold() = runBlocking {
        for (state in listOf(snapshot(like = true), snapshot(coin = true), snapshot(favorite = true), snapshot(true, true, true)))
            assertFalse(BilibiliTriplePolicy().allow(task, state, hold))
    }

    @Test fun missingOrAmbiguousTitlePreventsHolding() = runBlocking {
        assertFalse(BilibiliTriplePolicy().allow(task, snapshot(title = "No known title marker"), hold))
        val ambiguous = snapshot().let { it.copy(elements = it.elements +
            Element("other-title", "Another video，展开", "android.widget.TextView", "", null, emptySet())) }
        assertFalse(BilibiliTriplePolicy().allow(task, ambiguous, hold))
    }

    @Test fun staleRefreshDoesNotSpendTheHoldBudget() = runBlocking {
        val policy = BilibiliTriplePolicy()
        assertTrue(policy.allow(task, snapshot(), hold))
        policy.onEvent(AgentEvent.Refreshing(0, Operation.LONG_PRESS, 1))
        assertTrue(policy.allow(task, snapshot(), hold))
        val message = executed(policy)
        assertTrue(message!!.contains("Like (frame_like)"))
        assertFalse(message.contains("A label"))
        assertFalse(policy.allow(task, snapshot(), hold))
    }

    @Test fun everyInteractionAfterTheHoldIsBlockedButWaitIsAllowed() = runBlocking {
        val policy = BilibiliTriplePolicy()
        assertTrue(policy.allow(task, snapshot(), hold)); executed(policy)
        for (decision in listOf(hold, Decision(Operation.CLICK, "search"), Decision(Operation.BACK),
            Decision(Operation.OPEN_APP, "tv.danmaku.bili"), Decision(Operation.LONG_CLICK, "like"),
            Decision(Operation.SET_TEXT, "search", "value"))) assertFalse(policy.allow(task, snapshot(), decision))
        assertTrue(policy.allow(task, snapshot(), Decision(Operation.WAIT)))
    }

    @Test fun postHoldObservationsBypassTheModelAndStopAfterThreeWaits() = runBlocking {
        val policy = BilibiliTriplePolicy()
        assertTrue(policy.allow(task, snapshot(), hold)); executed(policy)
        val provider = policy.provider(noModel())
        repeat(3) { assertEquals(Operation.WAIT, provider.decide(task, snapshot(), emptyList()).operation) }
        assertEquals(Operation.BLOCKED, provider.decide(task, snapshot(), emptyList()).operation)
        val message = policy.messageFor(RunResult(Status.BLOCKED, 4, "Provider cannot progress"))
        assertTrue(message.contains("Like=unchecked, Coin=unchecked, Favorite=unchecked"))
        assertTrue(message.contains("No second hold"))
    }

    @Test fun allThreeCheckedOnTheSameVideoAfterOneHoldIsVerified() = runBlocking {
        val policy = BilibiliTriplePolicy()
        assertTrue(policy.allow(task, snapshot(), hold)); executed(policy)
        val after = snapshot(true, true, true)
        assertEquals(Operation.DONE, policy.provider(noModel()).decide(task, after, emptyList()).operation)
        assertTrue(policy.verify(task, after))
        assertTrue(policy.messageFor(RunResult(Status.VERIFIED, 1, "Outcome verified"))
            .contains("Search-result ordering was not independently verified"))
    }

    @Test fun temporarilyHiddenTitleOrControlsCanSettleWithoutAnotherInteraction() = runBlocking {
        val policy = BilibiliTriplePolicy()
        assertTrue(policy.allow(task, snapshot(), hold)); executed(policy)
        val provider = policy.provider(noModel())
        assertEquals(Operation.WAIT, provider.decide(task, snapshot(title = "An animation is covering the title"), emptyList()).operation)
        assertEquals(Operation.WAIT, provider.decide(task, snapshot(coin = null), emptyList()).operation)
        assertEquals(Operation.DONE, provider.decide(task, snapshot(true, true, true), emptyList()).operation)
        assertTrue(policy.verify(task, snapshot(true, true, true)))
    }

    @Test fun selectedStatesWithoutARecordedHoldAreNotVerified() = runBlocking {
        assertFalse(BilibiliTriplePolicy().verify(task, snapshot(true, true, true)))
        val policy = BilibiliTriplePolicy()
        assertTrue(policy.allow(task, snapshot(), hold))
        assertFalse(policy.verify(task, snapshot(true, true, true)))
    }

    @Test fun changedTitleOrForegroundBlocksWithoutAnotherModelCall() = runBlocking {
        for (after in listOf(snapshot(true, true, true, title = "Another exact video，展开"),
            snapshot(true, true, true).copy(packageName = "com.samsung.android.onetouch"))) {
            val policy = BilibiliTriplePolicy()
            assertTrue(policy.allow(task, snapshot(), hold)); executed(policy)
            assertEquals(Operation.BLOCKED, policy.provider(noModel()).decide(task, after, emptyList()).operation)
            assertFalse(policy.verify(task, after))
        }
    }

    @Test fun partialOrAmbiguousStatesCannotBeVerified() = runBlocking {
        val policy = BilibiliTriplePolicy()
        assertTrue(policy.allow(task, snapshot(), hold)); executed(policy)
        for (state in listOf(snapshot(true, false, true), snapshot(true, null, true),
            snapshot(true, true, true).let { it.copy(elements = it.elements + button("coin", true).copy(id = "duplicate")) }))
            assertFalse(policy.verify(task, state))
    }

    @Test fun rejectedOrUnmatchedExecutionConsumesTheAttemptAndCannotBeVerified() = runBlocking {
        for ((accepted, target) in listOf(false to "like", true to "different-target")) {
            val policy = BilibiliTriplePolicy()
            assertTrue(policy.allow(task, snapshot(), hold)); executed(policy, accepted, target)
            assertFalse(policy.allow(task, snapshot(), hold))
            assertEquals(Operation.BLOCKED, policy.provider(noModel()).decide(task, snapshot(true, true, true), emptyList()).operation)
            assertFalse(policy.verify(task, snapshot(true, true, true)))
        }
    }
}
