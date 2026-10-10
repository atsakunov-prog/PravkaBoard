// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.smarttouch

import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Predicts the next letter of the word being typed from the letters typed so far.
 *
 * This is a character n-gram model trained on the words of a dictionary weighted by their
 * frequency, with interpolation between orders (Witten-Bell style), as used for language model
 * driven key-target resizing (Goodman et al. 2002, Gunawardana et al. 2010).
 *
 * Storage is compact so it can stay in memory while typing: contexts form a trie that is read
 * backwards from the cursor, and both the trie and the n-gram table are sorted int arrays with
 * quantized probabilities.
 */
class NextLetterModel private constructor(
    val order: Int,
    /** letter code point for each letter index, index 0 is the word start marker */
    private val alphabet: IntArray,
    /** sorted keys parentContextId * RADIX + letterIndex, the root context has id 0 */
    private val contextKeys: IntArray,
    /** context id for each entry in [contextKeys] */
    private val contextIds: IntArray,
    /** quantized interpolation weight for each context id, see [quantizeLambda] */
    private val contextLambda: ByteArray,
    /** sorted keys contextId * RADIX + letterIndex */
    private val ngramKeys: IntArray,
    /** quantized maximum likelihood probability for each entry in [ngramKeys] */
    private val ngramProb: ByteArray,
) {
    private val sortedLetters: IntArray
    private val sortedLetterIndices: IntArray

    init {
        val byCodePoint = (1 until alphabet.size).sortedBy { alphabet[it] }
        sortedLetters = IntArray(byCodePoint.size) { alphabet[byCodePoint[it]] }
        sortedLetterIndices = IntArray(byCodePoint.size) { byCodePoint[it] }
    }

    val letterCount get() = alphabet.size - 1

    /** Whether the letter appears in the words the model was built from, ignoring case (see [normalize]). */
    fun hasLetter(codePoint: Int) = indexOf(codePoint) > 0

    /** Context ids for orders 0 until [order], read backwards from the end of the typed word. */
    class Context internal constructor(internal val ids: IntArray, internal val size: Int)

    /**
     * Creates the context for predicting the letter after [prefix], which should contain only
     * the part of the current word before the cursor (empty at the start of a word).
     */
    fun context(prefix: CharSequence): Context {
        val ids = IntArray(order)
        var size = 1 // ids[0] is the root context
        var parent = 0
        var pos = prefix.length
        while (size < order) {
            val letter = if (pos == 0) {
                WORD_START
            } else {
                val cp = Character.codePointBefore(prefix, pos)
                pos -= Character.charCount(cp)
                indexOf(cp)
            }
            if (letter < 0) break
            val idx = contextKeys.binarySearch(parent * RADIX + letter)
            if (idx < 0) break
            parent = contextIds[idx]
            ids[size++] = parent
            if (letter == WORD_START) break
        }
        return Context(ids, size)
    }

    /** Probability that the next letter is [codePoint], ignoring case (see [normalize]). */
    fun probability(context: Context, codePoint: Int): Float {
        val letter = indexOf(codePoint)
        // the base distribution is uniform over known letters plus one slot for unknown letters
        var p = 1f / alphabet.size
        for (i in 0 until context.size) {
            val ctx = context.ids[i]
            val idx = if (letter > 0) ngramKeys.binarySearch(ctx * RADIX + letter) else -1
            val ml = if (idx >= 0) PROB_TABLE[ngramProb[idx].toInt() and 0xFF] else 0f
            val lambda = lambda(ctx)
            p = lambda * ml + (1f - lambda) * p
        }
        return p
    }

    private fun lambda(contextId: Int) = LAMBDA_TABLE[contextLambda[contextId].toInt() and 0xFF]

    private fun indexOf(codePoint: Int): Int {
        val idx = sortedLetters.binarySearch(normalize(codePoint))
        return if (idx < 0) -1 else sortedLetterIndices[idx]
    }

    fun sizeInBytes() = (alphabet.size + contextKeys.size * 2 + ngramKeys.size) * 4 + contextLambda.size + ngramProb.size

    @Throws(IOException::class)
    fun writeTo(out: OutputStream) {
        val data = DataOutputStream(out)
        data.writeInt(MAGIC)
        data.writeInt(FORMAT_VERSION)
        data.writeInt(order)
        data.writeIntArray(alphabet)
        data.writeIntArray(contextKeys)
        data.writeIntArray(contextIds)
        data.writeByteArray(contextLambda)
        data.writeIntArray(ngramKeys)
        data.writeByteArray(ngramProb)
        data.flush()
    }

    /**
     * Collects n-gram counts from dictionary words. To limit memory use, only add the more frequent
     * words of large dictionaries, this hardly affects prediction quality.
     */
    class Builder(private val order: Int = DEFAULT_ORDER) {
        init { require(order in 1..MAX_ORDER) }

        private val letters = HashMap<Int, Int>()
        private val alphabet = ArrayList<Int>().apply { add(0) }
        private val buffer = IntArray(MAX_WORD_LENGTH)

        // context trie, ids are assigned in order of creation
        private val contexts = IntIntMap()
        private var contextCount = 1
        private var contextWeight = DoubleArray(1 shl 12)
        private var contextTypes = IntArray(1 shl 12)
        private var contextDistinct = IntArray(1 shl 12)

        private val ngrams = NgramAccumulator()

        /**
         * Adds a word with its dictionary unigram [probability] (0..255, log scale).
         * Words are split at non-letters.
         */
        fun addWord(word: CharSequence, probability: Int) {
            if (probability < 0) return
            val weight = 2.0.pow(probability / FREQUENCY_SCALE)
            var length = 0
            var i = 0
            while (i < word.length) {
                val cp = Character.codePointAt(word, i)
                i += Character.charCount(cp)
                val letter = letterIndex(cp)
                if (letter < 0) {
                    addLetters(length, weight)
                    length = 0
                } else if (length < MAX_WORD_LENGTH) {
                    buffer[length++] = letter
                }
            }
            addLetters(length, weight)
        }

        private fun letterIndex(codePoint: Int): Int {
            if (!Character.isLetter(codePoint)) return -1
            val cp = normalize(codePoint)
            return letters[cp] ?: run {
                if (alphabet.size >= RADIX) return -1
                alphabet.add(cp)
                letters[cp] = alphabet.size - 1
                alphabet.size - 1
            }
        }

        private fun addLetters(length: Int, weight: Double) {
            for (pos in 0 until length) {
                val next = buffer[pos]
                addNgram(0, next, weight)
                var context = 0
                for (n in 1..minOf(order - 1, pos + 1)) {
                    val letter = if (n <= pos) buffer[pos - n] else WORD_START
                    context = childContext(context, letter)
                    addNgram(context, next, weight)
                }
            }
        }

        private fun childContext(parent: Int, letter: Int): Int {
            val key = parent * RADIX + letter
            val existing = contexts.get(key)
            if (existing >= 0) return existing
            val id = contextCount++
            require(id < MAX_CONTEXTS) { "too many contexts" }
            if (id == contextWeight.size) {
                val size = id * 2
                contextWeight = contextWeight.copyOf(size)
                contextTypes = contextTypes.copyOf(size)
                contextDistinct = contextDistinct.copyOf(size)
            }
            contexts.put(key, id)
            return id
        }

        private fun addNgram(context: Int, letter: Int, weight: Double) {
            if (ngrams.add(context * RADIX + letter, weight.toFloat()))
                contextDistinct[context]++
            contextWeight[context] += weight
            contextTypes[context]++
        }

        fun build(): NextLetterModel {
            val contextLambda = ByteArray(contextCount) {
                val types = contextTypes[it].toDouble()
                quantizeLambda(if (types == 0.0) 0.0 else types / (types + contextDistinct[it]))
            }
            // pack key and payload into longs so we can sort without boxing
            val contextEntries = LongArray(contexts.size)
            var i = 0
            contexts.forEach { key, id -> contextEntries[i++] = (key.toLong() shl 32) or id.toLong() }
            contextEntries.sort()

            var ngramEntries = LongArray(ngrams.size)
            i = 0
            ngrams.forEach { key, weight ->
                val context = key / RADIX
                val prob = quantizeProbability(weight / contextWeight[context])
                ngramEntries[i++] = (key.toLong() shl 8) or (prob.toLong() and 0xFF)
            }
            ngramEntries = ngramEntries.copyOf(i)
            ngramEntries.sort()

            return NextLetterModel(
                order,
                alphabet.toIntArray(),
                IntArray(contextEntries.size) { (contextEntries[it] ushr 32).toInt() },
                IntArray(contextEntries.size) { contextEntries[it].toInt() },
                contextLambda,
                IntArray(ngramEntries.size) { (ngramEntries[it] ushr 8).toInt() },
                ByteArray(ngramEntries.size) { ngramEntries[it].toByte() },
            )
        }
    }

    companion object {
        const val DEFAULT_ORDER = 6
        private const val MAX_ORDER = 8
        private const val WORD_START = 0
        private const val RADIX = 256 // maximum alphabet size including the word start marker
        private const val MAX_CONTEXTS = Int.MAX_VALUE / RADIX
        private const val MAX_WORD_LENGTH = 48
        // dictionary probabilities are on a log scale, this factor was fitted on Russian text
        private const val FREQUENCY_SCALE = 10.0
        private const val MAGIC = 0x4E4C4D31 // NLM1
        private const val FORMAT_VERSION = 2

        private val PROB_TABLE = FloatArray(256) { 2.0.pow(-it / 16.0).toFloat() }
        private val LAMBDA_TABLE = FloatArray(256) { 1f - 2.0.pow(-it / 16.0).toFloat() }

        private fun quantizeProbability(p: Double): Byte =
            (-ln(p) / ln(2.0) * 16).roundToInt().coerceIn(0, 255).toByte()

        // lambda is often very close to 1, but must never be exactly 1, or unseen letters get probability 0
        private fun quantizeLambda(lambda: Double): Byte =
            if (lambda >= 1.0) 255.toByte()
            else (-ln(1 - lambda) / ln(2.0) * 16).roundToInt().coerceIn(0, 255).toByte()

        /** Lower case, and letters that are usually typed as their base letter (ё as е) are merged. */
        @JvmStatic
        fun normalize(codePoint: Int): Int {
            val lower = Character.toLowerCase(codePoint)
            return if (lower == 'ё'.code) 'е'.code else lower
        }

        @Throws(IOException::class)
        fun readFrom(input: InputStream): NextLetterModel {
            val buffer = ByteBuffer.wrap(input.readBytes())
            try {
                if (buffer.getInt() != MAGIC || buffer.getInt() != FORMAT_VERSION) throw IOException("unknown format")
                val order = buffer.getInt()
                if (order !in 1..MAX_ORDER) throw IOException("invalid order $order")
                val alphabet = buffer.readIntArray()
                val contextKeys = buffer.readIntArray()
                val contextIds = buffer.readIntArray()
                val contextLambda = buffer.readByteArray()
                val ngramKeys = buffer.readIntArray()
                val ngramProb = buffer.readByteArray()
                if (alphabet.isEmpty() || alphabet.size > RADIX || contextKeys.size != contextIds.size
                    || contextLambda.size != contextKeys.size + 1 || ngramKeys.size != ngramProb.size
                    || contextIds.any { it !in 1 until contextLambda.size }
                    || ngramKeys.any { it < 0 || it / RADIX >= contextLambda.size })
                    throw IOException("inconsistent data")
                return NextLetterModel(order, alphabet, contextKeys, contextIds, contextLambda, ngramKeys, ngramProb)
            } catch (e: RuntimeException) {
                throw IOException("invalid data", e)
            }
        }

        private fun DataOutputStream.writeIntArray(array: IntArray) {
            writeInt(array.size)
            val bytes = ByteBuffer.allocate(array.size * 4)
            bytes.asIntBuffer().put(array)
            write(bytes.array())
        }

        private fun DataOutputStream.writeByteArray(array: ByteArray) {
            writeInt(array.size)
            write(array)
        }

        private fun ByteBuffer.readIntArray(): IntArray {
            val size = getInt()
            if (size < 0 || size.toLong() * 4 > remaining()) throw IOException("invalid size $size")
            val array = IntArray(size)
            asIntBuffer().get(array)
            position(position() + size * 4)
            return array
        }

        private fun ByteBuffer.readByteArray(): ByteArray {
            val size = getInt()
            if (size < 0 || size > remaining()) throw IOException("invalid size $size")
            val array = ByteArray(size)
            get(array)
            return array
        }
    }
}

