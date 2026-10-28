package eu.kanade.tachiyomi.ui.reader.setting

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

// KMK -->
internal data class SwipeNavigationState(val requested: Boolean, val tapMode: Int, val volumeKeys: Boolean) {
    val requiresFallback: Boolean get() = !requested && tapMode == 5 && !volumeKeys
    val enabled: Boolean get() = requested || requiresFallback
}

internal fun ReaderPreferences.swipeNavigationState(webtoon: Boolean): SwipeNavigationState = SwipeNavigationState(
    swipeNavigation(webtoon).get(),
    (if (webtoon) navigationModeWebtoon() else navigationModePager()).get(),
    readWithVolumeKeys().get(),
)

/** Restore the toggle itself as well as the runtime behavior when the last other control is disabled. */
internal fun ReaderPreferences.swipeNavigationChanges(webtoon: Boolean): Flow<Boolean> {
    val swipe = swipeNavigation(webtoon)
    val taps = if (webtoon) navigationModeWebtoon() else navigationModePager()
    return combine(swipe.changes(), taps.changes(), readWithVolumeKeys().changes(), ::SwipeNavigationState)
        .onEach { if (it.requiresFallback) swipe.set(true) }
        .map { it.enabled }
        .distinctUntilChanged()
}
// KMK <--
