package eu.kanade.tachiyomi.ui.reader.viewer.pager

// KMK -->
enum class UnpairedPagePosition {
    AUTOMATIC,
    RIGHT,
    LEFT,
    CENTER,
}

/** Pixel coordinates in the window, or in the pager after [relativeTo] is called. */
data class ReaderHinge(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun relativeTo(x: Int, y: Int, width: Int, height: Int): ReaderHinge? {
        if (width <= 0 || height <= 0 || top > y || bottom < y + height) return null
        if (left <= x || right >= x + width || right < left) return null
        return ReaderHinge(left - x, 0, right - x, height)
    }
}

data class PageViewport(val left: Int, val right: Int) {
    val width: Int get() = right - left
}

/** Presentation only: never changes adapter pairing, page indices, or saved progress. */
object ReaderSpreadLayout {
    fun isPortrait(width: Int, height: Int): Boolean = width > 0 && height > width

    fun firstPageOnLeft(isRtl: Boolean, inverted: Boolean): Boolean = !isRtl xor inverted

    fun unpairedPosition(
        position: UnpairedPagePosition,
        sideBySide: Boolean,
        portrait: Boolean,
        isRtl: Boolean,
        inverted: Boolean,
        openingPage: Boolean = false,
    ): UnpairedPagePosition {
        if (!sideBySide || !portrait) return UnpairedPagePosition.CENTER
        if (openingPage) return UnpairedPagePosition.LEFT
        return when (position) {
            UnpairedPagePosition.AUTOMATIC -> if (firstPageOnLeft(isRtl, inverted)) {
                UnpairedPagePosition.LEFT
            } else {
                UnpairedPagePosition.RIGHT
            }
            else -> position
        }
    }

    fun viewport(width: Int, position: UnpairedPagePosition, hinge: ReaderHinge?): PageViewport = when (position) {
        UnpairedPagePosition.LEFT -> PageViewport(0, hinge?.left ?: (width / 2))
        UnpairedPagePosition.RIGHT -> PageViewport(hinge?.right ?: (width / 2), width)
        else -> PageViewport(0, width)
    }

    /** Map each pointer independently when a continuous image is drawn around a physical gap. */
    fun imageX(x: Float, hinge: ReaderHinge): Float = when {
        x >= hinge.right -> x - (hinge.right - hinge.left)
        x > hinge.left -> hinge.left.toFloat()
        else -> x
    }
}
// KMK <--
