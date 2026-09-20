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
    /** Fixed diagnostic for the most recent cancellation; contains no task or screen data. */
    var lastStopReason: String? = null
        private set
    override fun onServiceConnected() { connection.value = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { cancelActive("Android interrupted the accessibility service") }

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
        lastStopReason = null
        return scope.launch(start = CoroutineStart.LAZY) {
            try {
                showStopButton()
                awaitStopButtonLayout()
                JevAgent(AccessibilityRuntime(this@JevAccessibilityService), provider, verifier, gate).run(task, onEvent)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e) }
            finally { removeStopButton() }
        }.also { active = it; it.start() }
    }

    fun stop() { cancelActive("Stop requested") }
    private fun cancelActive(reason: String) {
        if (active?.isActive == true) lastStopReason = reason
        active?.cancel()
    }
    internal fun stopButtonBounds(): ScreenBounds? {
        val button = stopButton?.takeIf { it.isShown && it.width > 0 && it.height > 0 } ?: return null
        val location = IntArray(2).also(button::getLocationOnScreen)
        return ScreenBounds(location[0], location[1], location[0] + button.width, location[1] + button.height)
    }
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
    private suspend fun awaitStopButtonLayout() {
        // addView schedules layout. Observe only after the visible Stop control has bounds,
        // including when a real provider suspends long enough for the first frame to run.
        val ready = withTimeoutOrNull(1_500) {
            while (stopButtonBounds() == null) delay(16)
            true
        } ?: false
        check(ready) { "Stop control did not become ready" }
    }
    private fun removeStopButton() {
        stopButton?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        stopButton = null
    }
    override fun onDestroy() {
        cancelActive("Accessibility service disconnected"); scope.cancel(); removeStopButton()
        if (connection.value === this) connection.value = null
        super.onDestroy()
    }
}
