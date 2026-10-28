package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

// KMK -->
class ReaderChapterTailDetailsTest {
    @Test
    fun `right hand final page shows finished and next chapter in the empty left display`() {
        val current = chapter(1, 24)
        val next = chapter(2, 23)
        val details = ReaderChapterTailDetails.create(
            current.pages!!.last(),
            null,
            ViewerChapters(current, null, next),
            UnpairedPagePosition.RIGHT,
            true,
        )!!
        assertEquals(UnpairedPagePosition.LEFT, details.position)
        assertSame(current, details.transition.from)
        assertSame(next, details.transition.to)
        assertEquals(24, current.pages!!.last().number)
    }

    @Test
    fun `left hand final page shows chapter details only in the empty right display`() {
        val current = chapter(1, 24)
        val details = ReaderChapterTailDetails.create(
            current.pages!!.last(),
            null,
            ViewerChapters(current, null, chapter(2, 23)),
            UnpairedPagePosition.LEFT,
            true,
        )!!
        assertEquals(UnpairedPagePosition.RIGHT, details.position)
    }

    @Test
    fun `terminal chapter keeps the final page and supplies the no next chapter message`() {
        val current = chapter(1, 24)
        val details = ReaderChapterTailDetails.create(
            current.pages!!.last(),
            null,
            ViewerChapters(current, null, null),
            UnpairedPagePosition.RIGHT,
            true,
        )!!
        assertEquals(UnpairedPagePosition.LEFT, details.position)
        assertSame(current, details.transition.from)
        assertNull(details.transition.to)
    }

    @Test
    fun `activation preserves the same tail boundary beside a previously active chapter`() {
        val current = chapter(1, 24)
        val next = chapter(2, 23)
        val lastPage = current.pages!!.last()
        val before = ReaderChapterTailDetails.create(
            lastPage,
            null,
            ViewerChapters(current, null, next),
            UnpairedPagePosition.RIGHT,
            true,
        )!!
        val after = ReaderChapterTailDetails.create(
            lastPage,
            null,
            ViewerChapters(next, current, chapter(3, 8)),
            UnpairedPagePosition.RIGHT,
            true,
        )!!
        assertTrue(before.transition.sameBoundary(after.transition))
        assertEquals(before.position, after.position)
    }

    @Test
    fun `paired interior and continuous pages never get an overlapping chapter panel`() {
        val current = chapter(1, 24)
        val pages = current.pages!!
        val chapters = ViewerChapters(current, null, chapter(2, 23))
        assertNull(ReaderChapterTailDetails.create(pages[2], null, chapters, UnpairedPagePosition.RIGHT, true))
        assertNull(ReaderChapterTailDetails.create(pages.last(), pages[2], chapters, UnpairedPagePosition.RIGHT, true))
        for (position in listOf(UnpairedPagePosition.CENTER, UnpairedPagePosition.AUTOMATIC)) {
            assertNull(ReaderChapterTailDetails.create(pages.last(), null, chapters, position, true))
        }
        assertNull(ReaderChapterTailDetails.create(pages.last(), null, chapters, UnpairedPagePosition.RIGHT, false))
    }

    @Test
    fun `unknown future and loading chapters do not guess a next chapter`() {
        val current = chapter(1, 24)
        val next = chapter(2, 23)
        val chapters = ViewerChapters(current, null, next)
        assertNull(ReaderChapterTailDetails.create(next.pages!!.last(), null, chapters, UnpairedPagePosition.RIGHT, true))
        val lastPage = current.pages!!.last()
        current.state = ReaderChapter.State.Loading
        assertNull(ReaderChapterTailDetails.create(lastPage, null, chapters, UnpairedPagePosition.RIGHT, true))
        assertNull(ReaderChapterTailDetails.create(lastPage, null, null, UnpairedPagePosition.RIGHT, true))
    }

    @Test
    fun `one page chapter details leave the opening identity and saved progress intact`() {
        val current = chapter(1, 1).apply { requestedPage = 0 }
        val opening = current.pages!!.single()
        val details = ReaderChapterTailDetails.create(
            opening,
            null,
            ViewerChapters(current, null, chapter(2, 2)),
            UnpairedPagePosition.LEFT,
            true,
        )!!
        assertEquals(UnpairedPagePosition.RIGHT, details.position)
        assertEquals(0, current.requestedPage)
        assertSame(opening, current.pages!!.single())
        assertEquals(1, opening.number)
    }

    private fun chapter(id: Long, count: Int): ReaderChapter {
        val chapter = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1, name = "Chapter $id", url = "/$id"))
        chapter.state = ReaderChapter.State.Loaded(List(count) { ReaderPage(it).apply { this.chapter = chapter } })
        return chapter
    }
}
// KMK <--
