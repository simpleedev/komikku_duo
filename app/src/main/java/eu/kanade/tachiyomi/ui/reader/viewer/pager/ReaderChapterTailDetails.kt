package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

// KMK -->
/** Fill the empty display beside a chapter's final page. */
internal data class ReaderChapterTailDetails(
    val transition: ChapterTransition.Next,
    val position: UnpairedPagePosition,
) {
    companion object {
        fun create(
            page: ReaderPage,
            extraPage: ReaderPage?,
            chapters: ViewerChapters?,
            pagePosition: UnpairedPagePosition,
            sideBySide: Boolean,
        ): ReaderChapterTailDetails? {
            if (!sideBySide || extraPage != null || chapters == null || page.index != page.chapter.pages?.lastIndex) {
                return null
            }
            val position = when (pagePosition) {
                UnpairedPagePosition.RIGHT -> UnpairedPagePosition.LEFT
                UnpairedPagePosition.LEFT -> UnpairedPagePosition.RIGHT
                else -> return null // Continuous images have no empty display to fill.
            }
            val next = when (page.chapter) {
                chapters.currChapter -> chapters.nextChapter
                chapters.prevChapter -> chapters.currChapter
                else -> return null // The chapter after a preloaded future chapter is not known yet.
            }
            return ReaderChapterTailDetails(ChapterTransition.Next(page.chapter, next), position)
        }
    }
}
// KMK <--
