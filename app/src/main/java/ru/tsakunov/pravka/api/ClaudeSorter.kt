package ru.tsakunov.pravka.api

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import ru.tsakunov.pravka.domain.IntakeKind

/** Одна страница домашки и то, что на ней нашлось. */
data class SortedPage(val index: Int, val kinds: Set<IntakeKind>, val note: String)
data class SortedPages(val title: String, val pages: List<SortedPage>)

/** Раскладывает фото всей домашки по вкладкам: словарь, правило, упражнения, текст для чтения. */
class ClaudeSorter(private val context: Context, private val api: ClaudeApi) {

    suspend fun sort(images: List<Uri>, apiKey: String, model: String): SortedPages {
        if (images.isEmpty()) throw ClaudeException("Нет фотографий")
        val content = JSONArray()
        images.forEachIndexed { i, uri ->
            content.put(ClaudeApi.textBlock("Фото ${i + 1}:"))
            content.put(ClaudeApi.imageBlock(Images.encodeJpegBase64(context, uri)))
        }
        content.put(ClaudeApi.textBlock("Это ${images.size} фото домашнего задания по английскому. Определи, что на каждом, и сохрани инструментом sort_pages."))
        val input = api.callTool(apiKey, model, SYSTEM_PROMPT, content, tool(), maxTokens = 3000, timeoutSec = 120)
        val arr = input.optJSONArray("pages") ?: JSONArray()
        val byIndex = HashMap<Int, SortedPage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("index", -1)
            if (idx !in 1..images.size) continue
            val kinds = o.optJSONArray("kinds") ?: JSONArray()
            val set = (0 until kinds.length()).mapNotNull { IntakeKind.fromApi(kinds.optString(it)) }.toSet()
            byIndex[idx] = SortedPage(idx, set, o.optString("note").trim())
        }
        val pages = (1..images.size).map { byIndex[it] ?: SortedPage(it, emptySet(), "") }
        return SortedPages(input.optString("title").trim().ifBlank { "Домашка" }, pages)
    }

    private fun tool(): JSONObject {
        val page = ClaudeApi.objectSchema(
            mapOf(
                "index" to ClaudeApi.intProp("Номер фото, как подписано: 1, 2, 3…"),
                "kinds" to ClaudeApi.arrayOf(
                    JSONObject().put("type", "string").put("enum", JSONArray().also { a -> IntakeKind.entries.forEach { a.put(it.api) }; a.put("other") }),
                ),
                "note" to ClaudeApi.stringProp("Коротко по-русски, что на странице: «словарь урока 4», «правило -es», «упр. 3 с ответами», «страница 12 книжки»"),
            ),
        )
        val schema = ClaudeApi.objectSchema(
            mapOf(
                "title" to ClaudeApi.stringProp("Название домашки целиком, например «Lesson 4, 12 сентября» или «Unit 2 — Farm»"),
                "pages" to ClaudeApi.arrayOf(page),
            ),
        )
        return ClaudeApi.tool(TOOL_NAME, "Сохранить, что на каждой странице домашки", schema)
    }

    companion object {
        const val TOOL_NAME = "sort_pages"
        private val SYSTEM_PROMPT = """
            Папа сфотографировал всю домашку по английскому семилетнего сына (2 класс российской школы) подряд, без
            разбора. Твоя задача — сказать, что на каждом фото, чтобы приложение отправило страницы в нужные разделы.

            Виды страниц (у одного фото может быть несколько):
            - vocabulary: словарь урока, колонки «английское слово — транскрипция — перевод» (Active Vocabulary, New words).
            - grammar: объяснение правила: рамка с правилом, таблица форм, примеры (например, множественное число, артикли, глагол to be).
            - exercise: упражнения рабочей тетради или учебника, в которых ребёнок УЖЕ написал ответы от руки.
              Незаполненные упражнения без ответов сюда не относятся (для них ставь other).
            - reading: текст для чтения: страницы книжки или учебника с рассказом, сказкой, диалогом; текст, который
              ребёнку надо прочитать вслух, а не заполнить.
            - other: обложка, пустая страница, расписание, что-то не по теме.

            Ставь один вид, если он явно главный; несколько, если на странице и правило, и упражнение с ответами.
            Каждое фото должно получить ровно одну запись с его номером. title: короткое название домашки по тому,
            что видно (номер урока, тема, дата).
        """.trimIndent()
    }
}
