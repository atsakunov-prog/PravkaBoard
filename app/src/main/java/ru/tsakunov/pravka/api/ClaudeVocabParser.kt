package ru.tsakunov.pravka.api

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class ParsedItem(val en: String, val ru: String, val kind: String)
data class ParsedVocabulary(val title: String, val items: List<ParsedItem>)

/** Разбор фотографий страницы словаря через Claude Opus. */
class ClaudeVocabParser(private val context: Context, private val api: ClaudeApi) {

    suspend fun parse(images: List<Uri>, apiKey: String, model: String): ParsedVocabulary =
        fromToolInput(api.callTool(apiKey, model, request(images)))

    /** Запрос без отправки: для пакетной обработки. */
    suspend fun request(images: List<Uri>): ToolRequest {
        if (images.isEmpty()) throw ClaudeException("Нет фотографий")
        val content = JSONArray()
        for (uri in images) content.put(ClaudeApi.imageBlock(Images.encodeJpegBase64(context, uri)))
        content.put(
            ClaudeApi.textBlock(
                "На фото — страница(ы) школьного словаря по английскому. Извлеки все словарные пары " +
                    "и сохрани их инструментом save_vocabulary. Сохраняй порядок как на странице.",
            ),
        )
        return ToolRequest(SYSTEM_PROMPT, content, tool())
    }

    fun fromToolInput(input: JSONObject): ParsedVocabulary {
        val items = ArrayList<ParsedItem>()
        val arr = input.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val en = o.optString("en").trim()
            val ru = o.optString("ru").trim()
            if (en.isEmpty() && ru.isEmpty()) continue
            items += ParsedItem(en, ru, o.optString("kind", "word").ifBlank { "word" })
        }
        if (items.isEmpty()) throw ClaudeException("На фото не нашлось словарных пар. Попробуй снять ближе и ровнее.")
        return ParsedVocabulary(input.optString("title").trim().ifBlank { "Новые слова" }, items)
    }

    private fun tool(): JSONObject {
        val item = ClaudeApi.objectSchema(
            mapOf(
                "en" to ClaudeApi.stringProp("Английское слово или фраза ровно так, как напечатано, с артиклем и «to»"),
                "ru" to ClaudeApi.stringProp("Основной русский перевод, одно значение"),
                "kind" to JSONObject().put("type", "string").put("enum", JSONArray().put("word").put("phrase")),
            ),
        )
        val schema = ClaudeApi.objectSchema(
            mapOf(
                "title" to ClaudeApi.stringProp("Короткое название списка, например 'Lesson 3 — The Red Hen'"),
                "items" to ClaudeApi.arrayOf(item),
            ),
        )
        return ClaudeApi.tool(TOOL_NAME, "Сохранить распознанный список словарных пар", schema)
    }

    companion object {
        const val TOOL_NAME = "save_vocabulary"

        private val SYSTEM_PROMPT = """
            Ты помогаешь папе и семилетнему сыну, который учит английский во 2 классе российской школы и тренирует
            письмо прописными буквами. На фото — страница словаря из учебника (Active Vocabulary): левая колонка
            английское слово с транскрипцией, правая — русский перевод. Задача: извлечь пары для тренировки письма.

            Правила:
            - en: английское слово ровно так, как оно напечатано в левой колонке, включая артикль и частицу «to»
              (a hen, a goose, to plant, work). Ребёнок пишет слово вместе с артиклем, и буквы считаются вместе с ним.
              Убирай только транскрипцию в квадратных скобках и пометки в круглых скобках.
            - Если отдельно указана форма (например, plural: geese), делай отдельную пару (geese — гуси).
            - ru: одно основное значение, первое из перечисленных. Без пометок вроде «(неисчисляемое сущ.)»,
              без транскрипции, без скобок. Для множественного числа бери слово из скобок, если оно там.
            - kind: "word" для отдельных слов, "phrase" для выражений и предложений (их тоже включай, с пунктуацией).
            - Пропускай заголовки разделов (NOUNS, VERBS, ADJECTIVES и т.п.), колонтитулы и номера страниц.
            - Сохраняй порядок как на странице. Несколько фото — это одна страница за другой.
            - title: короткое название списка по заголовку страницы (например, "Lesson 3 — The Red Hen").
        """.trimIndent()
    }
}
