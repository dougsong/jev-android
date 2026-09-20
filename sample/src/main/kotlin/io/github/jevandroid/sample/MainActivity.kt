package io.github.jevandroid.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import io.github.jevandroid.*
import io.github.jevandroid.core.*
import kotlinx.coroutines.*

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var log: TextView
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var disclosure: TextView
    private lateinit var goal: EditText
    private lateinit var packages: EditText
    private lateinit var input: EditText
    private var preparing: Job? = null
    private val selection = ProviderSelection()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(column) })
        column.addView(TextView(this).apply { text = "Jev Android SDK · 0.2.0"; textSize = 25f })
        column.addView(TextView(this).apply { text = "Decision provider" })
        val backendPicker = Spinner(this).apply {
            isSaveEnabled = false
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ModelBackend.entries.map { it.label })
        }
        column.addView(backendPicker)
        disclosure = TextView(this)
        column.addView(disclosure)
        fun field(hint: String, initial: String = "") = EditText(this).apply {
            this.hint = hint; setText(initial); column.addView(this)
        }
        key = field("TypeSafe API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        model = field("Model", selection.backend.defaultModel).apply { isSaveEnabled = false }
        goal = field("Task", "On the test page, enter Hello Jev, tap Save, and confirm that Saved: Hello Jev is displayed.")
        packages = field("Allowed package names, separated by commas", packageName)
        input = field("Input candidates for the model, one value per line", "Hello Jev")
        fun button(label: String, action: () -> Unit) = column.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        button("1. Enable accessibility service") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("2. Run on the built-in test page") { begin(fixture = true) }
        button("Run custom task") { begin(fixture = false) }
        button("Stop") { preparing?.cancel(); JevAccessibilityService.connected.value?.stop(); append("Stop requested") }
        log = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        column.addView(log)
        backendPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val backend = ModelBackend.entries[position]
                val fields = selection.select(backend, key.text.toString(), model.text.toString())
                key.setText(fields.apiKey)
                key.hint = "${backend.providerName} API Key"
                model.setText(fields.model)
                updateDisclosure()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateDisclosure()
    }

    private fun updateDisclosure() {
        val backend = selection.backend
        disclosure.text = "Starting a task sends UI text from allowed apps, your goal, and candidate input values to ${backend.providerName}, then performs actions automatically. Keys are kept separately in memory and are not saved. Try the built-in test page first." +
            if (backend == ModelBackend.DEEPSEEK) " DeepSeek confidence is self-reported, not a calibrated probability." else ""
    }

    private fun append(message: String) { log.text = (log.text.toString() + "\n" + message).takeLast(10000) }

    private fun begin(fixture: Boolean) {
        val service = JevAccessibilityService.connected.value
        if (service == null) { append("Enable the Jev accessibility service in system settings first."); return }
        if (preparing?.isActive == true) return
        try {
            val backend = selection.backend
            val apiKey = key.text.toString().trim()
            val modelName = model.text.toString().trim()
            val provider = when (backend) {
                ModelBackend.JEV -> JevProvider(apiKey, modelName)
                ModelBackend.DEEPSEEK -> DeepSeekProvider(apiKey, modelName)
            }
            val values = input.text.toString().lines().filter { it.isNotEmpty() }.mapIndexed { i, value -> "value_$i" to value }.toMap()
            val task = Task(goal.text.toString(),
                if (fixture) setOf(packageName) else packages.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(), values)
            val expected = values.values.firstOrNull().orEmpty()
            val verifier = if (fixture) OutcomeVerifier { _, snapshot ->
                snapshot.packageName == packageName && snapshot.elements.any { it.value == "Saved: $expected" }
            } else null
            append("Starting task with ${backend.label}. Use the Stop Jev button at the top right to cancel.")
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
        } catch (e: IllegalArgumentException) { append("Check the selected provider's API key, model, goal, package names, and input candidates.") }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
