package io.github.jevandroid

import org.junit.Assert.*
import org.junit.Test

class LongPressTargetTest {
    private val screen = ScreenBounds(0, 0, 1080, 2400)

    @Test fun usesCenterOfObservedNode() {
        assertEquals(LongPressTarget(150f, 240f), longPressTarget(ScreenBounds(100, 200, 200, 280), screen, screen, emptyList()))
    }

    @Test fun clipsToWindowAndDisplayBeforeChoosingCenter() {
        val node = ScreenBounds(-100, 300, 1500, 900)
        val window = ScreenBounds(100, 200, 1400, 800)
        assertEquals(LongPressTarget(590f, 550f), longPressTarget(node, window, screen, emptyList()))
    }

    @Test fun rejectsEmptyInvertedAndOffscreenBounds() {
        listOf(ScreenBounds(10, 10, 10, 100), ScreenBounds(50, 50, 10, 10),
            ScreenBounds(-100, -100, -1, -1), ScreenBounds(1080, 0, 1200, 100))
            .forEach { assertNull(longPressTarget(it, screen, screen, emptyList())) }
        assertNull(longPressTarget(screen, ScreenBounds(0, 0, 0, 0), screen, emptyList()))
    }

    @Test fun rejectsCenterCoveredByAnotherWindowOrStopButton() {
        val node = ScreenBounds(100, 200, 200, 280)
        assertNull(longPressTarget(node, screen, screen, listOf(ScreenBounds(140, 230, 170, 250))))
        assertEquals(LongPressTarget(150f, 240f), longPressTarget(node, screen, screen, listOf(ScreenBounds(800, 0, 1080, 150))))
    }

    @Test fun largeBoundsCannotOverflowCenterArithmetic() {
        assertEquals(LongPressTarget(540f, 1200f), longPressTarget(
            ScreenBounds(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE), screen, screen, emptyList()))
    }
}
