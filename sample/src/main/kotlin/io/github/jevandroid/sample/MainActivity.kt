package io.github.jevandroid.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import io.github.jevandroid.*
import io.github.jevandroid.core.*
import kotlinx.coroutines.*

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var log: TextView
    private lateinit var key: EditText
    private lateinit var goal: EditText
    private lateinit var packages: EditText
    private lateinit var input: EditText
    private var preparing: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(column) })
        column.addView(TextView(this).apply { text = "Jev Android SDK · 0.1.0"; textSize = 25f })
        column.addView(TextView(this).apply {
            text = "Starting a task sends UI text from allowed apps, your goal, and candidate input values to TypeSafe, then performs actions automatically. Your API key stays in memory and is not saved. Try the built-in test page first."
        })
        fun field(hint: String, initial: String = "") = EditText(this).apply {
            this.hint = hint; setText(initial); column.addView(this)
        }
        key = field("TypeSafe API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        goal = field("Task", "On the test page, enter Hello Jev, tap Save, and confirm that Saved: Hello Jev is displayed.")
        packages = field("Allowed package names, separated by commas", packageName)
        input = field("Input candidates for Jev, one value per line", "Hello Jev")
        fun button(label: String, action: () -> Unit) = column.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        button("1. Enable accessibility service") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("2. Run on the built-in test page") { begin(fixture = true) }
        button("Run custom task") { begin(fixture = false) }
        button("Stop") { preparing?.cancel(); JevAccessibilityService.connected.value?.stop(); append("Stop requested") }
        log = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        column.addView(log)
    }

    private fun append(message: String) { log.text = (log.text.toString() + "\n" + message).takeLast(10000) }

    private fun begin(fixture: Boolean) {
        val service = JevAccessibilityService.connected.value
        if (service == null) { append("Enable the Jev accessibility service in system settings first."); return }
        if (preparing?.isActive == true) return
        try {
            val provider = JevProvider(key.text.toString().trim())
            val values = input.text.toString().lines().filter { it.isNotEmpty() }.mapIndexed { i, value -> "value_$i" to value }.toMap()
            val task = Task(goal.text.toString(),
                if (fixture) setOf(packageName) else packages.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(), values)
            val expected = values.values.firstOrNull().orEmpty()
            val verifier = if (fixture) OutcomeVerifier { _, snapshot ->
                snapshot.packageName == packageName && snapshot.elements.any { it.value == "Saved: $expected" }
            } else null
            append("Starting task. Use the Stop Jev button at the top right to cancel.")
            if (fixture) startActivity(Intent(this, FixtureActivity::class.java)) else moveTaskToBack(true)
            preparing = scope.launch {
                delay(800)
                try {
                    service.start(task, provider, verifier,
                        onEvent = { event -> append(when (event) {
                            is AgentEvent.Observed -> "Observed ${event.packageName}: ${event.elementCount} elements"
                            is AgentEvent.Chosen -> "Selected ${event.decision.operation}, confidence=${event.decision.confidence}"
                            is AgentEvent.Executed -> "Step ${event.record.step}: accepted=${event.record.accepted}"
                            is AgentEvent.Finished -> "${event.result.status}: ${event.result.message}"
                        }) },
                        onError = { append("Failed: ${it.javaClass.simpleName} ${it.message}") })
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    append("Could not start: ${e.message}")
                }
            }
        } catch (e: IllegalArgumentException) { append("Check the API key, goal, package names, and input candidates.") }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
