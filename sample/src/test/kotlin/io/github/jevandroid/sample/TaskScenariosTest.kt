package io.github.jevandroid.sample

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
            val task = it.createTask()
            assertEquals(it.inputs, task.textValues.values.toList())
            assertEquals(it.timeoutMillis, task.timeoutMillis)
        }
    }

    @Test fun tripleUsesFourSecondsWhileLocalFixtureKeepsTwoSeconds() {
        val triple = scenarios.single { it.id == "bilibili_triple" }
        val fixture = scenarios.single { it.id == "long_press" }
        assertEquals(4_000L, triple.createTask().longPressDurationMillis)
        assertEquals(2_000L, fixture.createTask().longPressDurationMillis)
        assertTrue(triple.goal.contains("configured hold duration"))
    }

    @Test fun editedHoldDurationReachesTheTaskForPresetsAndCustomTasks() {
        listOf("bilibili_triple", "custom").forEach { id ->
            val task = scenarios.single { it.id == id }.createTask(
                goal = "Hold the button once.",
                allowedPackages = setOf("example.target"),
                textValues = emptyMap(),
                longPressDurationMillis = requireNotNull(TaskScenarios.parseHoldDurationMillis(" 4500 ")),
            )
            assertEquals(4_500L, task.longPressDurationMillis)
            assertEquals(setOf("example.target"), task.allowedPackages)
        }
    }

    @Test fun holdDurationRejectsMissingMalformedAndOutOfRangeValues() {
        listOf("", " ", "four seconds", "2000.5", "499", "5001", "-2000", "99999999999999999999").forEach {
            assertNull("Unexpected valid hold duration: $it", TaskScenarios.parseHoldDurationMillis(it))
        }
        assertEquals(500L, TaskScenarios.parseHoldDurationMillis("500"))
        assertEquals(5_000L, TaskScenarios.parseHoldDurationMillis("5000"))
    }
}
