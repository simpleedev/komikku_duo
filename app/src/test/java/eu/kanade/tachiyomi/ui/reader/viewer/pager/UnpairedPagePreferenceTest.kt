package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.AndroidPreferenceStore

// KMK -->
class UnpairedPagePreferenceTest {
    private val values = mutableMapOf<String, String?>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val context = mockk<Context>()

    init {
        every { preferences.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            values[firstArg()] = secondArg()
            editor
        }
    }

    private fun readerPreferences() = ReaderPreferences(AndroidPreferenceStore(context, preferences))

    @Test
    fun `new preference defaults to automatic`() {
        assertEquals(UnpairedPagePosition.AUTOMATIC, readerPreferences().unpairedPagePosition().get())
    }

    @Test
    fun `saved position survives preference wrapper recreation`() {
        readerPreferences().unpairedPagePosition().set(UnpairedPagePosition.RIGHT)
        assertEquals("RIGHT", values["unpaired_page_position"])
        assertEquals(UnpairedPagePosition.RIGHT, readerPreferences().unpairedPagePosition().get())
    }

    @Test
    fun `unknown saved value falls back to automatic without a migration`() {
        values["unpaired_page_position"] = "unknown"
        assertEquals(UnpairedPagePosition.AUTOMATIC, readerPreferences().unpairedPagePosition().get())
    }
}
// KMK <--
