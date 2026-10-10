// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.keyboard.smarttouch.NextLetterModel
import helium314.keyboard.keyboard.smarttouch.SmartTouch
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class SmartTouchTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val keyboardSwitcher = KeyboardSwitcher.getInstance()
    private var eventTime = 1000L

    init {
        keyboardSwitcher.onCreateInputView(latinIME, true)
        keyboardSwitcher.reloadMainKeyboard()
    }

    @BeforeTest
    fun reset() {
        latinIME.prefs().edit {
            clear()
            putBoolean(Settings.PREF_AUTO_CAP, false)
        }
        ShadowInputMethodService.reset()
        val editorInfo = EditorInfo().apply {
            inputType = ShadowInputMethodService.currentInputType
            initialSelStart = 0
            initialSelEnd = 0
        }
        latinIME.mHandler.onStartInput(editorInfo, false)
        latinIME.mHandler.onStartInputView(editorInfo, false)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        keyboardSwitcher.reloadMainKeyboard()
        val model = NextLetterModel.Builder().apply {
            addWord("the", 250)
            addWord("then", 200)
            addWord("there", 200)
            addWord("they", 200)
            addWord("throw", 50)
        }.build()
        SmartTouch.setModelForTesting(keyboardSwitcher.keyboard!!.mId.locale, model)
    }

    @Test fun `touch near border gives the likely letter`() {
        tapKey('t'); tapKey('h')
        tapBetween('e', 'r', 0.6f) // inside r, but close to e
        assertEquals("the", ShadowInputMethodService.text)
    }

    @Test fun `touch on key center gives that key`() {
        tapKey('t'); tapKey('h'); tapKey('r')
        assertEquals("thr", ShadowInputMethodService.text)
    }

    @Test fun `touch far into neighbor is not corrected`() {
        tapKey('t'); tapKey('h')
        tapBetween('e', 'r', 0.85f)
        assertEquals("thr", ShadowInputMethodService.text)
    }

    @Test fun `no correction when disabled`() {
        latinIME.prefs().edit { putBoolean(Settings.PREF_SMART_TOUCH_TARGETS, false) }
        tapKey('t'); tapKey('h')
        tapBetween('e', 'r', 0.6f)
        assertEquals("thr", ShadowInputMethodService.text)
    }

    @Test fun `no correction directly after delete`() {
        tapKey('t'); tapKey('h'); tapKey('r')
        tapKey(KeyCode.DELETE)
        tapBetween('e', 'r', 0.6f)
        assertEquals("thr", ShadowInputMethodService.text)
    }

    @Test fun `new word after picking a suggestion`() {
        tapKey('t'); tapKey('h')
        latinIME.pickSuggestionManually(SuggestedWordInfo("the", "", 0, 0, null, 0, 0))
        // continuing "the" would make r likely, but a new word starts with t
        tapBetween('t', 'r', 0.6f)
        assertEquals("the t", ShadowInputMethodService.text)
    }

    private fun tapKey(char: Char) = tapKey(char.code)

    private fun tapKey(code: Int) {
        val key = keyboardSwitcher.keyboard!!.getKey(code)!!
        tap(key.x + key.width / 2f, key.y + key.height / 2f)
    }

    /** tap at [fraction] of the way from the center of [from] to the center of [to] */
    private fun tapBetween(from: Char, to: Char, fraction: Float) {
        val kb = keyboardSwitcher.keyboard!!
        val a = kb.getKey(from.code)!!
        val b = kb.getKey(to.code)!!
        val ax = a.x + a.width / 2f
        val bx = b.x + b.width / 2f
        tap(ax + (bx - ax) * fraction, a.y + a.height / 2f)
    }

    private fun tap(x: Float, y: Float) {
        // coordinates are in keyboard view coordinates, keys are positioned after the view padding
        val view = keyboardSwitcher.mainKeyboardView
        val viewX = x + view.paddingLeft
        val viewY = y + view.paddingTop
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val me = MotionEvent.obtain(eventTime, eventTime, action, viewX, viewY, 0)
            view.onTouchEvent(me)
            me.recycle()
            eventTime += 50
        }
        eventTime += 200
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }
}
