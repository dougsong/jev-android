package io.github.jevandroid

import io.github.jevandroid.core.Decision
import io.github.jevandroid.core.DecisionRules
import io.github.jevandroid.core.Element
import io.github.jevandroid.core.Operation
import io.github.jevandroid.core.Task
import io.github.jevandroid.core.UiSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/** Snapshot-bound aliases select complete operation/target pairs, never independent model IDs. */
internal class DeepSeekChoices private constructor(
    actions: Map<String, Action>,
    textKeys: List<String>,
    private val elements: List<NodeDescription>,
    private val apps: List<AppDescription>,
    private val globals: Map<Operation, String>,
) {
    internal data class Action(val operation: Operation, val target: String?)

    val actions: Map<String, Action> = Collections.unmodifiableMap(LinkedHashMap(actions))
    val textKeys: List<String> = Collections.unmodifiableList(ArrayList(textKeys))

    /** Callers can modify the returned JSON without changing this catalog or later requests. */
    fun describe(): JSONObject = JSONObject()
        .put("elements", JSONArray(elements.map { node ->
            JSONObject().put("label", node.element.label).put("role", node.element.role)
                .put("value", node.element.value).put("checked", node.element.checked ?: JSONObject.NULL)
                .put("resource_id", node.element.resourceId ?: JSONObject.NULL)
                .put("context", JSONArray(node.context)).put("actions", operationAliases(node.actions))
        }))
        .put("apps", JSONArray(apps.map { app ->
            JSONObject().put("label", app.label)
                .put("actions", JSONObject().put(Operation.OPEN_APP.name, app.alias))
        }))
        .put("global_actions", operationAliases(globals))

    private data class NodeDescription(
        val element: Element,
        val context: List<String>,
        val actions: Map<Operation, String>,
    )

    private data class AppDescription(val label: String, val alias: String)

    companion object {
        private val targeted = listOf(
            Operation.CLICK, Operation.LONG_CLICK, Operation.LONG_PRESS, Operation.SET_TEXT,
            Operation.SCROLL_FORWARD, Operation.SCROLL_BACKWARD,
        )
        private val nodePath = Regex("^0(?:\\.\\d+)*$")

        fun create(task: Task, snapshot: UiSnapshot): DeepSeekChoices {
            val inAllowedPackage = snapshot.packageName in task.allowedPackages
            val elements = if (inAllowedPackage) snapshot.elements.map { element ->
                element.copy(operations = element.operations.toSet())
            } else emptyList()
            val apps = snapshot.apps.filterKeys { it in task.allowedPackages }.toSortedMap()
            val keys = task.textValues.keys.sorted()
            val scope = scope(task, snapshot, elements, apps)
            val actions = linkedMapOf<String, Action>()

            fun add(operation: Operation, target: String? = null): String {
                // Recheck capabilities here, as well as when the eventual response is parsed.
                DecisionRules.validate(task, snapshot, Decision(operation, target,
                    if (operation == Operation.SET_TEXT) keys.first() else null))
                val alias = "a${scope}_${actions.size}"
                actions[alias] = Action(operation, target)
                return alias
            }

            val globals = linkedMapOf(
                Operation.WAIT to add(Operation.WAIT),
                Operation.DONE to add(Operation.DONE),
                Operation.BLOCKED to add(Operation.BLOCKED),
            )
            if (inAllowedPackage) globals[Operation.BACK] = add(Operation.BACK)

            val descriptions = elements.map { element ->
                val nodeActions = targeted.filter { operation ->
                    operation in element.operations && (operation != Operation.SET_TEXT || keys.isNotEmpty())
                }.associateWith { operation -> add(operation, element.id) }
                NodeDescription(element, descendantContext(element, elements), nodeActions)
            }
            val appDescriptions = apps.map { (packageName, label) ->
                AppDescription(label, add(Operation.OPEN_APP, packageName))
            }
            return DeepSeekChoices(actions, keys, descriptions, appDescriptions, globals)
        }

        /** Labels provide context only; descendants never contribute executable capabilities. */
        private fun descendantContext(parent: Element, elements: List<Element>): List<String> {
            if (!nodePath.matches(parent.id) || parent.operations.none { it in targeted }) return emptyList()
            val prefix = "${parent.id}."
            val descendants = elements.filter { nodePath.matches(it.id) && it.id.startsWith(prefix) }
            val boundaries = descendants.filter { child -> child.operations.any { it in targeted } }
                .mapTo(hashSetOf()) { it.id }
            val ownText = setOf(parent.label.trim().take(160), parent.value.trim().take(160))
            val context = linkedSetOf<String>()
            for (child in descendants) {
                var ancestor = child.id
                var separateControl = false
                while (ancestor.startsWith(prefix)) {
                    if (ancestor in boundaries) {
                        separateControl = true
                        break
                    }
                    ancestor = ancestor.substringBeforeLast('.', "")
                }
                if (separateControl) continue
                for (text in listOf(child.label, child.value)) {
                    val snippet = text.trim().take(160)
                    if (snippet.isNotEmpty() && snippet !in ownText) context += snippet
                    if (context.size == 6) return context.toList()
                }
            }
            return context.toList()
        }

        /** Length-delimited hashing includes bindings even when a custom runtime reuses a fingerprint. */
        private fun scope(
            task: Task,
            snapshot: UiSnapshot,
            elements: List<Element>,
            apps: Map<String, String>,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String?) {
                if (value == null) {
                    digest.update(ByteBuffer.allocate(4).putInt(-1).array())
                } else {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                    digest.update(bytes)
                }
            }
            add("jev-deepseek-choices-v1")
            add(snapshot.fingerprint)
            add(snapshot.gestureFingerprint)
            add(snapshot.packageName)
            add(task.goal)
            add(task.longPressDurationMillis.toString())
            add(task.allowedPackages.size.toString())
            task.allowedPackages.sorted().forEach(::add)
            add(task.textValues.size.toString())
            task.textValues.toSortedMap().forEach { (key, value) -> add(key); add(value) }
            add(elements.size.toString())
            elements.forEach { element ->
                add(element.id)
                add(element.label)
                add(element.role)
                add(element.value)
                add(element.checked?.toString())
                add(element.resourceId)
                val operations = targeted.filter { it in element.operations }
                add(operations.size.toString())
                operations.forEach { add(it.name) }
            }
            add(apps.size.toString())
            apps.forEach { (packageName, label) -> add(packageName); add(label) }
            return digest.digest().take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun operationAliases(aliases: Map<Operation, String>): JSONObject = JSONObject().apply {
            aliases.forEach { (operation, alias) -> put(operation.name, alias) }
        }
    }
}
