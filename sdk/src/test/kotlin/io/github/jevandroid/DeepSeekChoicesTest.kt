package io.github.jevandroid

import io.github.jevandroid.core.Decision
import io.github.jevandroid.core.DecisionRules
import io.github.jevandroid.core.Element
import io.github.jevandroid.core.Operation
import io.github.jevandroid.core.Task
import io.github.jevandroid.core.UiSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekChoicesTest {
    private val task = Task("Find the first regular testv video", setOf("test.app"), mapOf("query" to "testv"))

    private fun node(
        id: String,
        label: String = "",
        value: String = "",
        operations: Set<Operation> = emptySet(),
    ) = Element(id, label, "View", value, null, operations)

    private fun snapshot(vararg elements: Element) = UiSnapshot("same-fingerprint", "test.app", elements.toList(),
        mapOf("test.app" to "Test app", "outside.app" to "Outside app"), "gesture-one")

    private fun alias(choices: DeepSeekChoices, operation: Operation, target: String? = null): String =
        choices.actions.entries.single { it.value == DeepSeekChoices.Action(operation, target) }.key

    @Test fun resourceNamesAreContextAndChangeBindingsButNeverBecomeDispatchTargets() {
        val like = node("0.1", "Like", operations = setOf(Operation.LONG_PRESS))
            .copy(resourceId = "test.app:id/frame_like")
        val original = DeepSeekChoices.create(task, snapshot(like))
        val changed = DeepSeekChoices.create(task, snapshot(like.copy(resourceId = "test.app:id/frame_fav")))
        assertEquals("test.app:id/frame_like", original.describe().getJSONArray("elements")
            .getJSONObject(0).getString("resource_id"))
        assertNotEquals(alias(original, Operation.LONG_PRESS, "0.1"), alias(changed, Operation.LONG_PRESS, "0.1"))
        assertFalse(original.actions.values.any { it.target == like.resourceId })
        assertTrue(DeepSeekChoices.create(task, snapshot(node("0.2"))).describe()
            .getJSONArray("elements").getJSONObject(0).isNull("resource_id"))
    }

    @Test fun blankClickableCardCarriesItsReadOnlyTitleWithoutMakingTheTitleClickable() {
        val observed = snapshot(
            node("0.1", operations = setOf(Operation.CLICK, Operation.LONG_PRESS)),
            node("0.1.0", "First testv video"),
            node("0.1.1", "12,345 views"),
            node("0.10", "Unrelated sibling card"),
        )
        val choices = DeepSeekChoices.create(task, observed)
        val elements = choices.describe().getJSONArray("elements")
        val card = elements.getJSONObject(0)
        assertEquals(listOf("First testv video", "12,345 views"), card.getJSONArray("context").toList())
        assertEquals(alias(choices, Operation.CLICK, "0.1"), card.getJSONObject("actions").getString("CLICK"))
        assertEquals(0, elements.getJSONObject(1).getJSONObject("actions").length())
        assertFalse(choices.actions.values.any { it.target == "0.1.0" })
        assertFalse(card.getJSONArray("context").toList().contains("Unrelated sibling card"))
    }

    @Test fun independentlyInteractiveDescendantsKeepTheirOwnContext() {
        val observed = snapshot(
            node("0.1", operations = setOf(Operation.CLICK)),
            node("0.1.0", "Video title"),
            node("0.1.1", "Author button", operations = setOf(Operation.CLICK)),
            node("0.1.1.0", "Author profile description"),
        )
        val elements = DeepSeekChoices.create(task, observed).describe().getJSONArray("elements")
        assertEquals(listOf("Video title"), elements.getJSONObject(0).getJSONArray("context").toList())
        assertEquals(listOf("Author profile description"), elements.getJSONObject(2).getJSONArray("context").toList())
    }

    @Test fun contextIsDeduplicatedAndBounded() {
        val observed = snapshot(*(
            listOf(node("0.1", "Own label", operations = setOf(Operation.CLICK)),
                node("0.1.0", "Own label"), node("0.1.1", "Repeated", "Repeated")) +
                (2..20).map { node("0.1.$it", "$it:" + "x".repeat(300)) }
            ).toTypedArray())
        val context = DeepSeekChoices.create(task, observed).describe().getJSONArray("elements")
            .getJSONObject(0).getJSONArray("context").toList().map { it as String }
        assertEquals(6, context.size)
        assertEquals(6, context.distinct().size)
        assertEquals("Repeated", context.first())
        assertTrue(context.all { it.length <= 160 })
        assertFalse("Own label" in context)
    }

    @Test fun opaqueCustomIdsDoNotAcquireInventedTreeRelationships() {
        val observed = snapshot(node("card", operations = setOf(Operation.CLICK)), node("card.0", "Unrelated text"))
        val card = DeepSeekChoices.create(task, observed).describe().getJSONArray("elements").getJSONObject(0)
        assertEquals(0, card.getJSONArray("context").length())
    }

    @Test fun operationsRemainDistinctAndNeverGainUnsupportedCapabilities() {
        val observed = snapshot(
            node("0.1", "Hold", operations = setOf(Operation.CLICK, Operation.LONG_CLICK, Operation.LONG_PRESS)),
            node("0.2", "Read-only title"),
            node("0.3", "Scroll", operations = setOf(Operation.SCROLL_FORWARD)),
        )
        val choices = DeepSeekChoices.create(task, observed)
        val expected = setOf(Operation.CLICK, Operation.LONG_CLICK, Operation.LONG_PRESS)
        assertEquals(expected, choices.actions.values.filter { it.target == "0.1" }.map { it.operation }.toSet())
        assertEquals(3, expected.map { alias(choices, it, "0.1") }.toSet().size)
        assertFalse(choices.actions.values.any { it.target == "0.2" })
        assertFalse(choices.actions.values.any { it.operation == Operation.SCROLL_BACKWARD })
        choices.actions.values.forEach { action ->
            DecisionRules.validate(task, observed, Decision(action.operation, action.target))
        }
    }

    @Test fun fiftyTextValuesDoNotMultiplyActionsAndEveryTextKeyIsPreserved() {
        val observed = snapshot(node("0.1", "Search", operations = setOf(Operation.CLICK, Operation.SET_TEXT)))
        val manyValues = task.copy(textValues = (1..50).associate { "key-$it" to "text-$it" })
        val one = DeepSeekChoices.create(task, observed)
        val many = DeepSeekChoices.create(manyValues, observed)
        assertEquals(one.actions.size, many.actions.size)
        assertEquals(1, many.actions.values.count { it.operation == Operation.SET_TEXT })
        assertEquals(manyValues.textValues.keys, many.textKeys.toSet())
        val action = many.actions.getValue(alias(many, Operation.SET_TEXT, "0.1"))
        many.textKeys.forEach { key ->
            DecisionRules.validate(manyValues, observed, Decision(action.operation, action.target, key))
        }
    }

    @Test fun missingTextValuesRemoveOnlySetTextChoices() {
        val observed = snapshot(node("0.1", "Search", operations = setOf(Operation.CLICK, Operation.SET_TEXT)))
        val choices = DeepSeekChoices.create(task.copy(textValues = emptyMap()), observed)
        assertTrue(choices.textKeys.isEmpty())
        assertFalse(choices.actions.values.any { it.operation == Operation.SET_TEXT })
        assertTrue(choices.actions.values.any { it.operation == Operation.CLICK })
        assertEquals(setOf("CLICK"), choices.describe().getJSONArray("elements").getJSONObject(0)
            .getJSONObject("actions").keySet())
    }

    @Test fun outsidePackageOnlyExposesAllowedAppsAndGlobalChoices() {
        val observed = snapshot(node("0.1", "Private outside text", operations = setOf(Operation.CLICK)))
            .copy(packageName = "outside.app")
        val choices = DeepSeekChoices.create(task, observed)
        val description = choices.describe()
        assertEquals(0, description.getJSONArray("elements").length())
        assertEquals(setOf(Operation.WAIT, Operation.DONE, Operation.BLOCKED, Operation.OPEN_APP),
            choices.actions.values.map { it.operation }.toSet())
        assertEquals("test.app", choices.actions.values.single { it.operation == Operation.OPEN_APP }.target)
        assertFalse(description.toString().contains("Outside app"))
        assertFalse(description.toString().contains("Private outside text"))
        assertEquals(setOf("WAIT", "DONE", "BLOCKED"), description.getJSONObject("global_actions").keySet())
    }

    @Test fun displayUsesAliasesInsteadOfRawTargetPathsOrPackageIds() {
        val observed = snapshot(node("0.19.13", "Search", operations = setOf(Operation.CLICK)))
        val choices = DeepSeekChoices.create(task, observed)
        val description = choices.describe().toString()
        assertFalse(description.contains("0.19.13"))
        assertFalse(description.contains("test.app"))
        assertTrue(description.contains(alias(choices, Operation.CLICK, "0.19.13")))
        assertTrue(choices.actions.keys.all { it.matches(Regex("a[0-9a-f]{8}_[0-9]+")) })
    }

    @Test fun candidateAliasesAreDeterministicAcrossUnorderedInputCollections() {
        val first = snapshot(node("0.1", operations = linkedSetOf(Operation.CLICK, Operation.LONG_PRESS)))
        val second = first.copy(elements = listOf(first.elements.single().copy(
            operations = linkedSetOf(Operation.LONG_PRESS, Operation.CLICK))),
            apps = linkedMapOf("outside.app" to "Outside app", "test.app" to "Test app"))
        assertEquals(DeepSeekChoices.create(task, first).actions, DeepSeekChoices.create(task, second).actions)
        assertEquals(DeepSeekChoices.create(task, first).describe().toString(), DeepSeekChoices.create(task, second).describe().toString())
    }

    @Test fun changedSnapshotsAndBindingsCannotReuseAliasesEvenWithAConstantFingerprint() {
        val original = snapshot(node("0.1", "Old title", operations = setOf(Operation.CLICK)))
        val base = DeepSeekChoices.create(task, original).actions.keys
        val variants = listOf(
            original.copy(fingerprint = "new-fingerprint"),
            original.copy(gestureFingerprint = "gesture-two"),
            original.copy(packageName = "other.app"),
            original.copy(elements = listOf(original.elements.single().copy(id = "0.2"))),
            original.copy(elements = listOf(original.elements.single().copy(label = "New title"))),
            original.copy(elements = listOf(original.elements.single().copy(operations = setOf(Operation.LONG_CLICK)))),
            original.copy(apps = mapOf("test.app" to "Changed app label")),
        )
        variants.forEach { changed -> assertTrue(base.intersect(DeepSeekChoices.create(task, changed).actions.keys).isEmpty()) }
        val changedTasks = listOf(task.copy(textValues = mapOf("different-key" to "testv")),
            task.copy(textValues = mapOf("query" to "different-value")), task.copy(goal = "Different goal"),
            task.copy(longPressDurationMillis = 3_000))
        changedTasks.forEach { changed -> assertTrue(base.intersect(DeepSeekChoices.create(changed, original).actions.keys).isEmpty()) }
    }

    @Test fun callerMutationsCannotChangeCapturedCatalogOrLaterDescriptions() {
        val operations = mutableSetOf(Operation.CLICK)
        val elements = mutableListOf(node("0.1", "Saved title", operations = operations))
        val observed = snapshot().copy(elements = elements)
        val choices = DeepSeekChoices.create(task, observed)
        val before = choices.describe().toString()
        operations += Operation.LONG_PRESS
        elements.clear()
        val mutableDescription = choices.describe()
        mutableDescription.getJSONArray("elements").getJSONObject(0).put("label", "Changed title")
        mutableDescription.put("global_actions", JSONObject())
        assertEquals(before, choices.describe().toString())
        assertFalse(choices.actions.values.any { it.operation == Operation.LONG_PRESS })
        try {
            (choices.actions as MutableMap<String, DeepSeekChoices.Action>).clear()
            fail("Actions must be immutable")
        } catch (_: UnsupportedOperationException) { }
        try {
            (choices.textKeys as MutableList<String>).clear()
            fail("Text keys must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }
}
