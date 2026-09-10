package ru.tsakunov.pravka.api

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class ExtractedText(val title: String, val textEn: String, val textRu: String)

/** Страницы книжки → текст для чтения на время плюс перевод. */
class ClaudeReading(private val context: Context, private val api: ClaudeApi) {

    suspend fun extract(images: List<Uri>, apiKey: String, model: String): ExtractedText =
        fromToolInput(api.callTool(apiKey, model, request(images)))

    fun fromToolInput(input: JSONObject): ExtractedText {
        val en = input.optString("text_en").trim()
        if (en.isEmpty()) throw ClaudeException("На фото не нашлось текста. Сними страницу ближе.")
        return ExtractedText(input.optString("title").trim().ifBlank { "Текст" }, en, input.optString("text_ru").trim())
    }

    /** Запрос без отправки: для пакетной обработки. */
    suspend fun request(images: List<Uri>): ToolRequest {
        if (images.isEmpty()) throw ClaudeException("Нет фотографий")
        val content = JSONArray()
        for (uri in images) content.put(ClaudeApi.imageBlock(Images.encodeJpegBase64(context, uri)))
        content.put(ClaudeApi.textBlock("На фото — страницы детской книжки на английском. Перепиши текст для чтения вслух и сохрани инструментом save_reading_text."))
        return ToolRequest(SYSTEM_PROMPT, content, tool())
    }

    private fun tool() = ClaudeApi.tool(
        TOOL_NAME,
        "Сохранить текст страниц для чтения",
        ClaudeApi.objectSchema(
            mapOf(
                "title" to ClaudeApi.stringProp("Название книги и страницы, например «The Little Red Hen, с. 3–4»"),
                "text_en" to ClaudeApi.stringProp("Текст страниц по порядку, абзацы через пустую строку"),
                "text_ru" to ClaudeApi.stringProp("Русский перевод, абзацы соответствуют английским"),
            ),
        ),
    )

    companion object {
        const val TOOL_NAME = "save_reading_text"
        private val SYSTEM_PROMPT = """
            Ты переписываешь текст со страниц детской книжки на английском (уровень Level 1, ребёнку 7 лет) для
            чтения вслух на время.
            - Основной текст страниц дословно, в порядке чтения (левая страница, затем правая). Реплики в облачках
              включай отдельной строкой в кавычках там, где они по смыслу. Номера страниц и колонтитулы не включай.
            - Не исправляй и не упрощай текст книги. Абзацы отделяй пустой строкой.
            - text_ru: живой русский перевод для ребёнка, абзацы соответствуют английским.
            - title: название книги и номера страниц, если видны.
        """.trimIndent()
    }
}
