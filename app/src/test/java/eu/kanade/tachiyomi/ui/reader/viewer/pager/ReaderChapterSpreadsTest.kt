package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

// KMK -->
class ReaderChapterSpreadsTest {
    @Test
    fun `continuing from a prior chapter shows the opening exactly once then pages two and three`() {
        val previous = chapter(1, 3)
        val next = chapter(2, 5)
        val transition = ChapterTransition.Next(previous, next)
        val items = previous.pages!! + transition + next.pages!!
        val spreads = join(items)
        val position = spreads.indexOfFirst { it.first === transition }
        assertEquals(transition to next.pages!![0], spreads[position])
        assertEquals(next.pages!![1] to next.pages!![2], spreads[position + 1])
        assertEquals(next.pages!![3] to next.pages!![4], spreads[position + 2])
        assertSame(next.pages!![0], spreads[position].readingPage)
        assertFalse(spreads[position].hasExtraPage)
        assertTrue(spreads[position + 1].hasExtraPage)
        assertEveryPageOnce(items, spreads)
    }

    @Test
    fun `opening directly keeps the opening separate from pages two and three`() {
        val current = chapter(1, 5)
        val spreads = join(current.pages!!)
        assertEquals(current.pages!![0] to null, spreads.first())
        assertEquals(current.pages!![1] to current.pages!![2], spreads[1])
        assertEquals(current.pages!![3] to current.pages!![4], spreads[2])
        assertEveryPageOnce(current.pages!!, spreads)
    }

    @Test
    fun `previous current owns the same opening as finished next after the active chapter changes`() {
        val previous = chapter(1, 3)
        val current = chapter(2, 3)
        val before = join(previous.pages!! + ChapterTransition.Next(previous, current) + current.pages!!)
        val openingSpread = before.single { it.second === current.pages!![0] }
        val after = join(previous.pages!! + ChapterTransition.Prev(current, previous) + current.pages!!)
        assertEquals(openingSpread, after.single { it.second === current.pages!![0] })
        assertEquals(1, after.count { it.first === current.pages!![0] || it.second === current.pages!![0] })
    }

    @Test
    fun `loading a destination attaches its opening once without skipping page two`() {
        val current = chapter(1, 3)
        val next = chapter(2, 3)
        val loaded = next.state
        val transition = ChapterTransition.Next(current, next)
        next.state = ReaderChapter.State.Loading
        val before = join(current.pages!! + transition)
        assertNull(before.last().readingPage)
        next.state = loaded
        val after = join(current.pages!! + transition + next.pages!!)
        assertSame(next.pages!![0], after[before.lastIndex].readingPage)
        assertEquals(next.pages!![1] to next.pages!![2], after.last())
        assertEveryPageOnce(current.pages!! + next.pages!!, after)
    }

    @Test
    fun `one page chapters remain selectable as real pages without counting chapter text as a page`() {
        val previous = chapter(1, 1)
        val current = chapter(2, 1)
        val transition = ChapterTransition.Next(previous, current)
        val end = ChapterTransition.Next(current, null)
        val spreads = join(previous.pages!! + transition + current.pages!! + end)
        assertEquals(3, spreads.size)
        assertSame(current.pages!!.single(), spreads[1].readingPage)
        assertFalse(spreads[1].hasExtraPage)
        assertEquals(end to null, spreads.last())
        assertNull(spreads.last().readingPage)
    }

    @Test
    fun `first chapter and even page counts do not lose an opening or a tail`() {
        for (count in 1..6) {
            val current = chapter(1, count)
            val start = ChapterTransition.Prev(current, null)
            val end = ChapterTransition.Next(current, null)
            val spreads = join(listOf(start) + current.pages!! + end)
            assertEquals(start to current.pages!![0], spreads.first())
            assertEquals(end to null, spreads.last())
            assertEveryPageOnce(current.pages!!, spreads)
        }
    }

    @Test
    fun `wide opening and wide interior pages preserve image identity and flags`() {
        val current = chapter(1, 5)
        val pages = current.pages!!
        pages[0].fullPage = true
        pages[2].fullPage = true
        val start = ChapterTransition.Prev(current, null)
        val spreads = join(listOf(start) + pages)
        assertEquals(start to pages[0], spreads.first())
        assertEquals(pages[1] to null, spreads[1])
        assertEquals(pages[2] to null, spreads[2])
        assertEquals(pages[3] to pages[4], spreads[3])
        assertTrue(pages[0].fullPage)
        assertTrue(pages[2].fullPage)
        assertEveryPageOnce(pages, spreads)
    }

    @Test
    fun `initial shift does not create a second isolated page after the opening`() {
        val current = chapter(1, 3)
        val expected = join(current.pages!!)
        assertEquals(expected, joinReaderChapterSpreads(current.pages!!, true, true, null))
        assertEquals(expected, joinReaderChapterSpreads(current.pages!!, true, true, current.pages!![0]))
    }

    @Test
    fun `shifting farther into the chapter keeps the opening and every remaining page`() {
        val current = chapter(1, 6)
        val pages = current.pages!!
        val spreads = joinReaderChapterSpreads(pages, true, true, pages[3])
        assertEquals(pages[0] to null, spreads.first())
        assertEquals(pages[1] to null, spreads[1])
        assertEquals(pages[2] to pages[3], spreads[2])
        assertEveryPageOnce(pages, spreads)
    }

