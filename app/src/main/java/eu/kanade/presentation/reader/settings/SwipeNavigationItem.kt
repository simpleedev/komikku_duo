package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.swipeNavigationChanges
import kotlinx.coroutines.flow.collect
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.i18n.stringResource

// KMK -->
@Composable
internal fun ObserveSwipeNavigation(preferences: ReaderPreferences, webtoon: Boolean) {
    LaunchedEffect(preferences, webtoon) {
        preferences.swipeNavigationChanges(webtoon).collect()
    }
}

@Composable
internal fun SwipeNavigationItem(preferences: ReaderPreferences, webtoon: Boolean) {
    ObserveSwipeNavigation(preferences, webtoon)
    CheckboxItem(
        label = stringResource(KMR.strings.pref_swipe_navigation),
        pref = preferences.swipeNavigation(webtoon),
    )
    Text(
        text = stringResource(KMR.strings.pref_swipe_navigation_summary),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}
// KMK <--
