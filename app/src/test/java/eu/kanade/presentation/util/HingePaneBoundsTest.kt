package eu.kanade.presentation.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// KMK -->
class HingePaneBoundsTest {
    @Test
    fun `large screens use equal halves without the former 450dp sidebar cap`() {
        val panes = HingePaneBounds.resolve(2400, 1600, 0, 0, null)
        assertEquals(PaneBounds(0, 1200), panes.left)
        assertEquals(PaneBounds(1200, 2400), panes.right)
        assertFalse(panes.hasHinge)
    }

    @Test
    fun `odd widths allocate every pixel exactly once`() {
        val panes = HingePaneBounds.resolve(1801, 1600, 0, 0, null)
        assertEquals(1801, panes.left.width + panes.right.width)
        assertEquals(panes.left.right, panes.right.left)
    }

    @Test
    fun `duo panes exclude the entire hinge rather than dividing through it`() {
        val panes = HingePaneBounds.resolve(1800, 1600, 0, 0, VerticalWindowHinge(858, 0, 942, 1800))
        assertEquals(PaneBounds(0, 858), panes.left)
        assertEquals(PaneBounds(942, 1800), panes.right)
        assertTrue(panes.hasHinge)
    }

    @Test
    fun `off center hinges and system bar offsets use physical visible regions`() {
        val panes = HingePaneBounds.resolve(1800, 1600, 30, 100, VerticalWindowHinge(830, 0, 914, 1800))
        assertEquals(PaneBounds(0, 800), panes.left)
        assertEquals(PaneBounds(884, 1800), panes.right)
        assertTrue(panes.hasHinge)
    }

    @Test
    fun `zero width folds still split at their measured location`() {
        val panes = HingePaneBounds.resolve(1800, 1600, 0, 0, VerticalWindowHinge(800, 0, 800, 1800))
        assertEquals(PaneBounds(0, 800), panes.left)
        assertEquals(PaneBounds(800, 1800), panes.right)
        assertTrue(panes.hasHinge)
    }

    @Test
    fun `unspanning or losing a separating hinge clears its gap`() {
        val hinge = VerticalWindowHinge(800, 0, 884, 1800)
        assertTrue(HingePaneBounds.resolve(1800, 1600, 0, 0, hinge).hasHinge)
        val unspanned = HingePaneBounds.resolve(916, 1600, 884, 0, hinge)
        assertFalse(unspanned.hasHinge)
        assertEquals(PaneBounds(458, 916), unspanned.right)
        assertFalse(HingePaneBounds.resolve(1800, 1600, 0, 0, null).hasHinge)
    }

    @Test
    fun `partial horizontal invalid and unlaid out hinges do not reserve a gap`() {
        for (hinge in listOf(
            VerticalWindowHinge(0, 800, 1800, 884),
            VerticalWindowHinge(800, 500, 884, 1000),
            VerticalWindowHinge(884, 0, 800, 1800),
        )) {
            assertFalse(HingePaneBounds.resolve(1800, 1600, 0, 0, hinge).hasHinge)
        }
        val unlaidOut = HingePaneBounds.resolve(0, 0, 0, 0, VerticalWindowHinge(800, 0, 884, 1800))
        assertEquals(0, unlaidOut.left.width + unlaidOut.right.width)
        assertFalse(unlaidOut.hasHinge)
    }
}
// KMK <--
