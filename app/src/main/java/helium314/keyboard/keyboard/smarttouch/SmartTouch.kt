// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.smarttouch

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.accessibility.AccessibilityUtils
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyDetector
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

/**
 * Smart touch targets: when a letter key is hit near a neighboring letter key, choose the key the
 * user most likely intended, using the touch position and how likely each letter is to follow the
 * letters typed before in the current word. Gboard, Yandex Keyboard and iOS do the same invisibly.
 *
 * Each key's central area always returns that key, so every letter can still be typed on purpose
 * (anchored dynamic key-targets, Gunawardana et al. 2010). Further out, a neighbor may take over
 * if it's much more likely, but never further than a fraction of a key width into the hit key.
 */
object SmartTouch : KeyDetector.HitKeyCorrector {
    private const val TAG = "SmartTouch"
    private const val MAX_WORD_LENGTH = 32
    /** only the most frequent words are used, rare words add a lot of memory but hardly any accuracy */
    private const val MAX_DICTIONARY_WORDS = 100_000
    private const val RETRY_DELAY_MILLIS = 60_000L
    private const val CACHE_FOLDER = "smart_touch"
    private const val CACHE_VERSION = 1

    private val models = ConcurrentHashMap<Locale, NextLetterModel>()
    private val loading: MutableSet<Locale> = Collections.newSetFromMap(ConcurrentHashMap())
    private val failedAt = ConcurrentHashMap<Locale, Long>()

    private var appContext: Context? = null
    private var wordBeforeCursor: (() -> CharSequence?)? = null

    // the context is the same for all touches until the word before the cursor changes
    private var lastModel: NextLetterModel? = null
    private var lastWord: String? = null
    private var lastContext: NextLetterModel.Context? = null

    /** [wordBeforeCursor] returns the letters of the current word before the cursor, or null to disable correction */
    @JvmStatic
    fun init(context: Context, wordBeforeCursor: () -> CharSequence?) {
        appContext = context.applicationContext
        this.wordBeforeCursor = wordBeforeCursor
    }

    /** Starts loading the model for the keyboard's language, so it's ready when typing starts. */
    @JvmStatic
    fun preload(keyboard: Keyboard) {
        if (Settings.getValues().mSmartTouchTargets && keyboard.mId.element.isAlphabet)
            getModel(keyboard.mId.locale)
    }

    @JvmStatic
    fun setModelForTesting(locale: Locale, model: NextLetterModel) {
        models[locale] = model
    }

    override fun correct(keyboard: Keyboard, hitKey: Key, x: Int, y: Int): Key {
        val settings = Settings.getValues()
        if (!settings.mSmartTouchTargets || !keyboard.mId.element.isAlphabet || !isLetterKey(hitKey))
            return hitKey
        if (AccessibilityUtils.instance.isTouchExplorationEnabled) return hitKey
        val model = getModel(keyboard.mId.locale) ?: return hitKey
        // e.g. Korean dictionaries contain syllables, but keys are jamo
        if (!model.hasLetter(hitKey.code)) return hitKey
        val word = wordBeforeCursor?.invoke() ?: return hitKey
        if (word.length > MAX_WORD_LENGTH) return hitKey
        val context = getContext(model, word)

        val params = Params.forStrength(settings.mSmartTouchStrength)
        val keyWidth = keyboard.mMostCommonKeyWidth.toFloat()
        val keyHeight = keyboard.mMostCommonKeyHeight.toFloat()
        val marginX = params.extendX * keyWidth
        val marginY = params.extendY * keyHeight
        val twoVariance = 2f * params.variance * keyWidth * keyWidth

        var best = hitKey
        var bestScore = score(model, context, hitKey, x, y, twoVariance)
        for (key in keyboard.getNearestKeys(x, y)) {
            if (key === hitKey || !isLetterKey(key) || !model.hasLetter(key.code)) continue
            val box = key.hitBox
            if (x < box.left - marginX || x > box.right + marginX || y < box.top - marginY || y > box.bottom + marginY)
                continue
            val score = score(model, context, key, x, y, twoVariance)
            if (score > bestScore) {
                best = key
                bestScore = score
            }
        }
        return best
    }

    private fun isLetterKey(key: Key) = Character.isLetter(key.code)

    /** log of letter probability plus log of the (unnormalized) Gaussian touch probability */
    private fun score(model: NextLetterModel, context: NextLetterModel.Context, key: Key, x: Int, y: Int, twoVariance: Float): Float {
        val dx = x - (key.x + key.width / 2f)
        val dy = y - (key.y + key.height / 2f)
        return ln(letterProbability(model, context, key)) - (dx * dx + dy * dy) / twoVariance
    }

    /** probability of the key's letter, plus letters in its popup like ъ on the х key */
    private fun letterProbability(model: NextLetterModel, context: NextLetterModel.Context, key: Key): Float {
        val code = NextLetterModel.normalize(key.code)
        var p = model.probability(context, code)
        key.popupKeys?.forEach {
            if (it.mCode > 0 && Character.isLetter(it.mCode) && NextLetterModel.normalize(it.mCode) != code)
                p += model.probability(context, it.mCode)
        }
        return p
    }

