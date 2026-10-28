package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

// KMK -->
class ReaderPageNumbersTest {
    @Test
    fun `paired counters follow physical image order in both directions and inversion modes`() {
        val chapter = chapter(1, 9)
        val spread = chapter.pages!![1] to chapter.pages!![2]
        for ((rtl, inverted, expected) in listOf(
            Triple(false, false, 2 to 3),
            Triple(false, true, 3 to 2),
            Triple(true, false, 3 to 2),
            Triple(true, true, 2 to 3),
        )) {
            assertEquals(
                ReaderPageNumbers(ReaderPageNumber(expected.first, 9), ReaderPageNumber(expected.second, 9)),
                spread.pageNumbers(rtl, inverted, UnpairedPagePosition.CENTER),
            )
        }
    }

    @Test
    fun `counters use actual page identities instead of assuming consecutive indices`() {
        val chapter = chapter(1, 9)
        assertEquals(
            ReaderPageNumbers(ReaderPageNumber(2, 9), ReaderPageNumber(7, 9)),
            (chapter.pages!![1] to chapter.pages!![6]).pageNumbers(false, false, UnpairedPagePosition.CENTER),
        )
    }

    @Test
    fun `chapter openings have only a left counter with the opening chapters total`() {
        val previous = chapter(1, 12)
        val current = chapter(2, 5)
        val transitions = listOf(
            ChapterTransition.Next(previous, current),
            ChapterTransition.Prev(current, previous),
        )
        for (transition in transitions) {
            for (rtl in listOf(false, true)) {
                for (inverted in listOf(false, true)) {
                    assertEquals(
                        ReaderPageNumbers(left = ReaderPageNumber(1, 5)),
                        (transition to current.pages!![0]).pageNumbers(rtl, inverted, UnpairedPagePosition.RIGHT),
                    )
                }
            }
        }
    }

    @Test
    fun `blank loading and terminal transitions have no stale page counters`() {
        val current = chapter(1, 5)
        val next = chapter(2, 3).apply { state = ReaderChapter.State.Loading }
        val transitions = listOf(
            ChapterTransition.Next(current, next),
            ChapterTransition.Next(current, null),
            ChapterTransition.Prev(current, null),
        )
        for (transition in transitions) {
            assertEquals(
                ReaderPageNumbers(),
                (transition to null).pageNumbers(true, false, UnpairedPagePosition.RIGHT),
            )
        }
    }

    @Test
    fun `isolated pages leave the opposite display counter empty`() {
        val page = chapter(1, 4).pages!!.last()
        val spread: Pair<ReaderItem, ReaderItem?> = page to null
        assertEquals(
            ReaderPageNumbers(left = ReaderPageNumber(4, 4)),
            spread.pageNumbers(true, false, UnpairedPagePosition.LEFT),
        )
        assertEquals(
            ReaderPageNumbers(right = ReaderPageNumber(4, 4)),
            spread.pageNumbers(false, false, UnpairedPagePosition.RIGHT),
        )
        assertEquals(
            ReaderPageNumbers(right = ReaderPageNumber(4, 4)),
            spread.pageNumbers(true, false, UnpairedPagePosition.AUTOMATIC),
        )
        assertEquals(
            ReaderPageNumbers(left = ReaderPageNumber(4, 4)),
            spread.pageNumbers(true, true, UnpairedPagePosition.AUTOMATIC),
        )
    }

    @Test
    fun `continuous full width image repeats its own number without inventing an extra page`() {
        val page = chapter(1, 4).pages!![1].apply { fullPage = true }
        assertEquals(
            ReaderPageNumbers(ReaderPageNumber(2, 4), ReaderPageNumber(2, 4)),
            (page to null).pageNumbers(true, false, UnpairedPagePosition.CENTER),
        )
    }

    @Test
    fun `unavailable pages do not produce invalid totals`() {
        val chapter = chapter(1, 0)
        val page = ReaderPage(0).apply { this.chapter = chapter }
        assertEquals(
            ReaderPageNumbers(),
            (page to null).pageNumbers(false, false, UnpairedPagePosition.LEFT),
        )
        chapter.state = ReaderChapter.State.Loading
        assertEquals(
            ReaderPageNumbers(),
            (page to null).pageNumbers(false, false, UnpairedPagePosition.LEFT),
        )
    }

    private fun chapter(id: Long, count: Int): ReaderChapter {
        val chapter = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1, name = "Chapter $id", url = "/$id"))
        chapter.state = ReaderChapter.State.Loaded(List(count) { ReaderPage(it).apply { this.chapter = chapter } })
        return chapter
    }
}
// KMK <--
