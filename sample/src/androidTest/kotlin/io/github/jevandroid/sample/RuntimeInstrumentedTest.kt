package io.github.jevandroid.sample

import android.app.Activity
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.Window
import android.widget.EditText
import android.widget.Spinner
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jevandroid.*
import io.github.jevandroid.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/device fixture tests. No API key or network/model call. */
@RunWith(AndroidJUnit4::class)
class RuntimeInstrumentedTest {
    private fun <A : Activity, T> ActivityScenario<A>.useWithExplicitFinish(
        block: (ActivityScenario<A>) -> T,
    ): T {
        var bodyFailure: Throwable? = null
        try {
            return block(this)
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            try {
                if (state != Lifecycle.State.DESTROYED) {
                    try {
                        // onActivity runs on the main thread. Finish directly instead of
                        // asking close() to launch its EmptyActivity lifecycle helper.
                        onActivity { it.finish() }
                    } catch (failure: IllegalStateException) {
                        // The activity may finish between the state check and callback.
                        if (state != Lifecycle.State.DESTROYED) throw failure
                    }
                    val destroyed = runBlocking {
                        withTimeoutOrNull(5_000) {
                            while (state != Lifecycle.State.DESTROYED) delay(25)
                            true
                        } ?: false
                    }
                    check(destroyed) { "Fixture did not finish within 5 seconds; skipped blocking lifecycle transition" }
                }
                // An already-destroyed scenario only needs its monitoring resources closed.
                close()
            } catch (cleanupFailure: Throwable) {
                if (bodyFailure != null) bodyFailure.addSuppressed(cleanupFailure)
                else throw cleanupFailure
            }
        }
    }

