package ru.tsakunov.pravka.api

import org.json.JSONArray
import ru.tsakunov.pravka.data.WordItem

data class GeneratedStory(val title: String, val textEn: String, val textRu: String)

/** Смешной короткий рассказ на словах урока: и почитать, и послушать. */
class ClaudeStory(private val api: ClaudeApi) {

    suspend fun generate(items: List<WordItem>, apiKey: String, model: String, previousTitle: String? = null): GeneratedStory {
        val words = items.filter { it.en.isNotBlank() }
        if (words.isEmpty()) throw ClaudeException("В уроке нет английских слов")
        val list = words.joinToString("\n") { "- ${it.en}" + if (it.ru.isNotBlank()) " (${it.ru})" else "" }
        val ask = buildString {
            append("Слова урока:\n").append(list).append("\n\n")
            append("Сочини рассказ по правилам и сохрани его инструментом save_story.")
            if (previousTitle != null) append(" Прошлый рассказ назывался «$previousTitle», сделай совсем другой сюжет.")
        }
        val input = api.callTool(apiKey, model, SYSTEM_PROMPT, JSONArray().put(ClaudeApi.textBlock(ask)), tool(), maxTokens = 8000)
        val en = input.optString("text_en").trim()
        if (en.isEmpty()) throw ClaudeException("Модель не вернула текст рассказа")
        return GeneratedStory(input.optString("title").trim().ifBlank { "Рассказ" }, en, input.optString("text_ru").trim())
    }

    private fun tool() = ClaudeApi.tool(
        TOOL_NAME,
        "Сохранить рассказ",
        ClaudeApi.objectSchema(
            mapOf(
                "title" to ClaudeApi.stringProp("Короткое смешное название на английском"),
                "text_en" to ClaudeApi.stringProp("Текст рассказа на английском, 70–110 слов, абзацы через пустую строку"),
                "text_ru" to ClaudeApi.stringProp("Перевод рассказа на русский, абзацы соответствуют английским"),
            ),
        ),
    )

    companion object {
        const val TOOL_NAME = "save_story"
        private val SYSTEM_PROMPT = """
            Ты сочиняешь короткие смешные рассказы для Бори, ему 7 лет, он учит английский во 2 классе (уровень A1,
            читал книжку The Little Red Hen). Правила рассказа:
            - 70–110 слов, 3–4 коротких абзаца, Present Simple, простые короткие предложения, знакомые слова.
            - Каждое слово из списка урока должно встретиться хотя бы один раз ровно в той форме, как дано
              (артикль можно менять на the или убирать, если так естественнее; глаголы «to ...» можно ставить в
              нужную форму, но основа слова остаётся).
            - Сюжет глупый и весёлый: неожиданный поворот, что-то смешное происходит. Можно использовать героев
              The Little Red Hen (Hen, Cat, Duck, Goose). Иногда пусть Боря сам будет героем.
            - Никакой жестокости и ничего страшного. Никаких сложных времён и длинных слов.
            - text_ru: живой русский перевод, не буквальный подстрочник, чтобы было смешно и по-русски.
        """.trimIndent()
    }
}
