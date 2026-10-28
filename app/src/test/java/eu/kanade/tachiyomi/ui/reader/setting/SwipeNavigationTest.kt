package eu.kanade.tachiyomi.ui.reader.setting

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

// KMK -->
@OptIn(ExperimentalCoroutinesApi::class)
class SwipeNavigationTest {
    private val store = mockk<PreferenceStore>()
    private val booleans = mutableMapOf<String, TestPreference<Boolean>>()
    private val integers = mutableMapOf<String, TestPreference<Int>>()
    private val preferences: ReaderPreferences
        get() = ReaderPreferences(store)

    init {
        every { store.getBoolean(any(), any()) } answers {
            booleans.getOrPut(firstArg()) { TestPreference(firstArg(), secondArg()) }
        }
        every { store.getInt(any(), any()) } answers {
            integers.getOrPut(firstArg()) { TestPreference(firstArg(), secondArg()) }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "false, 5, false, true",
        "false, 5, true, false",
        "false, 0, false, false",
        "false, 0, true, false",
        "true, 5, false, true",
        "true, 5, true, true",
        "true, 0, false, true",
        "true, 0, true, true",
    )
    fun `swipe is always available when all other primary navigation is off`(
        requested: Boolean,
        taps: Int,
        volume: Boolean,
        expected: Boolean,
    ) {
        assertEquals(expected, SwipeNavigationState(requested, taps, volume).enabled)
    }

    @Test
    fun `swipe defaults on and paged and webtoon preferences persist independently`() {
        assertTrue(preferences.swipeNavigation(false).get())
        assertTrue(preferences.swipeNavigation(true).get())
        preferences.swipeNavigation(false).set(false)
        assertFalse(preferences.swipeNavigation(false).get())
        assertTrue(preferences.swipeNavigation(true).get())
    }

    @Test
    fun `turning off swipe while tapping is enabled leaves it off`() = runTest {
        val prefs = preferences
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            prefs.swipeNavigationChanges(false).collect { values.add(it) }
        }
        runCurrent()
        prefs.swipeNavigation(false).set(false)
        runCurrent()
        assertEquals(listOf(true, false), values)
        assertFalse(prefs.swipeNavigation(false).get())
    }

    @Test
    fun `disabling the last tap control restores both swipe and its displayed preference`() = runTest {
        val prefs = preferences
        prefs.swipeNavigation(false).set(false)
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            prefs.swipeNavigationChanges(false).collect { values.add(it) }
        }
        runCurrent()
        prefs.navigationModePager().set(5)
        runCurrent()
        assertEquals(listOf(false, true), values)
        assertTrue(prefs.swipeNavigation(false).get())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `volume keys alone allow swipe off but turning them off restores swipe`(webtoon: Boolean) = runTest {
        val prefs = preferences
        prefs.readWithVolumeKeys().set(true)
        (if (webtoon) prefs.navigationModeWebtoon() else prefs.navigationModePager()).set(5)
        prefs.swipeNavigation(webtoon).set(false)
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            prefs.swipeNavigationChanges(webtoon).collect { values.add(it) }
        }
        runCurrent()
        assertFalse(prefs.swipeNavigation(webtoon).get())
        prefs.readWithVolumeKeys().set(false)
        runCurrent()
        assertEquals(listOf(false, true), values)
        assertTrue(prefs.swipeNavigation(webtoon).get())
    }

    @Test
    fun `restored preferences with every control off are safe before the first flow emission`() = runTest {
        val prefs = preferences
        prefs.navigationModePager().set(5)
        prefs.swipeNavigation(false).set(false)
        assertTrue(prefs.swipeNavigationState(false).enabled)
        assertTrue(prefs.swipeNavigationChanges(false).first())
        assertTrue(prefs.swipeNavigation(false).get())
    }

    @Test
    fun `a fallback in one reader does not overwrite the other readers choice`() = runTest {
        val prefs = preferences
        prefs.swipeNavigation(false).set(false)
        prefs.swipeNavigation(true).set(false)
        prefs.navigationModeWebtoon().set(5)
        assertFalse(prefs.swipeNavigationChanges(false).first())
        assertTrue(prefs.swipeNavigationChanges(true).first())
        assertFalse(prefs.swipeNavigation(false).get())
        assertTrue(prefs.swipeNavigation(true).get())
    }

    private class TestPreference<T>(private val name: String, private val default: T) : Preference<T> {
        private val value = MutableStateFlow(default)
        override fun key() = name
        override fun get() = value.value
        override fun set(value: T) {
            this.value.value = value
        }
        override fun isSet() = get() != default
        override fun delete() = set(default)
        override fun defaultValue() = default
        override fun changes() = value
        override fun stateIn(scope: CoroutineScope) = value
    }
}
// KMK <--