    private fun getContext(model: NextLetterModel, word: CharSequence): NextLetterModel.Context {
        val wordString = word.toString()
        val cached = lastContext
        if (cached != null && model === lastModel && wordString == lastWord) return cached
        return model.context(wordString).also {
            lastModel = model
            lastWord = wordString
            lastContext = it
        }
    }

    private fun getModel(locale: Locale): NextLetterModel? {
        models[locale]?.let { return it }
        val context = appContext ?: return null
        val failed = failedAt[locale]
        if (failed != null && SystemClock.elapsedRealtime() - failed < RETRY_DELAY_MILLIS) return null
        if (loading.add(locale)) {
            ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
                // building takes a few seconds of CPU time, which should not slow down typing
                val priority = Process.getThreadPriority(Process.myTid())
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    val model = loadModel(context, locale)
                    if (model != null) models[locale] = model
                    else failedAt[locale] = SystemClock.elapsedRealtime()
                } catch (e: Throwable) { // also catch OutOfMemoryError, the keyboard works fine without model
                    Log.w(TAG, "could not create model for $locale", e)
                    failedAt[locale] = SystemClock.elapsedRealtime()
                } finally {
                    loading.remove(locale)
                    Process.setThreadPriority(priority)
                }
            }
        }
        return null
    }

    private fun loadModel(context: Context, locale: Locale): NextLetterModel? {
        val dictFile = getMainDictionaryFile(context, locale) ?: return null
        val sourceKey = "$CACHE_VERSION:${dictFile.absolutePath}:${dictFile.length()}:${dictFile.lastModified()}"
        val cacheFile = File(File(context.cacheDir, CACHE_FOLDER), locale.toLanguageTag() + ".bin")
        if (cacheFile.isFile) {
            try {
                DataInputStream(cacheFile.inputStream().buffered()).use {
                    if (it.readUTF() == sourceKey) return NextLetterModel.readFrom(it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not read cached model for $locale", e)
            }
        }

        val start = SystemClock.elapsedRealtime()
        val dict = BinaryDictionary(dictFile.absolutePath, 0, dictFile.length(), false, locale, Dictionary.TYPE_MAIN, false)
        val model = try {
            if (!dict.isValidDictionary) return null
            buildModel(dict)
        } finally {
            dict.close()
        }
        Log.i(TAG, "created model for $locale with ${model.letterCount} letters, ${model.sizeInBytes() / 1024} kB, " +
                "in ${SystemClock.elapsedRealtime() - start} ms")
        try {
            cacheFile.parentFile?.mkdirs()
            val tempFile = File(cacheFile.path + ".tmp")
            DataOutputStream(tempFile.outputStream().buffered()).use {
                it.writeUTF(sourceKey)
                model.writeTo(it)
            }
            tempFile.renameTo(cacheFile)
        } catch (e: Exception) {
            Log.w(TAG, "could not cache model for $locale", e)
        }
        return model
    }

    private fun buildModel(dict: BinaryDictionary): NextLetterModel {
        // only use the most frequent words: find the lowest probability that still fits
        val histogram = IntArray(256)
        dict.forEachWord { _, probability -> if (probability in 0..255) histogram[probability]++ }
        var threshold = 255
        var count = histogram[255]
        while (threshold > 0 && count + histogram[threshold - 1] <= MAX_DICTIONARY_WORDS) {
            threshold--
            count += histogram[threshold]
        }
        val builder = NextLetterModel.Builder()
        dict.forEachWord { word, probability -> if (probability >= threshold) builder.addWord(word, probability) }
        return builder.build()
    }

    /** The main dictionary as used for suggestions, user-provided dictionaries first. */
    private fun getMainDictionaryFile(context: Context, locale: Locale): File? {
        val (cached, notExtracted) = DictionaryFactory.getAvailableDictsForLocale(locale, context, false)
        cached.filter { DictionaryInfoUtils.getDictionaryFileHeaderOrNull(it)?.mIdString?.substringBefore(":") == Dictionary.TYPE_MAIN }
            .minByOrNull { !it.name.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX) }
            ?.let { return it }
        val assetsName = notExtracted.firstOrNull { it.substringBefore("_") == Dictionary.TYPE_MAIN } ?: return null
        return DictionaryInfoUtils.extractAssetsDictionary(assetsName, locale, context)
    }

    /**
     * Tuning was done by simulating noisy taps on Russian text: the variance of the touch model (in
     * key widths squared) sets how much a letter's probability may move the border between keys,
     * and the extension (in key widths/heights) limits how far a key can reach into its neighbors.
     */
    private class Params(val variance: Float, val extendX: Float, val extendY: Float) {
        companion object {
            private var cachedStrength = Float.NaN
            private var cached: Params? = null

            fun forStrength(strength: Float): Params {
                cached?.let { if (strength == cachedStrength) return it }
                val s = strength.coerceIn(0f, 1f)
                return Params(0.02f + 0.08f * s, 0.15f + 0.25f * s, 0.1f + 0.1f * s).also {
                    cached = it
                    cachedStrength = strength
                }
            }
        }
    }
}
