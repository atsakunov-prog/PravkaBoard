package ru.tsakunov.pravka.api

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import ru.tsakunov.pravka.domain.GrammarSetContent

/** Страница с правилом → уровни тренажёра с карточками «слово → форма». */
class ClaudeGrammar(private val context: Context, private val api: ClaudeApi) {

    suspend fun build(images: List<Uri>, apiKey: String, model: String): GrammarSetContent =
        fromToolInput(api.callTool(apiKey, model, request(images)))

    fun fromToolInput(input: JSONObject): GrammarSetContent {
        val set = GrammarSetContent.fromJsonObject(input)
        if (set.rules.isEmpty()) throw ClaudeException("На фото не нашлось правила с примерами. Сними страницу с правилом целиком.")
        return set
    }

    /** Запрос без отправки: для пакетной обработки. */
    suspend fun request(images: List<Uri>): ToolRequest {
        if (images.isEmpty()) throw ClaudeException("Нет фотографий")
        val content = JSONArray()
        for (uri in images) content.put(ClaudeApi.imageBlock(Images.encodeJpegBase64(context, uri)))
        content.put(ClaudeApi.textBlock("На фото — страница учебника с грамматическим правилом. Разбери его на уровни тренажёра и сохрани инструментом save_grammar_set."))
        return ToolRequest(SYSTEM_PROMPT, content, tool())
    }

    private fun tool(): JSONObject {
        val drill = ClaudeApi.objectSchema(
            mapOf(
                "prompt" to ClaudeApi.stringProp("Что показать ребёнку, например исходное слово: box"),
                "answer" to ClaudeApi.stringProp("Правильный ответ, например: boxes"),
                "note" to ClaudeApi.stringProp("Коротко по-русски, почему так; может быть пустым"),
            ),
        )
        val rule = ClaudeApi.objectSchema(
            mapOf(
                "name" to ClaudeApi.stringProp("Короткое название уровня по-русски, например «Просто добавь -s»"),
                "explanation" to ClaudeApi.stringProp("Правило одной-двумя фразами по-русски для семилетнего"),
                "examples" to ClaudeApi.arrayOf(drill),
                "drills" to ClaudeApi.arrayOf(drill),
            ),
        )
        val schema = ClaudeApi.objectSchema(
            mapOf(
                "title" to ClaudeApi.stringProp("Название темы, например «Множественное число»"),
                "rules" to ClaudeApi.arrayOf(rule),
            ),
        )
        return ClaudeApi.tool(TOOL_NAME, "Сохранить набор правил и карточек тренажёра", schema)
    }

    companion object {
        const val TOOL_NAME = "save_grammar_set"
        private val SYSTEM_PROMPT = """
            Ты превращаешь страницу учебника английского (2 класс российской школы, ребёнку 7 лет) в тренажёр.
            Каждое правило или его случай со страницы — отдельный уровень, в порядке как на странице (от простого к
            сложному). Для правила множественного числа это, например: «просто -s», «-es после -s, -sh, -ch, -x, -z»,
            «согласная + y → -ies», «гласная + y → -s», «слова на -o», «-f/-fe → -ves», «особые слова».

            Для каждого уровня:
            - name: короткое название по-русски; explanation: правило одной-двумя фразами для семилетнего.
            - examples: 2–4 пары прямо со страницы (prompt — исходное слово, answer — форма).
            - drills: 8–12 карточек. Возьми примеры со страницы и добавь новые простые слова, которые семилетний
              точно знает (cat, dog, book, bus, fox, dish, city, boy, baby, tomato, wolf, tooth и т.п.). Каждая карточка
              должна подчиняться именно этому правилу. Никаких редких слов.
            - note: почему так, коротко по-русски; для очевидных можно пустую строку.
            Особые слова (children, men, mice…) — отдельный уровень, только те, что есть на странице.
        """.trimIndent()
    }
}
