package io.github.jevandroid

import io.github.jevandroid.core.Element
import io.github.jevandroid.core.Operation
import io.github.jevandroid.core.UiSnapshot
import org.junit.Assert.*
import org.junit.Test

class SnapshotFingerprintsTest {
    private val screen = ScreenBounds(0, 0, 1080, 2400)
    private val bounds = ScreenBounds(100, 200, 300, 300)
    private val stop = ScreenBounds(850, 80, 1080, 180)
    private val button = Element("0.1", "Save", "Button", "Save", null,
        setOf(Operation.CLICK, Operation.LONG_CLICK, Operation.LONG_PRESS))
    private val apps = mapOf("test.app" to "Test")
    private val targets = mapOf(button.id to LongPressTarget(200f, 250f))

    private fun node(
        element: Element = button,
        rectangle: ScreenBounds = bounds,
        enabled: Boolean = true,
        focused: Boolean = false,
        selected: Boolean = false,
        resourceId: String = "test:id/save",
    ) = SnapshotFingerprints.node(element, resourceId, rectangle, enabled, focused, selected)

    private fun snapshot(
        element: Element = button,
        occlusions: List<ScreenBounds> = emptyList(),
        availableTargets: Map<String, LongPressTarget> = targets,
    ) = UiSnapshot(SnapshotFingerprints.ui("test.app", 5, listOf(node(element)), apps),
        "test.app", listOf(element), apps,
        SnapshotFingerprints.gestures(screen, screen, occlusions, availableTargets))

    @Test fun stopOverlayAppearingDuringProviderWaitDoesNotRejectNativeActions() {
        val before = snapshot()
        val afterLayout = snapshot(occlusions = listOf(stop))
        assertEquals(before.fingerprint, afterLayout.fingerprint)
        assertNotEquals(before.gestureFingerprint, afterLayout.gestureFingerprint)
        listOf(Operation.CLICK, Operation.LONG_CLICK, Operation.SET_TEXT, Operation.SCROLL_FORWARD).forEach {
            assertTrue("Overlay layout must not invalidate $it", SnapshotFingerprints.matches(before, afterLayout, it))
        }
        assertFalse(SnapshotFingerprints.matches(before, afterLayout, Operation.LONG_PRESS))
    }

    @Test fun coveringTargetRemovesOnlySyntheticHoldAvailabilityAndRejectsHeldTouch() {
        val before = snapshot()
        val covered = snapshot(button.copy(operations = button.operations - Operation.LONG_PRESS),
            occlusions = listOf(bounds), availableTargets = emptyMap())
        assertEquals(before.fingerprint, covered.fingerprint)
        assertTrue(SnapshotFingerprints.matches(before, covered, Operation.CLICK))
        assertFalse(SnapshotFingerprints.matches(before, covered, Operation.LONG_PRESS))
    }

    @Test fun realNodeContentStateAndNativeCapabilitiesRemainInUiIdentity() {
        val original = node()
        listOf(
            node(button.copy(value = "Saved")),
            node(button.copy(label = "Delete")),
            node(button.copy(role = "EditText")),
            node(button.copy(id = "0.2")),
            node(button.copy(checked = true)),
            node(button.copy(operations = setOf(Operation.LONG_PRESS))),
            node(rectangle = ScreenBounds(110, 200, 310, 300)),
            node(enabled = false), node(focused = true), node(selected = true),
            node(resourceId = "test:id/delete"),
        ).forEach { assertNotEquals(original, it) }
    }

    @Test fun changedUiIsRejectedEvenWhenGestureGeometryIsUnchanged() {
        val before = snapshot()
        val changed = snapshot(button.copy(value = "Changed externally"))
        assertEquals(before.gestureFingerprint, changed.gestureFingerprint)
        listOf(Operation.CLICK, Operation.SET_TEXT, Operation.LONG_CLICK, Operation.LONG_PRESS).forEach {
            assertFalse("Real UI changes must invalidate $it", SnapshotFingerprints.matches(before, changed, it))
        }
    }

    @Test fun windowPackageAppAndNodeOrderChangesRemainInUiIdentity() {
        val nodes = listOf(node(), node(button.copy(id = "0.2")))
        val original = SnapshotFingerprints.ui("test.app", 5, nodes, apps)
        listOf(
            SnapshotFingerprints.ui("other.app", 5, nodes, apps),
            SnapshotFingerprints.ui("test.app", 6, nodes, apps),
            SnapshotFingerprints.ui("test.app", 5, nodes.reversed(), apps),
            SnapshotFingerprints.ui("test.app", 5, nodes, emptyMap()),
        ).forEach { assertNotEquals(original, it) }
    }

    @Test fun timedHoldRequiresMatchingNonNullGestureIdentity() {
        val before = snapshot()
        assertTrue(SnapshotFingerprints.matches(before, before.copy(), Operation.LONG_PRESS))
        assertFalse(SnapshotFingerprints.matches(before.copy(gestureFingerprint = null), before, Operation.LONG_PRESS))
        assertFalse(SnapshotFingerprints.matches(before, before.copy(gestureFingerprint = null), Operation.LONG_PRESS))
        val legacy = before.copy(gestureFingerprint = null)
        assertFalse(SnapshotFingerprints.matches(legacy, legacy, Operation.LONG_PRESS))
        assertTrue(SnapshotFingerprints.matches(legacy, legacy, Operation.CLICK))
    }

    @Test fun windowDisplayOcclusionsAndTargetCoordinatesAllProtectHeldTouches() {
        val original = SnapshotFingerprints.gestures(screen, screen, emptyList(), targets)
        val smaller = ScreenBounds(0, 0, 1080, 1200)
        listOf(
            SnapshotFingerprints.gestures(smaller, screen, emptyList(), targets),
            SnapshotFingerprints.gestures(screen, smaller, emptyList(), targets),
            SnapshotFingerprints.gestures(screen, screen, listOf(stop), targets),
            SnapshotFingerprints.gestures(screen, screen, emptyList(), emptyMap()),
            SnapshotFingerprints.gestures(screen, screen, emptyList(), mapOf(button.id to LongPressTarget(210f, 250f))),
        ).forEach { assertNotEquals(original, it) }
    }

    @Test fun equivalentOcclusionOrderingAndDuplicatesDoNotInvalidateHeldTouches() {
        val keyboard = ScreenBounds(0, 1600, 1080, 2400)
        assertEquals(
            SnapshotFingerprints.gestures(screen, screen, listOf(stop, keyboard), targets),
            SnapshotFingerprints.gestures(screen, screen, listOf(keyboard, stop, stop), targets),
        )
    }
}
