// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.settings.findIndexOfDefaultSetting
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FoldedSizeTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().prefs()
    private val foldableField = FoldableUtils::class.java.getDeclaredField("isFoldable").apply { isAccessible = true }

    @After fun reset() = foldableField.setBoolean(null, false)

    @Test fun `font scale for folded and unfolded screens`() {
        prefs.edit { clear() }
        assertEquals(1f, Settings.readFontScale(prefs, false, true))
        // the former single setting is the default for every variant
        prefs.edit { putFloat(Settings.PREF_FONT_SCALE, 1.2f) }
        assertEquals(1.2f, Settings.readFontScale(prefs, false, true))
        val foldedKey = createPrefKeyForBooleanSettings(Settings.PREF_FONT_SCALE, findIndexOfDefaultSetting(false, true), 2)
        prefs.edit { putFloat(foldedKey, 0.9f) }
        assertEquals(0.9f, Settings.readFontScale(prefs, false, true))
        assertEquals(1.2f, Settings.readFontScale(prefs, false, false))
        assertEquals(1.2f, Settings.readFontScale(prefs, true, false))
    }

    @Test fun `folded state follows the screen width`() {
        val outer = Configuration().apply { smallestScreenWidthDp = 411 }
        val inner = Configuration().apply { smallestScreenWidthDp = 673 }
        assertFalse(FoldableUtils.isFolded(outer)) // not a foldable
        foldableField.setBoolean(null, true)
        assertTrue(FoldableUtils.isFolded(outer))
        assertFalse(FoldableUtils.isFolded(inner))
    }
}
