// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class PravkaLookTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.prefs()

    private fun upgradeFrom(version: Int) {
        prefs.edit { putInt(Settings.PREF_VERSION_CODE, version) }
        checkVersionUpgrade(context)
    }

    @Test fun `an update switches a stored theme to the Pravka look once`() {
        prefs.edit {
            clear()
            putString(Settings.PREF_THEME_STYLE, KeyboardTheme.STYLE_MATERIAL)
            putBoolean(Settings.PREF_THEME_KEY_BORDERS, false)
            putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_LIGHT)
            putString(Settings.PREF_THEME_COLORS_NIGHT, KeyboardTheme.THEME_DARK)
            putString(Settings.PREF_POPUP_KEYS_HINT_ORDER, "symbols:true")
            putBoolean(Settings.PREF_THEME_DAY_NIGHT, false)
        }
        upgradeFrom(BuildConfig.VERSION_CODE - 1)
        assertEquals(KeyboardTheme.STYLE_ROUNDED, prefs.getString(Settings.PREF_THEME_STYLE, null))
        assertEquals(true, prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, false))
        assertEquals(KeyboardTheme.THEME_PRAVKA_LIGHT, prefs.getString(Settings.PREF_THEME_COLORS, null))
        assertEquals(KeyboardTheme.THEME_PRAVKA, prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, null))
        assertEquals(Defaults.PREF_THEME_DAY_NIGHT, prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, !Defaults.PREF_THEME_DAY_NIGHT))
        assertFalse(prefs.contains(Settings.PREF_POPUP_KEYS_HINT_ORDER))

        // what is chosen afterwards stays on the next update
        prefs.edit { putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_PRAVKA_PAPER) }
        upgradeFrom(BuildConfig.VERSION_CODE - 1)
        assertEquals(KeyboardTheme.THEME_PRAVKA_PAPER, prefs.getString(Settings.PREF_THEME_COLORS, null))
    }

    @Test fun `a fresh install keeps a later choice on its first update`() {
        prefs.edit { clear() }
        upgradeFrom(0) // fresh install
        prefs.edit { putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_LIGHT) }
        upgradeFrom(BuildConfig.VERSION_CODE - 1)
        assertEquals(KeyboardTheme.THEME_LIGHT, prefs.getString(Settings.PREF_THEME_COLORS, null))
    }
}
