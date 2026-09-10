package ru.tsakunov.pravka.api

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import ru.tsakunov.pravka.domain.HomeworkResult

/** Проверка домашки по фото: что верно, что нет, а после второй попытки — почему. */
class ClaudeHomework(private val context: Context, private val api: ClaudeApi) {

    suspend fun check(images: List<Uri>, previous: HomeworkResult?, apiKey: String, model: String): HomeworkResult {
        if (images.isEmpty()) throw ClaudeException("Нет фотографий")
        val content = JSONArray()
        for (uri in images) content.put(ClaudeApi.imageBlock(Images.encodeJpegBase64(context, uri)))
        val ask = buildString {
            append("На фото — страницы рабочей тетради с выполненным домашним заданием. Проверь его и сохрани результат инструментом report_homework_check.")
            if (previous != null) {
                append("\n\nЭто повторная проверка после исправлений. Прошлый результат:\n")
                for (ex in previous.exercises) {
                    append("• ").append(ex.name).append('\n')
                    for (it in ex.items) append("   ").append(it.label).append(": ").append(it.answer.ifBlank { "(пусто)" }).append(" — ").append(it.status).append('\n')
                }
                append("\nПроверь все пункты заново по новому фото (ребёнок мог исправить), сохрани те же названия упражнений и label.")
            }
        }
        content.put(ClaudeApi.textBlock(ask))
        val input = api.callTool(apiKey, model, SYSTEM_PROMPT, content, tool())
        val result = HomeworkResult.fromJsonObject(input)
        if (result.total == 0) throw ClaudeException("На фото не нашлось выполненных заданий. Сними страницу целиком, чтобы были видны и задание, и ответы.")
        return result
    }

    private fun tool(): JSONObject {
        val item = ClaudeApi.objectSchema(
            mapOf(
                "label" to ClaudeApi.stringProp("Номер пункта как напечатан: 1, 2, 3a…"),
                "answer" to ClaudeApi.stringProp("Что написал ребёнок, как прочитано; пустая строка, если не написано"),
                "status" to JSONObject().put("type", "string").put("enum", JSONArray().put("correct").put("wrong").put("missing")),
                "expected" to ClaudeApi.stringProp("Правильный ответ"),
                "hint" to ClaudeApi.stringProp("1–2 фразы по-русски для семилетнего: какое правило и почему так. Для верных пунктов — пустая строка"),
            ),
        )
        val exercise = ClaudeApi.objectSchema(
            mapOf(
                "name" to ClaudeApi.stringProp("Название упражнения как напечатано, например Exercise 1.1"),
                "instruction" to ClaudeApi.stringProp("Коротко, что нужно было сделать"),
                "items" to ClaudeApi.arrayOf(item),
            ),
        )
        val schema = ClaudeApi.objectSchema(
            mapOf(
                "title" to ClaudeApi.stringProp("Название домашки, например «Lesson 2, Home assignment 2»"),
                "exercises" to ClaudeApi.arrayOf(exercise),
            ),
        )
        return ClaudeApi.tool(TOOL_NAME, "Сохранить результат проверки домашнего задания", schema)
    }

    companion object {
        const val TOOL_NAME = "report_homework_check"
        private val SYSTEM_PROMPT = """
            Ты проверяешь домашнее задание по английскому у семилетнего мальчика Бори, 2 класс российской школы.
            На фото — страницы рабочей тетради: напечатанное задание и его ответы карандашом или ручкой.

            Как проверять:
            - Прочитай инструкцию каждого упражнения и проверяй ответы строго по ней. Если задание «запиши, если это
              возможно», прочерк или пустая строка у неисчисляемого существительного (porridge, money, food) — верный ответ.
            - Включай упражнение в отчёт только если в нём написан хотя бы один ответ. Внутри такого упражнения
              пустые пункты отмечай status = missing.
            - Почерк детский: буквы неровные, могут наезжать. Если сомнение только в почерке, а само слово написано
              правильно, ставь correct. Если буква пропущена, лишняя или неверная — wrong.
            - Верный ответ с другой допустимой формой (например, the вместо a там, где оба уместны) считай correct,
              если он не противоречит смыслу задания.
            - expected: правильный ответ. hint: одна-две фразы по-русски, понятные семилетнему, объясняющие правило
              (например: «После согласной y меняется на ies: baby → babies»). Для correct — пустая строка.
            - Порядок упражнений и пунктов — как на странице. label — номер как напечатан.
        """.trimIndent()
    }
}
