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
import java.security.MessageDigest
import kotlin.coroutines.resume

/** Native node actions and explicit holds on observed node bounds; no model-supplied coordinates. */
class AccessibilityRuntime(private val service: AccessibilityService) : DeviceRuntime {
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

    override suspend fun execute(task: Task, snapshot: UiSnapshot, decision: Decision): Boolean = withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        DecisionRules.validate(task, snapshot, decision)
        val fresh = capture(task)
        try {
            if (snapshot.fingerprint != fresh.snapshot.fingerprint) return@withContext false
            currentCoroutineContext().ensureActive()
            when (decision.operation) {
                Operation.OPEN_APP -> {
                    val intent = service.packageManager.getLaunchIntentForPackage(requireNotNull(decision.target))
                        ?: return@withContext false
                    service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }
                Operation.WAIT -> true
                Operation.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Operation.CLICK -> fresh.nodes[decision.target]?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                Operation.LONG_CLICK -> fresh.nodes[decision.target]?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
                Operation.LONG_PRESS -> {
                    val target = fresh.longPressTargets[decision.target] ?: return@withContext false
                    if (!performLongPress(target, task.longPressDurationMillis)) return@withContext false
                    // OS gesture completion does not prove that the app received the hold.
                    // A system long-press recognizer may instead have opened another app.
                    delay(150)
                    val after = capture(task)
                    try {
                        after.snapshot.packageName == snapshot.packageName &&
                            after.snapshot.packageName in task.allowedPackages
                    } finally { after.close() }
                }
                Operation.SCROLL_FORWARD -> fresh.nodes[decision.target]?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
                Operation.SCROLL_BACKWARD -> fresh.nodes[decision.target]?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
                Operation.SET_TEXT -> {
                    val node = fresh.nodes[decision.target] ?: return@withContext false
                    if (node.isPassword) return@withContext false
                    val text = task.textValues.getValue(requireNotNull(decision.textKey))
                    val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    })
                    if (!accepted) return@withContext false
                    delay(150)
                    // Do not continue from an input operation whose value cannot be read back.
                    node.refresh() && node.text?.toString() == text
                }
                else -> false
            }
        } finally { fresh.close() }
    }

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
                    signatures += "$element|${node.viewIdResourceName}|$bounds|${node.isEnabled}|${node.isFocused}|${node.isSelected}"
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, "$path.$i", depth + 1) }
            } finally { node.recycle() }
        }
        val windowId = root?.windowId ?: -1
        try {
            if (root != null) {
                if (packageName in task.allowedPackages) visit(root, "0", 0) else root.recycle()
            }
            val canonical = "$packageName|$windowId|${signatures.joinToString("\n")}|$apps|$gestureContext|$longPressTargets"
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return Capture(UiSnapshot(digest, packageName, elements, apps), nodes, longPressTargets)
        } catch (e: Exception) {
            nodes.values.forEach { it.recycle() }
            throw e
        }
    }
}
