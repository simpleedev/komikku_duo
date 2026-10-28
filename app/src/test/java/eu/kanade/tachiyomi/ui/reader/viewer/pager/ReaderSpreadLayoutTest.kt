package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

// KMK -->
class ReaderSpreadLayoutTest {
    @Test
    fun `only portrait metadata enables a single page viewport`() {
        assertTrue(ReaderSpreadLayout.isPortrait(600, 900))
        assertFalse(ReaderSpreadLayout.isPortrait(1200, 900))
        assertFalse(ReaderSpreadLayout.isPortrait(900, 900))
        assertFalse(ReaderSpreadLayout.isPortrait(0, 900))
        assertFalse(ReaderSpreadLayout.isPortrait(-1, -1))
    }

    @ParameterizedTest
    @CsvSource(
        "true, false, RIGHT",
        "false, false, LEFT",
        "true, true, LEFT",
        "false, true, RIGHT",
    )
    fun `automatic follows reading direction and inversion`(rtl: Boolean, inverted: Boolean, side: UnpairedPagePosition) {
        assertEquals(side, position(UnpairedPagePosition.AUTOMATIC, rtl, inverted))
    }

    @Test
    fun `explicit placement including center overrides direction and inversion`() {
        for (side in listOf(UnpairedPagePosition.RIGHT, UnpairedPagePosition.LEFT, UnpairedPagePosition.CENTER)) {
            for (rtl in listOf(true, false)) {
                for (inverted in listOf(true, false)) {
                    assertEquals(side, position(side, rtl, inverted))
                }
            }
        }
    }

    @Test
    fun `single page and landscape images retain full viewport`() {
        for (side in UnpairedPagePosition.entries) {
            assertEquals(UnpairedPagePosition.CENTER, ReaderSpreadLayout.unpairedPosition(side, false, true, true, false))
            assertEquals(UnpairedPagePosition.CENTER, ReaderSpreadLayout.unpairedPosition(side, true, false, true, false))
            assertEquals(PageViewport(0, 1800), ReaderSpreadLayout.viewport(1800, UnpairedPagePosition.CENTER, null))
        }
    }

    @Test
    fun `rtl pair preserves page identities and order with first page on right`() {
        val pages = List(2) { ReaderPage(it) }
        val pair = pairReaderPages(pages, false, null).single()
        assertSame(pages[0], pair.first)
        assertSame(pages[1], pair.second)
        assertFalse(ReaderSpreadLayout.firstPageOnLeft(true, false))
        assertTrue(ReaderSpreadLayout.firstPageOnLeft(false, false))
        assertTrue(ReaderSpreadLayout.firstPageOnLeft(true, true))
    }

    @Test
    fun `odd chapter leaves last page unpaired and automatic places it on right`() {
        val pages = List(3) { ReaderPage(it) }
        val pairs = pairReaderPages(pages, false, null)
        assertEquals(2, pairs.size)
        assertSame(pages[2], pairs.last().first)
        assertNull(pairs.last().second)
        assertEquals(UnpairedPagePosition.RIGHT, position(UnpairedPagePosition.AUTOMATIC))
    }

    @Test
    fun `shifted first page is unpaired and explicit right ignores parity`() {
        val pages = List(3) { ReaderPage(it) }
        val pairs = pairReaderPages(pages, true, pages.first())
        assertSame(pages.first(), pairs.first().first)
        assertNull(pairs.first().second)
        assertTrue(pages.first().shiftedPage)
        assertEquals(UnpairedPagePosition.RIGHT, position(UnpairedPagePosition.RIGHT, rtl = false, inverted = true))
        assertEquals(pages[1] to pages[2], pairs.last())
    }

    @Test
    fun `wide page isolates preceding portrait without discarding or renumbering pages`() {
        val pages = List(5) { ReaderPage(it) }
        pages[1].fullPage = true
        val pairs = pairReaderPages(pages, false, null)
        assertEquals(listOf(0 to null, 1 to null, 2 to 3, 4 to null), pairs.map { it.first.index to it.second?.index })
        assertTrue(pages[0].isolatedPage)
        assertTrue(pages[1].fullPage)
        assertEquals(UnpairedPagePosition.RIGHT, position(UnpairedPagePosition.AUTOMATIC))
    }

    @Test
    fun `shifting after wide page and toggling back recalculates original pairs`() {
        val pages = List(5) { ReaderPage(it) }
        pages[0].fullPage = true
        val shifted = pairReaderPages(pages, true, pages[3])
        assertEquals(listOf(0 to null, 1 to null, 2 to 3, 4 to null), shifted.map { it.first.index to it.second?.index })
        val restored = pairReaderPages(pages, false, null)
        assertEquals(listOf(0 to null, 1 to 2, 3 to 4), restored.map { it.first.index to it.second?.index })
        assertFalse(pages.any { it.shiftedPage })
    }

    @Test
    fun `off center hinge is converted from window into reader coordinates`() {
        val hinge = ReaderHinge(830, 0, 914, 2000).relativeTo(30, 100, 1800, 1600)!!
        assertEquals(ReaderHinge(800, 0, 884, 1600), hinge)
        assertEquals(PageViewport(0, 800), ReaderSpreadLayout.viewport(1800, UnpairedPagePosition.LEFT, hinge))
        assertEquals(PageViewport(884, 1800), ReaderSpreadLayout.viewport(1800, UnpairedPagePosition.RIGHT, hinge))
    }

    @Test
    fun `no hinge uses adjacent halves with no artificial gap`() {
        val left = ReaderSpreadLayout.viewport(1801, UnpairedPagePosition.LEFT, null)
        val right = ReaderSpreadLayout.viewport(1801, UnpairedPagePosition.RIGHT, null)
        assertEquals(left.right, right.left)
        assertEquals(1801, left.width + right.width)
    }

    @Test
    fun `zero width separating fold still defines display regions`() {
        val hinge = ReaderHinge(800, 0, 800, 1600).relativeTo(0, 0, 1800, 1600)!!
        assertEquals(PageViewport(800, 1800), ReaderSpreadLayout.viewport(1800, UnpairedPagePosition.RIGHT, hinge))
    }

    @Test
    fun `rotation resize folding and inset changes do not retain old hinge bounds`() {
        val bounds = ReaderHinge(800, 0, 884, 1800)
        assertEquals(800, bounds.relativeTo(0, 0, 1800, 1600)?.left)
        assertEquals(750, bounds.relativeTo(50, 100, 1700, 1500)?.left)
        assertNull(bounds.relativeTo(884, 0, 916, 1600)) // unspanned
        assertNull(ReaderHinge(0, 800, 1800, 884).relativeTo(0, 0, 1800, 1600)) // rotated hinge
        assertNull(bounds.relativeTo(0, 0, 0, 0)) // not laid out
        assertEquals(PageViewport(900, 1800), ReaderSpreadLayout.viewport(1800, UnpairedPagePosition.RIGHT, null))
    }

    @Test
    fun `spread touch coordinates skip gap without losing image pixels`() {
        val hinge = ReaderHinge(800, 0, 884, 1600)
        assertEquals(799f, ReaderSpreadLayout.imageX(799f, hinge))
        assertEquals(800f, ReaderSpreadLayout.imageX(884f, hinge))
        assertEquals(1716f, ReaderSpreadLayout.imageX(1800f, hinge))
        assertEquals(800f, ReaderSpreadLayout.imageX(850f, hinge))
    }

    private fun position(
        side: UnpairedPagePosition,
        rtl: Boolean = true,
        inverted: Boolean = false,
    ) = ReaderSpreadLayout.unpairedPosition(side, true, true, rtl, inverted)
}
// KMK <--
