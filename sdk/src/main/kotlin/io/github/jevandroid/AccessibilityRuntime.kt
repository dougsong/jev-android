@file:Suppress("DEPRECATION")
package io.github.jevandroid

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.github.jevandroid.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Node actions only: no guessed coordinates or hidden gesture fallback. */
class AccessibilityRuntime(private val service: AccessibilityService) : DeviceRuntime {
    private data class Capture(val snapshot: UiSnapshot, val nodes: Map<String, AccessibilityNodeInfo>) {
        fun close() = nodes.values.forEach { it.recycle() }
    }

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
        val nodes = linkedMapOf<String, AccessibilityNodeInfo>()
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
                val operations = buildSet {
                    if (node.isEnabled) {
                        if (AccessibilityNodeInfo.ACTION_CLICK in supported) add(Operation.CLICK)
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
            val canonical = "$packageName|$windowId|${signatures.joinToString("\n")}|$apps"
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return Capture(UiSnapshot(digest, packageName, elements, apps), nodes)
        } catch (e: Exception) {
            nodes.values.forEach { it.recycle() }
            throw e
        }
    }
}