/** Open addressing hash map from non-negative int keys to non-negative int values. */
private class IntIntMap {
    private var keys = IntArray(1 shl 12) { -1 }
    private var values = IntArray(1 shl 12)
    var size = 0
        private set

    fun get(key: Int): Int {
        var i = slot(key, keys.size)
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == -1) return -1
            i = (i + 1) and (keys.size - 1)
        }
    }

    fun put(key: Int, value: Int) {
        var i = slot(key, keys.size)
        while (keys[i] != -1 && keys[i] != key) i = (i + 1) and (keys.size - 1)
        if (keys[i] == -1) size++
        keys[i] = key
        values[i] = value
        if (size * 4 > keys.size * 3) grow()
    }

    fun forEach(action: (Int, Int) -> Unit) {
        for (i in keys.indices) if (keys[i] != -1) action(keys[i], values[i])
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = IntArray(oldKeys.size * 2) { -1 }
        values = IntArray(oldKeys.size * 2)
        size = 0
        for (i in oldKeys.indices) if (oldKeys[i] != -1) put(oldKeys[i], oldValues[i])
    }
}

/** Open addressing hash map from non-negative int keys to accumulated weights. */
private class NgramAccumulator {
    private var keys = IntArray(1 shl 14) { -1 }
    private var weights = FloatArray(1 shl 14)
    var size = 0
        private set

    /** Returns whether the key is new. */
    fun add(key: Int, weight: Float): Boolean {
        var i = slot(key, keys.size)
        while (true) {
            val k = keys[i]
            if (k == key) {
                weights[i] += weight
                return false
            }
            if (k == -1) {
                keys[i] = key
                weights[i] = weight
                if (++size * 4 > keys.size * 3) grow()
                return true
            }
            i = (i + 1) and (keys.size - 1)
        }
    }

    fun forEach(action: (Int, Double) -> Unit) {
        for (i in keys.indices) if (keys[i] != -1) action(keys[i], weights[i].toDouble())
    }

    private fun grow() {
        val oldKeys = keys
        val oldWeights = weights
        keys = IntArray(oldKeys.size * 2) { -1 }
        weights = FloatArray(oldKeys.size * 2)
        size = 0
        for (i in oldKeys.indices) if (oldKeys[i] != -1) add(oldKeys[i], oldWeights[i])
    }
}

private fun slot(key: Int, capacity: Int): Int {
    val h = key * -0x61c88647
    return (h xor (h ushr 16)) and (capacity - 1)
}
