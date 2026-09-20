package io.github.jevandroid.sample

import android.app.UiAutomation
import android.content.ComponentName
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jevandroid.*
import io.github.jevandroid.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/device fixture tests. No TypeSafe key or network/model call. */
@RunWith(AndroidJUnit4::class)
class RuntimeInstrumentedTest {
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
        ActivityScenario.launch(FixtureActivity::class.java).use {
            runBlocking { delay(800) }
            val result = CompletableDeferred<RunResult>()
            val provider = object : DecisionProvider {
                override suspend fun decide(task: Task, snapshot: UiSnapshot, history: List<StepRecord>): Decision {
                    if (snapshot.elements.any { it.value == "Saved: Hello Jev" }) return Decision(Operation.DONE)
                    val input = snapshot.elements.first { Operation.SET_TEXT in it.operations }
                    return if (input.value != "Hello Jev") Decision(Operation.SET_TEXT, input.id, "message")
                    else Decision(Operation.CLICK, snapshot.elements.first { it.value == "Save" && Operation.CLICK in it.operations }.id)
                }
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                service.start(Task("Save Hello Jev", setOf("io.github.jevandroid.sample"), mapOf("message" to "Hello Jev")), provider,
                    verifier = OutcomeVerifier { _, snapshot -> snapshot.elements.any { it.value == "Saved: Hello Jev" } },
                    onEvent = { event -> if (event is AgentEvent.Finished) result.complete(event.result) },
                    onError = { error -> result.completeExceptionally(error) })
            }
            val completed = runBlocking { withTimeout(15_000) { result.await() } }
            assertEquals(Status.VERIFIED, completed.status)
            assertEquals(2, completed.steps)
        }
    }

    @Test fun changedUiIsRejectedAndOutsidePackageIsNotRead() = withService { service ->
        ActivityScenario.launch(FixtureActivity::class.java).use { scenario ->
            runBlocking {
                delay(800)
                val runtime = AccessibilityRuntime(service)
                val task = Task("Save", setOf("io.github.jevandroid.sample"))
                val snapshot = runtime.observe(task)
                val save = snapshot.elements.first { it.value == "Save" && Operation.CLICK in it.operations }
                fun findInput(view: View): EditText? {
                    if (view is EditText) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) findInput(view.getChildAt(i))?.let { return it }
                    return null
                }
                scenario.onActivity { activity -> findInput(activity.findViewById(android.R.id.content))!!.setText("Changed externally") }
                delay(200)
                assertFalse(runtime.execute(task, snapshot, Decision(Operation.CLICK, save.id)))
                val outside = runtime.observe(Task("Other", setOf("not.allowed")))
                assertTrue(outside.elements.isEmpty())
            }
        }
    }
}
