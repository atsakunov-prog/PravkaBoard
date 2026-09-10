package ru.tsakunov.pravka.domain

import org.json.JSONArray
import org.json.JSONObject

/** Разбивка английского текста на предложения для чтения с микрофоном. */
object Sentences {
    // Конец предложения: знак, до трёх закрывающих кавычек/скобок, пробел, дальше заглавная, цифра или открывающая
    // кавычка. Точка после Mr/Mrs/Ms/Dr/St — не конец предложения.
    private val END = Regex("""(?<=[.!?…]["»”’')]{0,3})(?<!\b(?:Mr|Mrs|Ms|Dr|St)\.)\s+(?=["«“‘(]?[A-Z0-9])""")
    private val PARAGRAPH = Regex("\n\\s*\n")
    private val SPACES = Regex("\\s+")

    fun split(text: String): List<String> =
        text.split(PARAGRAPH)
            .flatMap { para ->
                val flat = para.replace(SPACES, " ").trim()
                if (flat.isEmpty()) emptyList() else flat.split(END)
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

/** Итог по одному предложению: что услышали, как прочитано, как переведено. */
data class SentenceResult(
    val text: String,
    val heardEn: String,
    val heardRu: String,
    /** READ_OK, READ_SLIPS, READ_WRONG или SKIPPED. */
    val reading: String,
    /** TR_OK, TR_PARTIAL, TR_WRONG, SKIPPED или UNKNOWN (модель недоступна). */
    val translation: String,
    /** Короткий комментарий для ребёнка по-русски; пусто, когда всё верно. */
    val comment: String,
    val readMs: Long,
) {
    val readClean: Boolean get() = reading == READ_OK
    val readSlips: Boolean get() = reading == READ_SLIPS
    val translated: Boolean get() = translation == TR_OK

    fun toJson(): JSONObject = JSONObject()
        .put("text", text).put("heardEn", heardEn).put("heardRu", heardRu)
        .put("reading", reading).put("translation", translation).put("comment", comment).put("readMs", readMs)

    companion object {
        const val READ_OK = "ok"
        const val READ_SLIPS = "slips"
        const val READ_WRONG = "wrong"
        const val TR_OK = "ok"
        const val TR_PARTIAL = "partial"
        const val TR_WRONG = "wrong"
        const val SKIPPED = "skipped"
        const val UNKNOWN = "unknown"

        fun fromJson(o: JSONObject) = SentenceResult(
            text = o.optString("text"), heardEn = o.optString("heardEn"), heardRu = o.optString("heardRu"),
            reading = o.optString("reading", UNKNOWN), translation = o.optString("translation", UNKNOWN),
            comment = o.optString("comment"), readMs = o.optLong("readMs", 0L),
        )
    }
}

/** Подробности чтения с микрофоном: по предложениям плюс похвала от модели. */
data class ReadingDetail(val sentences: List<SentenceResult>, val praise: String, val judged: Boolean) {
    val total: Int get() = sentences.size
    val readOk: Int get() = sentences.count { it.readClean }
    val readSlips: Int get() = sentences.count { it.readSlips }
    val transOk: Int get() = sentences.count { it.translated }
    val transPartial: Int get() = sentences.count { it.translation == SentenceResult.TR_PARTIAL }
    val translationKnown: Boolean get() = judged && sentences.any { it.translation != SentenceResult.UNKNOWN }

    fun toJson(): String = JSONObject()
        .put("praise", praise).put("judged", judged)
        .put("sentences", JSONArray().also { a -> sentences.forEach { a.put(it.toJson()) } })
        .toString()

    companion object {
        fun fromJson(text: String): ReadingDetail? = runCatching {
            val o = JSONObject(text)
            val arr = o.optJSONArray("sentences") ?: JSONArray()
            ReadingDetail(
                sentences = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { SentenceResult.fromJson(it) },
                praise = o.optString("praise"),
                judged = o.optBoolean("judged", false),
            )
        }.getOrNull()
    }
}

/**
 * Локальная оценка чтения без модели: доля слов предложения, которые распознались (с допуском на 1–2 буквы).
 * Используется, когда модель недоступна, и как подсказка модели.
 */
object LocalReading {
    fun readingVerdict(sentence: String, heardEn: String): String {
        if (heardEn.isBlank()) return SentenceResult.SKIPPED
        val expected = Matching.normalizeSpeech(sentence).split(' ').filter { it.isNotEmpty() }
        if (expected.isEmpty()) return SentenceResult.READ_OK
        val heard = Matching.normalizeSpeech(heardEn).split(' ').filter { it.isNotEmpty() }
        val matched = expected.count { e -> heard.any { Matching.tokenClose(e, it) } }
        val ratio = matched.toDouble() / expected.size
        return when {
            ratio >= 0.85 -> SentenceResult.READ_OK
            ratio >= 0.55 -> SentenceResult.READ_SLIPS
            else -> SentenceResult.READ_WRONG
        }
    }

    /** Слова предложения, которых в расшифровке не нашлось. */
    fun missedWords(sentence: String, heardEn: String): List<String> {
        val heard = Matching.normalizeSpeech(heardEn).split(' ').filter { it.isNotEmpty() }
        return Matching.normalizeSpeech(sentence).split(' ').filter { it.isNotEmpty() && heard.none { h -> Matching.tokenClose(it, h) } }
    }
}
