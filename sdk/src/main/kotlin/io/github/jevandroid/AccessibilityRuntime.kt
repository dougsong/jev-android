@file:Suppress("DEPRECATION")
package io.github.jevandroid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import io.github.jevandroid.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Native node actions and explicit holds on observed node bounds; no model-supplied coordinates. */
class AccessibilityRuntime(private val service: AccessibilityService) : DetailedDeviceRuntime {
    private data class Capture(
        val snapshot: UiSnapshot,
        val nodes: Map<String, AccessibilityNodeInfo>,
        val longPressTargets: Map<String, LongPressTarget>,
    ) {
        fun close() = nodes.values.forEach { it.recycle() }
    }
    private data class GestureContext(
        val window: ScreenBounds,
        val display: ScreenBounds,
        val occlusions: List<ScreenBounds>,
    )

    override suspend fun observe(task: Task): UiSnapshot = withContext(Dispatchers.Main.immediate) {
        val capture = capture(task)
        try { capture.snapshot } finally { capture.close() }
    }

    override suspend fun execute(task: Task, snapshot: UiSnapshot, decision: Decision): Boolean =
        executeWithResult(task, snapshot, decision) == ActionResult.Accepted

    override suspend fun executeWithResult(task: Task, snapshot: UiSnapshot, decision: Decision): ActionResult = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        DecisionRules.validate(task, snapshot, decision)
        val fresh = capture(task)
        try {
            // This is the only retryable result: no Android action has been submitted yet.
            if (!SnapshotFingerprints.matches(snapshot, fresh.snapshot, decision.operation))
                return@withContext ActionResult.StaleBeforeDispatch
            currentCoroutineContext().ensureActive()
            when (decision.operation) {
                Operation.OPEN_APP -> {
                    val packageName = requireNotNull(decision.target)
                    val intent = service.packageManager.getLaunchIntentForPackage(packageName)
                        ?: return@withContext ActionResult.Rejected("App launch intent unavailable")
                    service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    // startActivity only submits the launch. The old launcher root can remain
                    // active for much longer than the agent's normal inter-action delay.
                    if (awaitForeground(packageName)) ActionResult.Accepted
                    else ActionResult.Rejected("App launch was submitted but its foreground window was not observed within 10 seconds; inspect before restarting")
                }
                Operation.WAIT -> ActionResult.Accepted
                Operation.BACK -> nativeResult(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK), decision.operation)
                Operation.CLICK -> nativeResult(fresh.nodes[decision.target]?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true, decision.operation)
                Operation.LONG_CLICK -> nativeResult(fresh.nodes[decision.target]?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true, decision.operation)
                Operation.LONG_PRESS -> {
                    val target = fresh.longPressTargets[decision.target]
                        ?: return@withContext ActionResult.Rejected("Long-press target unavailable")
                    if (!performLongPress(target, task.longPressDurationMillis))
                        return@withContext ActionResult.Rejected("Long press was cancelled or not accepted; inspect before restarting")
                    // OS gesture completion does not prove that the app received the hold.
                    // A system long-press recognizer may instead have opened another app.
                    delay(150)
                    val after = capture(task)
                    try {
                        if (after.snapshot.packageName == snapshot.packageName &&
                            after.snapshot.packageName in task.allowedPackages) ActionResult.Accepted
                        else ActionResult.Rejected("Foreground app changed after long press; the hold may have been intercepted. Inspect before restarting")
                    } finally { after.close() }
                }
                Operation.SCROLL_FORWARD -> nativeResult(fresh.nodes[decision.target]?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true, decision.operation)
                Operation.SCROLL_BACKWARD -> nativeResult(fresh.nodes[decision.target]?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true, decision.operation)
                Operation.SET_TEXT -> {
                    val node = fresh.nodes[decision.target] ?: return@withContext ActionResult.Rejected("Input target unavailable")
                    if (node.isPassword) return@withContext ActionResult.Rejected("Password fields cannot be edited")
                    val text = task.textValues.getValue(requireNotNull(decision.textKey))
                    val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    })
                    if (!accepted) return@withContext nativeResult(false, decision.operation)
                    delay(150)
                    // Do not continue from an input operation whose value cannot be read back.
                    if (node.refresh() && node.text?.toString() == text) ActionResult.Accepted
                    else ActionResult.Rejected("Text was submitted but could not be confirmed; inspect before restarting")
                }
                else -> ActionResult.Rejected("Operation cannot be executed by the runtime")
            }
        } finally { fresh.close() }
    }

    private fun nativeResult(accepted: Boolean, operation: Operation): ActionResult =
        if (accepted) ActionResult.Accepted
        else ActionResult.Rejected("$operation was rejected by Android; inspect before restarting")

    private suspend fun awaitForeground(packageName: String): Boolean = withTimeoutOrNull(10_000) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val root = service.rootInActiveWindow
            val matches = try { root?.packageName?.toString() == packageName }
                finally { root?.recycle() }
            if (matches) return@withTimeoutOrNull true
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") false
    } ?: false

    private suspend fun performLongPress(target: LongPressTarget, durationMillis: Long): Boolean {
        currentCoroutineContext().ensureActive()
        val path = Path().apply { moveTo(target.x, target.y) }
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setDisplayId(Display.DEFAULT_DISPLAY)
        val gesture = builder.build()
        return withTimeoutOrNull(durationMillis + 1_500) {
            suspendCancellableCoroutine { continuation ->
                if (!continuation.isActive) return@suspendCancellableCoroutine
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                val accepted = try { service.dispatchGesture(gesture, callback, null) }
                    catch (_: RuntimeException) { false }
                if (!accepted && continuation.isActive) continuation.resume(false)
                // Android has no cancelGesture API. Cancelling stops waiting and later actions;
                // a hold already submitted to the OS can finish within the bounded duration.
            }
        } ?: false
    }

    private fun Rect.screenBounds() = ScreenBounds(left, top, right, bottom)

    private fun gestureContext(root: AccessibilityNodeInfo?): GestureContext? {
        if (root == null || ((service.serviceInfo?.capabilities ?: 0) and
            AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) == 0) return null
        val window = root.window ?: return null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && window.displayId != Display.DEFAULT_DISPLAY) return null
            val windows = service.windows
            try {
                // Before API 30, getWindows() exposes only windows on the default display.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && windows.none { it.id == window.id }) return null
                val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                    ?: return null
                val size = Point().also(display::getRealSize)
                if (size.x <= 0 || size.y <= 0) return null
                val windowBounds = Rect().also(window::getBoundsInScreen).screenBounds()
                val occlusions = windows.filter { candidate ->
                    candidate.id != window.id && candidate.layer > window.layer &&
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || candidate.displayId == Display.DEFAULT_DISPLAY)
                }.map { candidate -> Rect().also(candidate::getBoundsInScreen).screenBounds() }.toMutableList()
                (service as? JevAccessibilityService)?.stopButtonBounds()?.let(occlusions::add)
                return GestureContext(windowBounds, ScreenBounds(0, 0, size.x, size.y), occlusions)
            } finally { windows.forEach { it.recycle() } }
        } finally { window.recycle() }
    }

    private fun capture(task: Task): Capture {
        val apps = task.allowedPackages.sorted().mapNotNull { name ->
            val pm = service.packageManager
            if (pm.getLaunchIntentForPackage(name) == null) null else {
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString() }.getOrDefault(name)
                name to label
            }
        }.toMap()
        val root = service.rootInActiveWindow
        val packageName = root?.packageName?.toString().orEmpty()
        val gestureContext = if (packageName in task.allowedPackages) {
            try { gestureContext(root) } catch (_: RuntimeException) { null }
        } else null
        val nodes = linkedMapOf<String, AccessibilityNodeInfo>()
        val longPressTargets = linkedMapOf<String, LongPressTarget>()
        val elements = mutableListOf<Element>()
        val signatures = mutableListOf<String>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            try {
                if (depth > 40 || visited++ >= 1200 || elements.size >= 220 || !node.isVisibleToUser || node.isPassword) return
                val bounds = Rect().also(node::getBoundsInScreen)
                val text = node.text?.toString().orEmpty().take(300)
                val label = node.contentDescription?.toString()?.take(300)
                    ?: node.hintText?.toString()?.take(300) ?: text
                val supported = node.actionList.map { it.id }.toSet()
                val holdTarget = if (gestureContext != null && node.isEnabled &&
                    (AccessibilityNodeInfo.ACTION_CLICK in supported || AccessibilityNodeInfo.ACTION_LONG_CLICK in supported))
                    longPressTarget(bounds.screenBounds(), gestureContext.window, gestureContext.display, gestureContext.occlusions)
                    else null
                val operations = buildSet {
                    if (node.isEnabled) {
                        if (AccessibilityNodeInfo.ACTION_CLICK in supported) add(Operation.CLICK)
                        if (AccessibilityNodeInfo.ACTION_LONG_CLICK in supported) add(Operation.LONG_CLICK)
                        if (holdTarget != null) add(Operation.LONG_PRESS)
                        if (AccessibilityNodeInfo.ACTION_SET_TEXT in supported) add(Operation.SET_TEXT)
                        if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in supported) add(Operation.SCROLL_FORWARD)
                        if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in supported) add(Operation.SCROLL_BACKWARD)
                    }
                }
                if (text.isNotBlank() || label.isNotBlank() || operations.isNotEmpty()) {
                    val element = Element(path, label, node.className?.toString().orEmpty(), text,
                        if (node.isCheckable) node.isChecked else null, operations)
                    elements += element
                    nodes[path] = AccessibilityNodeInfo.obtain(node)
                    if (holdTarget != null) longPressTargets[path] = holdTarget
                    signatures += SnapshotFingerprints.node(element, node.viewIdResourceName, bounds.screenBounds(),
                        node.isEnabled, node.isFocused, node.isSelected)
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, "$path.$i", depth + 1) }
            } finally { node.recycle() }
        }
        val windowId = root?.windowId ?: -1
        try {
            if (root != null) {
                if (packageName in task.allowedPackages) visit(root, "0", 0) else root.recycle()
            }
            val fingerprint = SnapshotFingerprints.ui(packageName, windowId, signatures, apps)
            val gestureFingerprint = gestureContext?.let { context ->
                SnapshotFingerprints.gestures(context.window, context.display, context.occlusions, longPressTargets)
            }
            return Capture(UiSnapshot(fingerprint, packageName, elements, apps, gestureFingerprint), nodes, longPressTargets)
        } catch (e: Exception) {
            nodes.values.forEach { it.recycle() }
            throw e
        }
    }
}
