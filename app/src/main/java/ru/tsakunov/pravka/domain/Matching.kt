package ru.tsakunov.pravka.domain

import kotlin.math.ceil
import kotlin.math.min

/** Сопоставление распознанной речи с ожидаемым английским словом или фразой. */
object Matching {
    private val STOP = setOf("a", "an", "the", "to", "is", "are", "am", "and", "it", "i")

    fun normalizeSpeech(s: String): String =
        s.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^\\p{L}\\p{N}\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Ожидаемое слово в двух видах: как есть и без ведущего артикля / «to». */
    fun expectedVariants(expected: String): List<String> {
        val base = normalizeSpeech(expected)
        val stripped = base.replace(Regex("^(a|an|the|to) "), "")
        return listOf(base, stripped).filter { it.isNotEmpty() }.distinct()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            prev.indices.forEach { prev[it] = cur[it] }
        }
        return prev[b.length]
    }

    fun tokenClose(expected: String, heard: String): Boolean {
        if (expected == heard) return true
        val d = levenshtein(expected, heard)
        return (expected.length >= 4 && d <= 1) || (expected.length >= 7 && d <= 2)
    }

    /** Есть ли среди вариантов распознавания приемлемый ответ. */
    fun matches(expected: String, hypotheses: List<String>): Boolean {
        val variants = expectedVariants(expected)
        if (variants.isEmpty()) return false
        for (h in hypotheses) {
            val hn = normalizeSpeech(h)
            if (hn.isEmpty()) continue
            for (v in variants) {
                if (" $hn ".contains(" $v ")) return true
                val expTokens = v.split(' ').filter { it.isNotEmpty() && (it !in STOP || v.split(' ').size <= 2) }
                if (expTokens.isEmpty()) continue
                val hTokens = hn.split(' ')
                val matched = expTokens.count { e -> hTokens.any { tokenClose(e, it) } }
                val need = if (expTokens.size <= 2) expTokens.size else ceil(expTokens.size * 0.7).toInt()
                if (matched >= need) return true
            }
        }
        return false
    }

    /** Фраза ли это (тогда при провале локальной проверки стоит спросить модель). */
    fun isPhrase(expected: String): Boolean = normalizeSpeech(expected).split(' ').size >= 3
}
