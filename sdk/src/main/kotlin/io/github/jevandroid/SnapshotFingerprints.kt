package io.github.jevandroid

import io.github.jevandroid.core.Element
import io.github.jevandroid.core.Operation
import io.github.jevandroid.core.UiSnapshot
import java.security.MessageDigest

/** Native node actions do not depend on overlay geometry; synthesized touch gestures do. */
internal object SnapshotFingerprints {
    fun node(
        element: Element,
        resourceId: String?,
        bounds: ScreenBounds,
        enabled: Boolean,
        focused: Boolean,
        selected: Boolean,
    ): String {
        // LONG_PRESS is synthesized from window geometry, not an advertised native node action.
        val nativeElement = element.copy(operations = element.operations - Operation.LONG_PRESS)
        return "$nativeElement|$resourceId|$bounds|$enabled|$focused|$selected"
    }

    fun ui(packageName: String, windowId: Int, nodes: List<String>, apps: Map<String, String>): String =
        digest("$packageName|$windowId|${nodes.joinToString("\n")}|${apps.toSortedMap()}")

    fun gestures(
        window: ScreenBounds,
        display: ScreenBounds,
        occlusions: List<ScreenBounds>,
        targets: Map<String, LongPressTarget>,
    ): String {
        // Android may return the same occlusions in a different order or include our Stop
        // control both as a window and explicitly. Neither changes the touch geometry.
        val orderedOcclusions = occlusions.distinct().sortedWith(
            compareBy<ScreenBounds>({ it.left }, { it.top }, { it.right }, { it.bottom }),
        )
        return digest("$window|$display|$orderedOcclusions|${targets.toSortedMap()}")
    }

    fun matches(before: UiSnapshot, fresh: UiSnapshot, operation: Operation): Boolean =
        before.fingerprint == fresh.fingerprint &&
            (operation != Operation.LONG_PRESS ||
                (before.gestureFingerprint != null && before.gestureFingerprint == fresh.gestureFingerprint))

    private fun digest(canonical: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
