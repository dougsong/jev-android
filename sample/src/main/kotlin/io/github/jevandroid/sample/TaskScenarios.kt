package io.github.jevandroid.sample

internal enum class FixtureKind { NONE, SAVE_TEXT, LONG_PRESS }

internal data class TaskScenario(
    val id: String,
    val title: String,
    val description: String,
    val goal: String,
    val packages: List<String>,
    val inputs: List<String>,
    val fixture: FixtureKind = FixtureKind.NONE,
    val timeoutMillis: Long = 120_000,
)

/** Selecting a scenario only fills editable fields; it never starts a task. */
internal object TaskScenarios {
    fun create(hostPackage: String): List<TaskScenario> = listOf(
        TaskScenario(
            id = "save_text",
            title = "Built-in: save text",
            description = "Enter and save text on the local test page. The saved value is checked independently.",
            goal = "On the test page, enter Hello Jev, tap Save, and confirm that Saved: Hello Jev is displayed.",
            packages = listOf(hostPackage),
            inputs = listOf("Hello Jev"),
            fixture = FixtureKind.SAVE_TEXT,
        ),
        TaskScenario(
            id = "long_press",
            title = "Built-in: long press",
            description = "Press and hold the local test button for 2 seconds, then release. The held touch is checked independently.",
            goal = "On the test page, press and hold the Hold to confirm button for 2 seconds, then release. " +
                "Keep the touch down for the full duration, then confirm that Long press confirmed is displayed.",
            packages = listOf(hostPackage),
            inputs = emptyList(),
            fixture = FixtureKind.LONG_PRESS,
        ),
        TaskScenario(
            id = "bilibili_search",
            title = "Bilibili: search testv",
            description = "Search for testv and open the first ordinary video. Skip advertisements and live streams.",
            goal = "Open Bilibili, search for testv, and open the first ordinary video in the search results, " +
                "skipping advertisements and live streams. Stop on the video page. Do not like, coin, favorite, or comment. " +
                "Stop if sign-in, a CAPTCHA, or an inaccessible control prevents progress.",
            packages = listOf("tv.danmaku.bili"),
            inputs = listOf("testv"),
            timeoutMillis = 180_000,
        ),
        TaskScenario(
            id = "bilibili_triple",
            title = "Bilibili: search + triple action",
            description = "Search testv, open the first ordinary video, then hold Like once for 2 seconds. " +
                "This can like the video, spend account coins, and add it to favorites.",
            goal = "Open Bilibili, search for testv, and open the first ordinary video in the search results, " +
                "skipping advertisements and live streams. On that video, press and hold the Like button once " +
                "for 2 seconds to trigger the combined like, coin, and favorite action. " +
                "Inspect the resulting interface. Report done only if all three outcomes are visibly confirmed. " +
                "Never undo an existing like or favorite, and never repeat the hold or fall back to separate actions " +
                "when the result is uncertain. Stop if the hold action is unavailable, any result cannot be confirmed, " +
                "or sign-in, a CAPTCHA, or insufficient coins prevents completion. Do not comment or share.",
            packages = listOf("tv.danmaku.bili"),
            inputs = listOf("testv"),
            timeoutMillis = 180_000,
        ),
        TaskScenario(
            id = "custom",
            title = "Custom task",
            description = "Enter a goal, allowed app package names, and any exact text to type. Check these fields before starting.",
            goal = "",
            packages = emptyList(),
            inputs = emptyList(),
        ),
    )
}
