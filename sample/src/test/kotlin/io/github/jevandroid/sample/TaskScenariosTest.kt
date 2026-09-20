package io.github.jevandroid.sample

import io.github.jevandroid.core.Task
import org.junit.Assert.*
import org.junit.Test

class TaskScenariosTest {
    private val scenarios = TaskScenarios.create("example.host")

    @Test fun builtInScenariosTargetTheActualHostAndUseSeparateFixtures() {
        val fixtures = scenarios.filter { it.fixture != FixtureKind.NONE }
        assertEquals(setOf(FixtureKind.SAVE_TEXT, FixtureKind.LONG_PRESS), fixtures.map { it.fixture }.toSet())
        fixtures.forEach { assertEquals(listOf("example.host"), it.packages) }
        assertTrue(fixtures.single { it.fixture == FixtureKind.LONG_PRESS }.inputs.isEmpty())
    }

    @Test fun bilibiliScenariosReplaceTheDemoPackageAndInput() {
        val bilibili = scenarios.filter { it.id.startsWith("bilibili_") }
        assertEquals(2, bilibili.size)
        bilibili.forEach {
            assertEquals(listOf("tv.danmaku.bili"), it.packages)
            assertEquals(listOf("testv"), it.inputs)
            assertEquals(FixtureKind.NONE, it.fixture)
        }
    }

    @Test fun customStartsBlankInsteadOfReopeningTheDemo() {
        val custom = scenarios.single { it.id == "custom" }
        assertTrue(custom.goal.isEmpty())
        assertTrue(custom.packages.isEmpty())
        assertTrue(custom.inputs.isEmpty())
        assertEquals(FixtureKind.NONE, custom.fixture)
    }

    @Test fun everyPresetConstructsAValidTaskWithoutGeneratedInput() {
        assertEquals(scenarios.size, scenarios.map { it.id }.toSet().size)
        scenarios.filter { it.id != "custom" }.forEach {
            val values = it.inputs.mapIndexed { index, value -> "value_$index" to value }.toMap()
            val task = Task(it.goal, it.packages.toSet(), values, timeoutMillis = it.timeoutMillis)
            assertEquals(it.inputs, task.textValues.values.toList())
        }
    }
}
