package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

// KMK -->
class ReaderTransitionSpreadTest {
    @Test
    fun `finished next stays right while the next chapter opening is on the left`() {
        val current = chapter(1)
        val next = chapter(2)
        val spread = spread(ChapterTransition.Next(current, next))
        assertEquals(PageViewport(900, 1800), spread.details)
        assertEquals(PageViewport(0, 900), spread.leftPage)
        assertSame(next.pages!!.first(), spread.openingPage)
        assertFalse(spread.noMoreChapters)
    }

    @Test
    fun `previous current shows the current opening rather than a previous chapter page`() {
        val previous = chapter(1)
        val current = chapter(2)
        val spread = spread(ChapterTransition.Prev(current, previous))
        assertSame(current.pages!!.first(), spread.openingPage)
        assertEquals(PageViewport(900, 1800), spread.details)
        assertFalse(spread.noMoreChapters)
    }

    @Test
    fun `the first manga chapter can be shown even without a previous chapter`() {
        val current = chapter(1)
        val spread = spread(ChapterTransition.Prev(current, null))
        assertSame(current.pages!!.first(), spread.openingPage)
        assertFalse(spread.noMoreChapters)
    }

    @Test
    fun `no more chapters is on the left with the finished chapter on the right`() {
        val spread = spread(ChapterTransition.Next(chapter(1), null))
        assertTrue(spread.noMoreChapters)
        assertNull(spread.openingPage)
        assertEquals(PageViewport(0, 900), spread.leftPage)
        assertEquals(PageViewport(900, 1800), spread.details)
    }

    @Test
    fun `loading and failed destination lists leave the left page blank`() {
        val current = chapter(1)
        val next = chapter(2)
        for (state in listOf(ReaderChapter.State.Wait, ReaderChapter.State.Loading, ReaderChapter.State.Error(Exception()))) {
            next.state = state
            val spread = spread(ChapterTransition.Next(current, next))
            assertNull(spread.openingPage)
            assertFalse(spread.noMoreChapters)
            assertEquals(PageViewport(900, 1800), spread.details)
        }
    }

    @Test
    fun `a destination that finishes loading becomes available as an opening`() {
        val current = chapter(1)
        val next = chapter(2)
        val loaded = next.state
        next.state = ReaderChapter.State.Loading
        val transition = ChapterTransition.Next(current, next)
        assertNull(spread(transition).openingPage)
        next.state = loaded
        assertSame(next.pages!!.first(), spread(transition).openingPage)
    }

    @Test
    fun `an empty destination list uses the blank fallback rather than claiming the manga ended`() {
        val next = chapter(2, 0)
        val spread = spread(ChapterTransition.Next(chapter(1), next))
        assertNull(spread.openingPage)
        assertFalse(spread.noMoreChapters)
    }

    @Test
    fun `single page layouts retain the original full width transition`() {
        val spread = ReaderTransitionSpread.create(ChapterTransition.Next(chapter(1), chapter(2)), false, 1800, null)
        assertEquals(PageViewport(0, 1800), spread.details)
        assertNull(spread.leftPage)
        assertNull(spread.openingPage)
        assertFalse(spread.noMoreChapters)
    }

    @Test
    fun `single page end message is left to the existing inline transition`() {
        val spread = ReaderTransitionSpread.create(ChapterTransition.Next(chapter(1), null), false, 1800, null)
        assertFalse(spread.noMoreChapters)
        assertNull(spread.leftPage)
    }

    @Test
    fun `transition panes follow asymmetric hinge bounds and clear them after unspanning`() {
        val transition = ChapterTransition.Next(chapter(1), chapter(2))
        val hinged = ReaderTransitionSpread.create(transition, true, 1800, ReaderHinge(800, 0, 884, 1600))
        assertEquals(PageViewport(0, 800), hinged.leftPage)
        assertEquals(PageViewport(884, 1800), hinged.details)
        val unspanned = ReaderTransitionSpread.create(transition, true, 1200, null)
        assertEquals(PageViewport(0, 600), unspanned.leftPage)
        assertEquals(PageViewport(600, 1200), unspanned.details)
    }

    @Test
    fun `a full width opening leaves the chapter's wide page pairing unchanged`() {
        val next = chapter(2)
        val first = next.pages!!.first()
        first.fullPage = true
        val pairs = pairReaderPages(next.pages!!, false, null)
        assertSame(first, spread(ChapterTransition.Next(chapter(1), next)).openingPage)
        assertTrue(first.fullPage)
        assertEquals(pairs, pairReaderPages(next.pages!!, false, null))
    }

    @Test
    fun `opening selection preserves identities flags pairing and saved positions`() {
        val current = chapter(1)
        val next = chapter(2)
        val first = next.pages!!.first()
        first.shiftedPage = true
        first.isolatedPage = true
        next.requestedPage = 2
        repeat(3) { assertSame(first, spread(ChapterTransition.Next(current, next)).openingPage) }
        assertEquals(listOf(0, 1, 2), next.pages!!.map { it.index })
        assertEquals(2, next.requestedPage)
        assertTrue(first.shiftedPage)
        assertTrue(first.isolatedPage)
        assertFalse(first.fullPage)
    }

    private fun spread(transition: ChapterTransition) = ReaderTransitionSpread.create(transition, true, 1800, null)

    private fun chapter(id: Long, pageCount: Int = 3): ReaderChapter {
        val chapter = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1, name = "Chapter $id", url = "/$id"))
        chapter.state = ReaderChapter.State.Loaded(List(pageCount) { ReaderPage(it).apply { this.chapter = chapter } })
        return chapter
    }
}
// KMK <--
