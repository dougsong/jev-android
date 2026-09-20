package io.github.jevandroid

/** Geometry from accessibility and display metadata, never from model-provided coordinates. */
internal data class ScreenBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

internal data class LongPressTarget(val x: Float, val y: Float)

internal fun longPressTarget(
    node: ScreenBounds,
    window: ScreenBounds,
    display: ScreenBounds,
    occlusions: List<ScreenBounds>,
): LongPressTarget? {
    val left = maxOf(node.left, window.left, display.left)
    val top = maxOf(node.top, window.top, display.top)
    val right = minOf(node.right, window.right, display.right)
    val bottom = minOf(node.bottom, window.bottom, display.bottom)
    if (left >= right || top >= bottom) return null
    // Use the center of the visible intersection, avoiding clipped-off portions of the node.
    val x = (left.toDouble() + right) / 2.0
    val y = (top.toDouble() + bottom) / 2.0
    val target = LongPressTarget(x.toFloat(), y.toFloat())
    return target.takeUnless { point -> occlusions.any { it.contains(point.x, point.y) } }
}