    @Test
    fun `rtl pager reversal still navigates from transition to pages two and three`() {
        val current = chapter(1, 3)
        val next = chapter(2, 3)
        val transition = ChapterTransition.Next(current, next)
        val items = current.pages!! + transition + next.pages!!
        val reversed = join(items).asReversed()
        val position = reversed.indexOfFirst { it.first === transition }
        assertSame(next.pages!![0], reversed[position].readingPage)
        assertEquals(next.pages!![1] to next.pages!![2], reversed[position - 1])
        assertEveryPageOnce(items, reversed)
    }

    @Test
    fun `chapter adjacency without a transition still isolates both openings`() {
        val first = chapter(1, 3)
        val second = chapter(2, 3)
        val spreads = join(first.pages!! + second.pages!!)
        assertEquals(first.pages!![0] to null, spreads[0])
        assertEquals(second.pages!![0] to null, spreads[2])
        assertEquals(second.pages!![1] to second.pages!![2], spreads[3])
    }

    @Test
    fun `vertical or split layouts keep the existing pairing and separate transitions`() {
        val current = chapter(1, 3)
        val transition = ChapterTransition.Prev(current, null)
        val spreads = joinReaderChapterSpreads(listOf(transition) + current.pages!!, false, false, null)
        assertEquals(transition to null, spreads.first())
        assertEquals(current.pages!![0] to current.pages!![1], spreads[1])
        assertEquals(current.pages!![2] to null, spreads[2])
        assertEveryPageOnce(current.pages!!, spreads)
    }

    @Test
    fun `opening placement is physical left for both directions inversion and every unpaired preference`() {
        for (rtl in listOf(false, true)) {
            for (inverted in listOf(false, true)) {
                for (position in UnpairedPagePosition.entries) {
                    assertEquals(
                        UnpairedPagePosition.LEFT,
                        ReaderSpreadLayout.unpairedPosition(position, true, true, rtl, inverted, openingPage = true),
                    )
                }
            }
        }
        assertEquals(
            UnpairedPagePosition.CENTER,
            ReaderSpreadLayout.unpairedPosition(UnpairedPagePosition.RIGHT, false, true, true, false, true),
        )
        assertEquals(
            UnpairedPagePosition.CENTER,
            ReaderSpreadLayout.unpairedPosition(UnpairedPagePosition.RIGHT, true, false, true, false, true),
        )
    }

    @Test
    fun `joining does not rewrite page numbers or saved progress and slider lookup finds the opening`() {
        val current = chapter(1, 5)
        current.requestedPage = 3
        val pages = current.pages!!
        val start = ChapterTransition.Prev(current, null)
        val spreads = join(listOf(start) + pages)
        assertEquals(3, current.requestedPage)
        assertEquals(listOf(0, 1, 2, 3, 4), pages.map { it.index })
        assertEquals(0, spreads.indexOfFirst { it.first === pages[0] || it.second === pages[0] })
        assertSame(pages[0], spreads.first().readingPage)
        assertFalse(spreads.first().hasExtraPage)
    }

    @Test
    fun `start and end transitions of a single chapter do not reuse each others text holder`() {
        val current = chapter(1, 1)
        val start = ChapterTransition.Prev(current, null) to null
        val end = ChapterTransition.Next(current, null) to null
        assertFalse(start.sameReaderSpread(end))
        assertTrue(start.sameReaderSpread(ChapterTransition.Prev(current, null) to null))
        val page = current.pages!!.first()
        assertFalse((start.first to page).sameReaderSpread(start))
    }

    @Test
    fun `selection restoration distinguishes start from end and locates openings after chapter activation`() {
        val previous = chapter(1, 1)
        val current = chapter(2, 3)
        val start = ChapterTransition.Prev(current, null)
        val end = ChapterTransition.Next(current, null)
        val items = join(listOf(start) + current.pages!! + end)
        assertEquals(0, items.readerSpreadPosition(start))
        assertEquals(items.lastIndex, items.readerSpreadPosition(end))
        assertEquals(0, items.readerSpreadPosition(current.pages!![0]))
        val single = listOf(start to null, current.pages!![0] to null, end to null)
        assertEquals(0, single.readerSpreadPosition(start))
        assertEquals(2, single.readerSpreadPosition(end))
        val previousTransition = ChapterTransition.Next(previous, current)
        val newStart = ChapterTransition.Prev(current, previous)
        val activated = join(previous.pages!! + newStart + current.pages!!)
        assertSame(current.pages!![0], activated[activated.readerSpreadPosition(previousTransition)].readingPage)
        assertSame(current.pages!![0], activated[activated.readerSpreadPosition(current.pages!![0])].readingPage)
    }

    private fun join(items: List<ReaderItem>) = joinReaderChapterSpreads(items, true, false, null)

    private fun assertEveryPageOnce(items: List<ReaderItem>, spreads: List<Pair<ReaderItem, ReaderItem?>>) {
        val expected = items.filterIsInstance<ReaderPage>()
        val actual = spreads.flatMap { listOfNotNull(it.first as? ReaderPage, it.second as? ReaderPage) }
        assertEquals(expected.toSet(), actual.toSet())
        assertEquals(expected.size, actual.size)
        assertEquals(expected.size, actual.distinct().size)
    }

    private fun chapter(id: Long, count: Int): ReaderChapter {
        val chapter = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1, name = "Chapter $id", url = "/$id"))
        chapter.state = ReaderChapter.State.Loaded(List(count) { ReaderPage(it).apply { this.chapter = chapter } })
        return chapter
    }
}
// KMK <--
