// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.widget.TextView
import androidx.compose.ui.text.font.FontFamily
import helium314.keyboard.latin.common.isEmoji
import helium314.keyboard.latin.settings.Settings

object KeyboardTypeface {
    private val lock = Any()

    private var cachedCustomTypeface: Typeface? = null
    private var cachedCustomFontFamily: FontFamily? = null
    @Volatile
    private var customTypefaceLoaded = false

    private var cachedEmojiTypeface: Typeface? = null
    @Volatile
    private var emojiTypefaceLoaded = false

    // PravkaBoard: without a font of the user's own, keys and suggestions are set in Golos Text, the font of the
    // Pravka app, drawn for Cyrillic (OFL, assets/fonts/OFL-GolosText.txt). Roboto made the keyboard look dated.
    private const val BUILT_IN_FONT = "fonts/golos_text.ttf"
    private const val BUILT_IN_WEIGHT = 400
    private const val BUILT_IN_BOLD_WEIGHT = 600
    private var cachedBuiltIn: Typeface? = null
    private var cachedBuiltInBold: Typeface? = null
    @Volatile
    private var builtInLoaded = false

    // it's a variable font, the weights need font variation settings
    private fun loadBuiltIn(context: Context, weight: Int): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            Typeface.Builder(context.assets, BUILT_IN_FONT).setFontVariationSettings("'wght' $weight").build()
        }.getOrNull()
    }

    /** Golos Text in the weight of [default], null for monospace and serif, which it can't stand in for */
    private fun builtInTypeface(default: Typeface): Typeface? {
        if (default == Typeface.MONOSPACE || default == Typeface.SERIF) return null
        if (!builtInLoaded) {
            val context = Settings.getCurrentContext() ?: return null
            synchronized(lock) {
                if (!builtInLoaded) {
                    cachedBuiltIn = loadBuiltIn(context, BUILT_IN_WEIGHT)
                    cachedBuiltInBold = loadBuiltIn(context, BUILT_IN_BOLD_WEIGHT)
                    builtInLoaded = true
                }
            }
        }
        return if (default.isBold) cachedBuiltInBold else cachedBuiltIn
    }

    private fun loadCustomTypeface(context: Context): Typeface? {
        return runCatching {
            Typeface.createFromFile(Settings.getCustomFontFile(context))
        }.getOrNull()
    }

    private fun loadCustomEmojiTypeface(context: Context): Typeface? {
        return runCatching {
            Typeface.createFromFile(Settings.getCustomEmojiFontFile(context))
        }.getOrNull()
    }

    @JvmStatic
    fun customTypeface(): Typeface? {
        if (customTypefaceLoaded) return cachedCustomTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!customTypefaceLoaded) {
                cachedCustomTypeface = loadCustomTypeface(context)
                cachedCustomFontFamily = cachedCustomTypeface?.let(::FontFamily)
                customTypefaceLoaded = true
            }
            return cachedCustomTypeface
        }
    }

    @JvmStatic
    fun emojiTypeface(): Typeface? {
        if (emojiTypefaceLoaded) return cachedEmojiTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!emojiTypefaceLoaded) {
                cachedEmojiTypeface = loadCustomEmojiTypeface(context)
                emojiTypefaceLoaded = true
            }
            return cachedEmojiTypeface
        }
    }

    @JvmStatic
    fun customFontFamily(): FontFamily? {
        if (!customTypefaceLoaded) customTypeface()
        return cachedCustomFontFamily
    }

    @JvmStatic
    fun resolve(
        text: CharSequence?,
        defaultTypeface: Typeface = Typeface.DEFAULT,
    ): Typeface {
        val emojiTypeface = emojiTypeface()
        return if (emojiTypeface != null && text != null && isEmoji(text)) {
            emojiTypeface
        } else {
            customTypeface() ?: builtInTypeface(defaultTypeface) ?: defaultTypeface
        }
    }

    @JvmStatic
    fun applyToTextView(textView: TextView) {
        applyToTextView(textView, textView.text, Typeface.DEFAULT)
    }

    @JvmStatic
    fun applyToTextView(textView: TextView, text: CharSequence?, defaultTypeface: Typeface) {
        textView.typeface = resolve(text, defaultTypeface = defaultTypeface)
    }

    @JvmStatic
    fun clearCache() {
        synchronized(lock) {
            cachedCustomTypeface = null
            cachedCustomFontFamily = null
            customTypefaceLoaded = false
            cachedEmojiTypeface = null
            emojiTypefaceLoaded = false
            cachedBuiltIn = null
            cachedBuiltInBold = null
            builtInLoaded = false
        }
    }
}
