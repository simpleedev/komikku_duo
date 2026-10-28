package eu.kanade.presentation.util

// KMK -->
internal data class VerticalWindowHinge(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal data class PaneBounds(val left: Int, val right: Int) {
    val width: Int get() = right - left
}

internal data class HingePaneBounds(val left: PaneBounds, val right: PaneBounds, val hasHinge: Boolean) {
    companion object {
        fun resolve(width: Int, height: Int, windowX: Int, windowY: Int, hinge: VerticalWindowHinge?): HingePaneBounds {
            val left = hinge?.left?.minus(windowX)
            val right = hinge?.right?.minus(windowX)
            val valid = width > 0 && height > 0 && hinge != null && left != null && right != null &&
                left > 0 && right < width && right >= left && hinge.top <= windowY && hinge.bottom >= windowY + height
            return if (valid) {
                HingePaneBounds(PaneBounds(0, left!!), PaneBounds(right!!, width), true)
            } else {
                val safeWidth = width.coerceAtLeast(0)
                HingePaneBounds(PaneBounds(0, safeWidth / 2), PaneBounds(safeWidth / 2, safeWidth), false)
            }
        }
    }
}
// KMK <--
