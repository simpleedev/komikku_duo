package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

// KMK -->
internal data class ReaderPageNumber(val page: Int, val total: Int)

internal data class ReaderPageNumbers(val left: ReaderPageNumber? = null, val right: ReaderPageNumber? = null)

/** Match the real images, including nonconsecutive pairs and chapter openings, without changing progress. */
internal fun Pair<ReaderItem, ReaderItem?>.pageNumbers(
    isRtl: Boolean,
    inverted: Boolean,
    unpairedPosition: UnpairedPagePosition,
): ReaderPageNumbers {
    val page = readingPage ?: return ReaderPageNumbers()
    fun ReaderPage.numberOnDisplay(): ReaderPageNumber? {
        val total = chapter.pages?.size ?: return null
        return ReaderPageNumber(number, total).takeIf { number in 1..total }
    }
    val firstNumber = page.numberOnDisplay()
    if (first is ChapterTransition) return ReaderPageNumbers(left = firstNumber)
    if (hasExtraPage) {
        val secondNumber = (second as ReaderPage).numberOnDisplay()
        return if (ReaderSpreadLayout.firstPageOnLeft(isRtl, inverted)) {
            ReaderPageNumbers(firstNumber, secondNumber)
        } else {
            ReaderPageNumbers(secondNumber, firstNumber)
        }
    }
    return when (unpairedPosition) {
        UnpairedPagePosition.LEFT -> ReaderPageNumbers(left = firstNumber)
        UnpairedPagePosition.RIGHT -> ReaderPageNumbers(right = firstNumber)
        UnpairedPagePosition.AUTOMATIC -> if (ReaderSpreadLayout.firstPageOnLeft(isRtl, inverted)) {
            ReaderPageNumbers(left = firstNumber)
        } else {
            ReaderPageNumbers(right = firstNumber)
        }
        // A continuous image occupies both display regions, which show the same source page number.
        UnpairedPagePosition.CENTER -> ReaderPageNumbers(firstNumber, firstNumber)
    }
}
// KMK <--
