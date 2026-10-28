package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

// KMK -->
/** Existing per-chapter pairing, kept independent of Android views for regression tests. */
internal fun pairReaderPages(
    pages: List<ReaderPage>,
    shift: Boolean,
    pageToShift: ReaderPage?,
): List<Pair<ReaderPage, ReaderPage?>> {
    val items = pages.toMutableList<ReaderPage?>()
    items.forEach { it?.shiftedPage = false }
    if (shift) {
        val index = items.indexOf(pageToShift)
        val fullPageBeforeIndex = if (index > -1) {
            items.take(index).indexOfLast { it?.fullPage == true }
        } else {
            -1
        }.coerceAtLeast(0)
        (fullPageBeforeIndex until items.size).firstOrNull { items[it]?.fullPage == false }
            ?.let { items[it]?.shiftedPage = true }
    }

    var itemIndex = 0
    while (itemIndex < items.size) {
        val currentItem = items[itemIndex]
        currentItem?.isolatedPage = false
        if (currentItem?.fullPage == true || currentItem?.shiftedPage == true) {
            items.add(itemIndex + 1, null)
            if (currentItem.fullPage && itemIndex > 0 && items[itemIndex - 1] != null && (itemIndex - 1) % 2 == 0) {
                items[itemIndex - 1]?.isolatedPage = true
                items.add(itemIndex, null)
                itemIndex++
            }
            itemIndex++
        }
        itemIndex++
    }
    return items.chunked(2).map { it.first()!! to it.getOrNull(1) }
}
// KMK <--
