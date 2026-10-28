package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

// KMK -->
/** Regions and opening page for a chapter transition spread. */
internal data class ReaderTransitionSpread(
    val details: PageViewport,
    val leftPage: PageViewport?,
    val openingPage: ReaderPage?,
    val noMoreChapters: Boolean,
) {
    companion object {
        fun openingPage(transition: ChapterTransition): ReaderPage? = when (transition) {
            is ChapterTransition.Next -> transition.to
            is ChapterTransition.Prev -> transition.from
        }?.pages?.firstOrNull()

        fun create(
            transition: ChapterTransition,
            sideBySide: Boolean,
            width: Int,
            hinge: ReaderHinge?,
        ): ReaderTransitionSpread {
            if (!sideBySide) return ReaderTransitionSpread(PageViewport(0, width), null, null, false)
            return ReaderTransitionSpread(
                details = ReaderSpreadLayout.viewport(width, UnpairedPagePosition.RIGHT, hinge),
                leftPage = ReaderSpreadLayout.viewport(width, UnpairedPagePosition.LEFT, hinge),
                openingPage = openingPage(transition),
                noMoreChapters = transition is ChapterTransition.Next && transition.to == null,
            )
        }
    }
}
// KMK <--
