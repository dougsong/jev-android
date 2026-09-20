package io.github.jevandroid

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import io.github.jevandroid.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Declare this service (or a subclass) in the host manifest; see sample. */
open class JevAccessibilityService : AccessibilityService() {
    companion object {
        private val connection = MutableStateFlow<JevAccessibilityService?>(null)
        val connected: StateFlow<JevAccessibilityService?> = connection
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active: Job? = null
    private var stopButton: Button? = null
    override fun onServiceConnected() { connection.value = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { stop() }

    /** Call on the main thread. One run per service; Job cancellation stops network requests and future actions. */
    fun start(
        task: Task,
        provider: DecisionProvider,
        verifier: OutcomeVerifier? = null,
        gate: ActionGate = ActionGate { _, _, _ -> true },
        onEvent: (AgentEvent) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ): Job {
        check(android.os.Looper.myLooper() == mainLooper) { "Call start on main thread" }
        check(active?.isActive != true) { "A task is already running" }
        return scope.launch(start = CoroutineStart.LAZY) {
            try {
                showStopButton()
                JevAgent(AccessibilityRuntime(this@JevAccessibilityService), provider, verifier, gate).run(task, onEvent)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e) }
            finally { removeStopButton() }
        }.also { active = it; it.start() }
    }

    fun stop() { active?.cancel() }
    private fun showStopButton() {
        val button = Button(this).apply { text = "Stop Jev"; setOnClickListener { stop() } }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 80 }
        getSystemService(WindowManager::class.java).addView(button, params)
        stopButton = button
    }
    private fun removeStopButton() {
        stopButton?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        stopButton = null
    }
    override fun onDestroy() {
        stop(); scope.cancel(); removeStopButton()
        if (connection.value === this) connection.value = null
        super.onDestroy()
    }
}