    private fun views(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private suspend fun awaitFixture(runtime: AccessibilityRuntime, task: Task, text: String): UiSnapshot {
        var last: UiSnapshot? = null
        return withTimeoutOrNull(5_000) {
            while (true) {
                val snapshot = runtime.observe(task)
                last = snapshot
                if (snapshot.packageName in task.allowedPackages && snapshot.elements.any { it.value.equals(text, ignoreCase = true) })
                    return@withTimeoutOrNull snapshot
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        } ?: throw AssertionError("Fixture '$text' not visible; package=${last?.packageName}, elements=${last?.elements?.size}. Keep the device unlocked and the fixture foreground.")
    }

    private fun <T> withService(block: (JevAccessibilityService) -> T): T {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        val resolver = context.contentResolver
        val oldServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        try {
            val component = ComponentName(context, JevAccessibilityService::class.java).flattenToString()
            val services = oldServices.orEmpty().split(':').filter { it.isNotBlank() }.toMutableSet().apply { add(component) }
            // Instrumentation restarts the target process. Some devices retain the old
            // binding as crashed until this service is removed and added again.
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services.filter { it != component }.joinToString(":"))
            runBlocking {
                withTimeout(5_000) {
                    while (JevAccessibilityService.connected.value != null) delay(100)
                }
                delay(300)
            }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            val service = runBlocking {
                withTimeout(10_000) {
                    while (JevAccessibilityService.connected.value == null) delay(100)
                    requireNotNull(JevAccessibilityService.connected.value)
                }
            }
            return block(service)
        } finally {
            instrumentation.runOnMainSync { JevAccessibilityService.connected.value?.stop() }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices)
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled)
            automation.dropShellPermissionIdentity()
        }
    }

    @Test fun observesInputsClicksAndVerifiesRealFixture() = withService { service ->
        ActivityScenario.launch(FixtureActivity::class.java).useWithExplicitFinish {
            runBlocking { awaitFixture(AccessibilityRuntime(service), Task("Wait for fixture", setOf("io.github.jevandroid.sample")), "Save") }
            val result = CompletableDeferred<RunResult>()
            val provider = object : DecisionProvider {
                override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision {
                    // A real provider suspends for network I/O; allow overlay layout/events
                    // to occur between observation and execution as they do with DeepSeek.
                    delay(400)
                    if (snapshot.elements.any { it.value == "Saved: Hello Jev" }) return Decision(Operation.DONE)
                    val input = snapshot.elements.first { Operation.SET_TEXT in it.operations }
                    return if (input.value != "Hello Jev") Decision(Operation.SET_TEXT, input.id, "message")
                    else Decision(Operation.CLICK, snapshot.elements.first { it.value.equals("Save", ignoreCase = true) && Operation.CLICK in it.operations }.id)
                }
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                service.start(Task("Save Hello Jev", setOf("io.github.jevandroid.sample"), mapOf("message" to "Hello Jev")), provider,
                    verifier = OutcomeVerifier { _, snapshot -> snapshot.elements.any { it.value == "Saved: Hello Jev" } },
                    onEvent = { event -> if (event is AgentEvent.Finished) result.complete(event.result) },
                    onError = { error -> result.completeExceptionally(error) })
            }
            val completed = runBlocking { withTimeout(15_000) { result.await() } }
            assertEquals(completed.message, Status.VERIFIED, completed.status)
            assertEquals(2, completed.steps)
        }
    }

    @Test fun changedUiIsRejectedAndOutsidePackageIsNotRead() = withService { service ->
        ActivityScenario.launch(FixtureActivity::class.java).useWithExplicitFinish { scenario ->
            runBlocking {
                val runtime = AccessibilityRuntime(service)
                val task = Task("Save", setOf("io.github.jevandroid.sample"))
                val snapshot = awaitFixture(runtime, task, "Save")
                val save = snapshot.elements.first { it.value.equals("Save", ignoreCase = true) && Operation.CLICK in it.operations }
                fun findInput(view: View): EditText? {
                    if (view is EditText) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) findInput(view.getChildAt(i))?.let { return it }
                    return null
                }
                scenario.onActivity { activity -> findInput(activity.findViewById(android.R.id.content))!!.setText("Changed externally") }
                delay(200)
                assertEquals(ActionResult.StaleBeforeDispatch,
                    runtime.executeWithResult(task, snapshot, Decision(Operation.CLICK, save.id)))
                assertFalse(runtime.execute(task, snapshot, Decision(Operation.CLICK, save.id)))
                val outside = runtime.observe(Task("Other", setOf("not.allowed")))
                assertTrue(outside.elements.isEmpty())
            }
        }
    }

    @Test(timeout = 30_000) fun performsTimedLongPressWithMeasuredTouchDuration() = withService { service ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, FixtureActivity::class.java)
            .putExtra(FixtureActivity.EXTRA_KIND, FixtureKind.LONG_PRESS.name)
        ActivityScenario.launch<FixtureActivity>(intent).useWithExplicitFinish { scenario ->
            val touches = java.util.concurrent.CopyOnWriteArrayList<String>()
            var buttonBounds = ""
            scenario.onActivity { activity ->
                val button = views(activity.findViewById(android.R.id.content)).filterIsInstance<android.widget.Button>()
                    .first { it.text.toString() == "Hold to confirm" }
                val location = IntArray(2).also(button::getLocationOnScreen)
                buttonBounds = "${location.toList()}, ${button.width}x${button.height}"
                val original = activity.window.callback
                activity.window.callback = object : Window.Callback by original {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked != MotionEvent.ACTION_MOVE)
                            touches += "${event.actionMasked}: ${event.rawX},${event.rawY}; elapsed=${event.eventTime - event.downTime}"
                        return original.dispatchTouchEvent(event)
                    }
                }
            }
            runBlocking {
                val runtime = AccessibilityRuntime(service)
                val task = Task("Hold the fixture control", setOf(context.packageName))
                // An observed node can appear before the newly launched input window is ready.
                awaitFixture(runtime, task, "Hold to confirm")
                delay(800)
                val before = awaitFixture(runtime, task, "Hold to confirm")
                val hold = before.elements.first { it.value.equals("Hold to confirm", ignoreCase = true) }
                assertTrue("Timed hold must be advertised", Operation.LONG_PRESS in hold.operations)
                assertFalse("The touch-only fixture must not advertise native long-click", Operation.LONG_CLICK in hold.operations)
                try {
                    runtime.execute(task, before, Decision(Operation.LONG_CLICK, hold.id))
                    fail("Native long-click on a click-only control must be rejected")
                } catch (_: IllegalArgumentException) { }
                val accepted = runtime.execute(task, before, Decision(Operation.LONG_PRESS, hold.id))
                delay(200)
                val afterHold = runtime.observe(task)
                assertTrue("Hold rejected; foreground=${afterHold.packageName}, touches=$touches", accepted)
                assertTrue("Fixture result after hold: package=${afterHold.packageName}, values=${afterHold.elements.map { it.value }}, bounds=$buttonBounds, touches=$touches",
                    afterHold.elements.any { it.value == "Long press confirmed" })
                val stats = afterHold.elements.first { it.value.startsWith("Completed holds: 1;") }.value
                val elapsed = stats.substringAfter("duration: ").substringBefore(" ms").toLong()
                assertTrue("Hold ended too early: $elapsed ms", elapsed >= 1_800)
                assertFalse(afterHold.elements.any { it.value == "A tap is not a long press" })
                // The outcome changed the screen, so the old snapshot cannot dispatch another hold.
                assertFalse(runtime.execute(task, before, Decision(Operation.LONG_PRESS, hold.id)))
            }
        }
    }

    @Test(timeout = 45_000) fun changedUiIsObservedAgainBeforeSavingOnce() = withService { service ->
        ActivityScenario.launch(FixtureActivity::class.java).useWithExplicitFinish { scenario ->
            val task = Task("Save Hello Jev", setOf("io.github.jevandroid.sample"), mapOf("message" to "Hello Jev"))
            runBlocking { awaitFixture(AccessibilityRuntime(service), task, "Save") }
            lateinit var field: EditText
            lateinit var save: android.widget.Button
            var saved = false
            var clicks = 0
            var refreshes = 0
            var changed = false
            val result = CompletableDeferred<RunResult>()
            scenario.onActivity { activity ->
                val all = views(activity.findViewById(android.R.id.content))
                field = all.filterIsInstance<EditText>().single()
                save = all.filterIsInstance<android.widget.Button>().single { it.text.toString() == "Save" }
                field.setText("Hello Jev")
                save.setOnClickListener { clicks++; saved = field.text.toString() == "Hello Jev" }
            }
            val provider = object : DecisionProvider {
                override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision {
                    if (saved) return Decision(Operation.DONE)
                    val target = snapshot.elements.first { it.value.equals("Save", ignoreCase = true) && Operation.CLICK in it.operations }
                    if (!changed) {
                        changed = true
                        // Change the observed target while the simulated model call is in flight.
                        withContext(Dispatchers.Main.immediate) { save.contentDescription = "Save confirmed text" }
                        delay(400)
                    }
                    return Decision(Operation.CLICK, target.id)
                }
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                service.start(task, provider, OutcomeVerifier { _, _ -> saved }, onEvent = { event ->
                    if (event is AgentEvent.Refreshing) refreshes++
                    if (event is AgentEvent.Finished) result.complete(event.result)
                }, onError = { result.completeExceptionally(it) })
            }
            val completed = runBlocking { withTimeout(15_000) { result.await() } }
            assertEquals(completed.message, Status.VERIFIED, completed.status)
            assertEquals(1, refreshes)
            assertEquals(1, clicks)
            assertEquals(1, completed.steps)
        }
    }

    /** Optional launchPackage argument exercises a real installed app without any account action. */
    @Test(timeout = 45_000) fun openAppWaitsForRequestedForeground() = withService { service ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = InstrumentationRegistry.getArguments().getString("launchPackage")
            ?: instrumentation.targetContext.packageName
        val task = Task("Open the requested app and stop", setOf(target))
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            }
            withTimeout(5_000) {
                while (AccessibilityRuntime(service).observe(task).packageName == target) delay(100)
            }
            delay(500)
        }
        val result = CompletableDeferred<RunResult>()
        val executed = mutableListOf<StepRecord>()
        val provider = object : DecisionProvider {
            override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision {
                delay(400)
                if (history.isEmpty()) return Decision(Operation.OPEN_APP, target)
                check(snapshot.packageName == target) { "The first observation after OPEN_APP must be the requested app" }
                return Decision(Operation.DONE)
            }
        }
        instrumentation.runOnMainSync {
            service.start(task, provider, OutcomeVerifier { _, snapshot -> snapshot.packageName == target }, onEvent = { event ->
                if (event is AgentEvent.Executed) executed += event.record
                if (event is AgentEvent.Finished) result.complete(event.result)
            }, onError = { result.completeExceptionally(it) })
        }
        val completed = runBlocking { withTimeout(20_000) { result.await() } }
        assertEquals(completed.message, Status.VERIFIED, completed.status)
        assertEquals(listOf(Operation.OPEN_APP), executed.map { it.operation })
        assertTrue(executed.single().accepted)
        assertEquals(1, completed.steps)
    }

    @Test fun performsNativeLongClickWithoutSynthesizingATouch() = withService { service ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, FixtureActivity::class.java)
            .putExtra(FixtureActivity.EXTRA_KIND, FixtureKind.LONG_PRESS.name)
        ActivityScenario.launch<FixtureActivity>(intent).useWithExplicitFinish {
            runBlocking {
                val runtime = AccessibilityRuntime(service)
                val task = Task("Use native long-click", setOf(context.packageName))
                val before = awaitFixture(runtime, task, "Native long click")
                val native = before.elements.first { it.value.equals("Native long click", ignoreCase = true) }
                assertTrue(Operation.LONG_CLICK in native.operations)
                assertTrue(runtime.execute(task, before, Decision(Operation.LONG_CLICK, native.id)))
                val after = awaitFixture(runtime, task, "Native long click confirmed")
                assertTrue(after.elements.any { it.value == "Completed holds: 0; duration: 0 ms" })
                assertFalse(after.elements.any { it.value == "A tap is not a long press" })
                assertFalse(runtime.execute(task, before, Decision(Operation.LONG_CLICK, native.id)))
            }
        }
    }

    @Test fun selectingScenariosFillsFieldsWithoutRunningATask() {
        ActivityScenario.launch(MainActivity::class.java).useWithExplicitFinish { scenario ->
            scenario.onActivity { activity ->
                val all = views(activity.findViewById(android.R.id.content))
                val picker = all.filterIsInstance<Spinner>().single { it.contentDescription == "Scenario selector" }
                picker.setSelection(TaskScenarios.create(activity.packageName).indexOfFirst { it.id == "bilibili_triple" })
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val all = views(activity.findViewById(android.R.id.content))
                val fields = all.filterIsInstance<EditText>().associateBy { it.hint.toString() }
                assertEquals("tv.danmaku.bili", fields.getValue("Allowed package names, separated by commas").text.toString())
                assertEquals("testv", fields.getValue("Input candidates for the model, one value per line").text.toString())
                assertTrue(fields.getValue("Task").text.toString().contains("press and hold the Like button once"))
                assertTrue(all.filterIsInstance<android.widget.TextView>().any { it.text.toString() == "Ready" })
                all.filterIsInstance<Spinner>().single { it.contentDescription == "Scenario selector" }
                    .setSelection(TaskScenarios.create(activity.packageName).indexOfFirst { it.id == "custom" })
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val fields = views(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>().associateBy { it.hint.toString() }
                assertEquals("", fields.getValue("Task").text.toString())
                assertEquals("", fields.getValue("Allowed package names, separated by commas").text.toString())
                assertEquals("", fields.getValue("Input candidates for the model, one value per line").text.toString())
            }
        }
    }
}
