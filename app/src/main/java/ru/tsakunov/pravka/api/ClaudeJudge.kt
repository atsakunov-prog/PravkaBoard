package ru.tsakunov.pravka.api

import org.json.JSONArray

/** Судья для контрольной: принимает ли расшифровка речи ответ ребёнка как верный перевод. */
class ClaudeJudge(private val api: ClaudeApi) {

    suspend fun accept(ru: String, expectedEn: String, heard: List<String>, apiKey: String, model: String): Boolean {
        val ask = buildString {
            append("Русская фраза: «").append(ru).append("».\n")
            append("Ожидаемый английский перевод из учебника: «").append(expectedEn).append("».\n")
            append("Ребёнок произнёс, варианты распознавания речи:\n")
            heard.forEach { append("- ").append(it).append('\n') }
            append("\nПринять ли ответ? Вызови инструмент judge.")
        }
        val input = api.callTool(apiKey, model, SYSTEM_PROMPT, JSONArray().put(ClaudeApi.textBlock(ask)), tool(), maxTokens = 500)
        return input.optBoolean("correct", false)
    }

    private fun tool() = ClaudeApi.tool(
        TOOL_NAME,
        "Вердикт по ответу ребёнка",
        ClaudeApi.objectSchema(
            mapOf(
                "correct" to ClaudeApi.boolProp("true, если смысл фразы передан верно"),
                "reason" to ClaudeApi.stringProp("Одна короткая фраза по-русски, почему"),
            ),
        ),
    )

    companion object {
        const val TOOL_NAME = "judge"
        private val SYSTEM_PROMPT = """
            Ты проверяешь устный ответ семилетнего ребёнка на контрольной по английским словам. Ему показали русскую
            фразу, он сказал её по-английски вслух, а распознавание речи выдало несколько вариантов текста.
            Принимай ответ, если хотя бы один вариант передаёт смысл фразы: допускаются пропущенный или лишний артикль,
            другой порядок слов, синоним того же уровня, ошибки распознавания похожих по звучанию слов
            (hen/hand, seed/seat). Не принимай, если сказано другое слово по смыслу или фраза не закончена.
        """.trimIndent()
    }
}
