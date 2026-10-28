package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

// KMK -->
/** Each opening belongs to exactly one spread, including when it shares a transition. */
internal fun joinReaderChapterSpreads(
    items: List<ReaderItem>,
    sideBySide: Boolean,
    shift: Boolean,
    pageToShift: ReaderPage?,
): MutableList<Pair<ReaderItem, ReaderItem?>> {
    val spreads = mutableListOf<Pair<ReaderItem, ReaderItem?>>()
    var index = 0
    while (index < items.size) {
        val item = items[index]
        if (item !is ReaderPage) {
            spreads.add(item to null)
            index++
            continue
        }
        val pages = items.drop(index).takeWhile { it is ReaderPage && it.chapter == item.chapter }
            .filterIsInstance<ReaderPage>()
        index += pages.size
        val opening = pages.first().takeIf { sideBySide && it.index == 0 && it !is InsertPage }
        if (opening != null) {
            opening.shiftedPage = false
            opening.isolatedPage = false
            val transition = spreads.lastOrNull()?.first as? ChapterTransition
            if (transition != null && ReaderTransitionSpread.openingPage(transition) === opening) {
                spreads[spreads.lastIndex] = transition to opening
            } else {
                spreads.add(opening to null)
            }
        }
        val remaining = if (opening != null) pages.drop(1) else pages
        // Isolating the opening already supplies the initial shift. Keep explicit shifts farther in.
        val shiftRemaining = shift && (opening == null || pageToShift in remaining)
        spreads.addAll(pairReaderPages(remaining, shiftRemaining, pageToShift))
    }
    return spreads
}

/** Transition text is not a second manga page for progress, page counts, or completion. */
internal val Pair<ReaderItem, ReaderItem?>.readingPage: ReaderPage?
    get() = first as? ReaderPage ?: second as? ReaderPage

internal val Pair<ReaderItem, ReaderItem?>.hasExtraPage: Boolean
    get() = first is ReaderPage && second is ReaderPage

/** A boundary keeps its rendered view when Next(A, B) becomes Prev(B, A) on chapter activation. */
internal fun ChapterTransition.sameBoundary(other: ChapterTransition): Boolean {
    val previous = if (this is ChapterTransition.Next) from else to
    val next = if (this is ChapterTransition.Next) to else from
    val otherPrevious = if (other is ChapterTransition.Next) other.from else other.to
    val otherNext = if (other is ChapterTransition.Next) other.to else other.from
    return previous === otherPrevious && next === otherNext
}

/** Start/end boundaries remain distinct, while an unchanged opening survives chapter activation. */
internal fun Pair<ReaderItem, ReaderItem?>.sameReaderSpread(other: Pair<ReaderItem, ReaderItem?>): Boolean {
    val transition = first as? ChapterTransition ?: return this == other
    val otherTransition = other.first as? ChapterTransition ?: return false
    return transition.sameBoundary(otherTransition) && second === other.second
}

/** Keep page aliases and chapter boundaries when restoring a selection after chapter loading. */
internal fun List<Pair<ReaderItem, ReaderItem?>>.readerSpreadPosition(item: ReaderItem?): Int {
    item ?: return -1
    if (item !is ChapterTransition) return indexOfFirst { it.first === item || it.second === item }
    val transitionPosition = indexOfFirst { (it.first to null).sameReaderSpread(item to null) }
    if (transitionPosition >= 0) return transitionPosition
    val pages = mapNotNull { it.readingPage }.filter { it.chapter == item.to }
    val destination = when (item) {
        is ChapterTransition.Next -> pages.minByOrNull { it.index }
        is ChapterTransition.Prev -> pages.maxByOrNull { it.index }
    } ?: return -1
    return indexOfFirst { it.first === destination || it.second === destination }
}
// KMK <--
