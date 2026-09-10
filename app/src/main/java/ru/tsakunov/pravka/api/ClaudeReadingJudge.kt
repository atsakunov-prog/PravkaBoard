package ru.tsakunov.pravka.api

import org.json.JSONArray
import org.json.JSONObject
import ru.tsakunov.pravka.domain.LocalReading
import ru.tsakunov.pravka.domain.ReadingDetail
import ru.tsakunov.pravka.domain.SentenceResult

/** Черновик по предложению до вердикта модели: что услышал микрофон и сколько длилось чтение. */
data class SentenceDraft(val text: String, val heardEn: String, val heardRu: String, val readMs: Long, val enSkipped: Boolean, val ruSkipped: Boolean)

/** Судья чтения с микрофоном: где прочитано верно, где перевод верный, и что подсказать ребёнку. */
class ClaudeReadingJudge(private val api: ClaudeApi) {

    suspend fun judge(textRu: String, drafts: List<SentenceDraft>, apiKey: String, model: String): ReadingDetail {
        val ask = buildString {
            append("Перевод всего текста из книжки (для сверки смысла):\n").append(textRu.ifBlank { "(нет)" }).append("\n\n")
            append("Предложения по порядку. Для каждого: оригинал, что распознал микрофон, когда Боря читал по-английски, ")
            append("и что распознал, когда он переводил на русский. Пропущенные Борей шаги помечены.\n\n")
            drafts.forEachIndexed { i, d ->
                append("#").append(i + 1).append(" EN: ").append(d.text).append('\n')
                append("   heard_en: ").append(if (d.enSkipped) "(пропустил чтение)" else d.heardEn.ifBlank { "(тишина)" }).append('\n')
                val missed = LocalReading.missedWords(d.text, d.heardEn)
                if (!d.enSkipped && missed.isNotEmpty()) append("   не расслышаны слова: ").append(missed.joinToString(", ")).append('\n')
                append("   heard_ru: ").append(if (d.ruSkipped) "(пропустил перевод)" else d.heardRu.ifBlank { "(тишина)" }).append('\n')
            }
            append("\nОцени каждое предложение и сохрани инструментом report_reading.")
        }
        val input = api.callTool(apiKey, model, SYSTEM_PROMPT, JSONArray().put(ClaudeApi.textBlock(ask)), tool(), maxTokens = 6000, timeoutSec = 90)
        return fromToolInput(input, drafts)
    }

    private fun fromToolInput(input: JSONObject, drafts: List<SentenceDraft>): ReadingDetail {
        val arr = input.optJSONArray("sentences") ?: JSONArray()
        val byIndex = HashMap<Int, JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { byIndex[it.optInt("index", -1)] = it }
        val sentences = drafts.mapIndexed { i, d ->
            val o = byIndex[i + 1]
            val reading = when {
                d.enSkipped -> SentenceResult.SKIPPED
                o == null -> LocalReading.readingVerdict(d.text, d.heardEn)
                else -> when (o.optString("reading")) {
                    "clean" -> SentenceResult.READ_OK
                    "slips" -> SentenceResult.READ_SLIPS
                    else -> SentenceResult.READ_WRONG
                }
            }
            val translation = when {
                d.ruSkipped -> SentenceResult.SKIPPED
                o == null -> SentenceResult.UNKNOWN
                else -> when (o.optString("translation")) {
                    "correct" -> SentenceResult.TR_OK
                    "partial" -> SentenceResult.TR_PARTIAL
                    else -> SentenceResult.TR_WRONG
                }
            }
            SentenceResult(d.text, d.heardEn, d.heardRu, reading, translation, o?.optString("comment")?.trim() ?: "", d.readMs)
        }
        return ReadingDetail(sentences, input.optString("praise").trim(), judged = true)
    }

    private fun tool(): JSONObject {
        val sentence = ClaudeApi.objectSchema(
            mapOf(
                "index" to ClaudeApi.intProp("Номер предложения, как в списке (с 1)"),
                "reading" to JSONObject().put("type", "string").put("enum", JSONArray().put("clean").put("slips").put("wrong"))
                    .put("description", "clean — прочитано верно; slips — одна-две заминки или замены; wrong — пропущено или прочитано не то"),
                "translation" to JSONObject().put("type", "string").put("enum", JSONArray().put("correct").put("partial").put("wrong"))
                    .put("description", "correct — смысл передан; partial — половина смысла или пропущено важное слово; wrong — не перевёл или перевёл неверно"),
                "comment" to ClaudeApi.stringProp("Одна короткая фраза по-русски для семилетнего: что не так и как правильно. Пустая строка, если всё верно"),
            ),
        )
        val schema = ClaudeApi.objectSchema(
            mapOf(
                "sentences" to ClaudeApi.arrayOf(sentence),
                "praise" to ClaudeApi.stringProp("Одна тёплая фраза по-русски о том, что получилось лучше всего"),
            ),
        )
        return ClaudeApi.tool(TOOL_NAME, "Сохранить разбор чтения по предложениям", schema)
    }

    companion object {
        const val TOOL_NAME = "report_reading"
        private val SYSTEM_PROMPT = """
            Ты слушаешь, как семилетний Боря (2 класс, английский уровня A1) читает вслух страницу детской книжки
            и сразу переводит каждое предложение на русский. У тебя не звук, а расшифровка речи Google: она
            ошибается, особенно на детском голосе и на похожих словах.

            Как судить чтение (reading):
            - Не считай ошибкой то, что похоже на ошибку распознавания: похожие по звучанию слова (hen/hand, seed/seat,
              said/sad), потерянные артикли, слитые слова, неверная пунктуация. Если расшифровка в целом повторяет
              предложение — clean.
            - slips: одно-два слова явно другие или пропущены, но предложение узнаётся.
            - wrong: половины слов нет, прочитано другое предложение или тишина.

            Как судить перевод (translation):
            - Смысл детскими словами — correct. Другой порядок слов, синонимы, пропущенный артикль или «вот/ну» —
              не ошибка. Русская расшифровка тоже может слегка коверкать слова: додумывай по смыслу.
            - partial: часть смысла есть, но пропущено важное слово или перепутано время/число/кто что делает.
            - wrong: перевёл не то, тишина или повторил по-английски.

            comment: только для не-clean или не-correct, одна короткая фраза ребёнку, по-русски, доброжелательно:
            что было не так и как правильно («Слово wheat — это пшеница, а не вода»). Для верных — пустая строка.
            praise: одна фраза, что получилось лучше всего, без сюсюканья.
        """.trimIndent()
    }
}
