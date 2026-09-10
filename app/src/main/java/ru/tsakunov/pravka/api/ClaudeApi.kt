package ru.tsakunov.pravka.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ClaudeException(message: String) : Exception(message)

/**
 * Тонкая обёртка над Messages API: один запрос с принудительным вызовом инструмента,
 * ответ — строгий JSON из tool_use.input. Используется разбором словаря, генерацией
 * рассказов, проверкой ответов и домашки.
 *
 * Запрос собирается вручную поверх OkHttp: официальный Java SDK не заявлен для Android
 * и тянет ~27 МБ зависимостей (Jackson, kotlin-reflect, jsonschema-generator).
 */
class ClaudeApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Возвращает input вызванного инструмента.
     * effort: null — по умолчанию модели, "low" — для быстрых коротких ответов (судья).
     * timeoutSec: чтение ответа; для судьи короткий, чтобы контроша не висела.
     */
    suspend fun callTool(
        apiKey: String,
        model: String,
        system: String,
        content: JSONArray,
        tool: JSONObject,
        maxTokens: Int = 16000,
        effort: String? = null,
        timeoutSec: Long = 180,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ClaudeException("Сначала вставь API-ключ Anthropic в настройках")
        val toolName = tool.getString("name")
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        // 1) fallbacks + принудительный инструмент; 2) без beta fallbacks, если она недоступна аккаунту;
        // 3) tool_choice auto, если модель (Fable 5.1) не принимает принудительный вызов.
        var withFallbacks = true
        var forceTool = true
        var text: String? = null
        for (i in 0 until 4) {
            val body = buildBody(model, system, messages, tool, maxTokens, effort, withFallbacks, forceTool)
            when (val r = send(body, apiKey, withFallbacks, forceTool, timeoutSec)) {
                is SendResult.Ok -> { text = r.body; break }
                SendResult.RetryWithoutFallbacks -> withFallbacks = false
                SendResult.RetryWithAutoTool -> forceTool = false
            }
        }
        parseToolInput(text ?: throw ClaudeException("Пустой ответ сервера"), toolName)
    }

    private sealed interface SendResult {
        class Ok(val body: String) : SendResult
        data object RetryWithoutFallbacks : SendResult
        data object RetryWithAutoTool : SendResult
    }

    private fun buildBody(
        model: String, system: String, messages: JSONArray, tool: JSONObject, maxTokens: Int,
        effort: String?, withFallbacks: Boolean, forceTool: Boolean,
    ): JSONObject {
        val body = JSONObject()
            .put("model", model.ifBlank { DEFAULT_MODEL })
            .put("max_tokens", maxTokens)
            .put("system", if (forceTool) system else "$system\n\nОтветь только вызовом инструмента ${tool.getString("name")}.")
            .put("tools", JSONArray().put(tool))
            .put("tool_choice", if (forceTool) JSONObject().put("type", "tool").put("name", tool.getString("name")) else JSONObject().put("type", "auto"))
            .put("messages", messages)
        if (effort != null) body.put("output_config", JSONObject().put("effort", effort))
        // Серверный fallback: если классификатор Opus 5 отклонит запрос, API сам перезапустит его
        // на другой модели. Если аккаунту эта beta недоступна (400), повторяем без неё.
        if (withFallbacks) body.put("fallbacks", "default")
        return body
    }

    private fun send(body: JSONObject, apiKey: String, withFallbacks: Boolean, forceTool: Boolean, timeoutSec: Long): SendResult {
        val builder = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (withFallbacks) builder.header("anthropic-beta", "server-side-fallback-2026-07-01")

        val client = if (timeoutSec == 180L) http else http.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build()
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw ClaudeException("Нет связи с сервером: ${e.message ?: "проверь интернет"}")
        }
        response.use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.isSuccessful) return SendResult.Ok(text)
            val lower = text.lowercase()
            if (resp.code == 400 && withFallbacks && (lower.contains("fallback") || lower.contains("beta"))) return SendResult.RetryWithoutFallbacks
            // Повторяем с tool_choice auto только один раз: если и он отклонён, показываем ответ сервера.
            if (resp.code == 400 && forceTool && lower.contains("tool_choice")) return SendResult.RetryWithAutoTool
            throw ClaudeException(describeError(resp.code, text))
        }
    }

    private fun describeError(code: Int, body: String): String {
        val apiMessage = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
        return when (code) {
            401 -> "API-ключ не подходит. Проверь его в настройках."
            402 -> "На аккаунте Anthropic закончились средства."
            403 -> "Ключу запрещён доступ к этой модели."
            404 -> "Модель не найдена: проверь имя модели в настройках."
            429 -> "Слишком много запросов, подожди минуту."
            500, 529 -> "Сервер Anthropic перегружен, попробуй ещё раз."
            else -> "Ошибка $code: ${apiMessage ?: body.take(200)}"
        }
    }

    private fun parseToolInput(text: String, toolName: String): JSONObject {
        val root = JSONObject(text)
        when (root.optString("stop_reason")) {
            "refusal" -> throw ClaudeException("Модель отказалась выполнять запрос. Попробуй ещё раз или переснять фото.")
            "max_tokens" -> throw ClaudeException("Ответ модели оборвался: слишком много материала за один раз.")
        }
        val blocks = root.optJSONArray("content") ?: JSONArray()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            if (b.optString("type") == "tool_use" && b.optString("name") == toolName) return b.getJSONObject("input")
        }
        // Запасной путь: если инструмент не вызван, ищем JSON в тексте.
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            if (b.optString("type") == "text") {
                val t = b.optString("text")
                val start = t.indexOf('{')
                val end = t.lastIndexOf('}')
                if (start >= 0 && end > start) runCatching { JSONObject(t.substring(start, end + 1)) }.getOrNull()?.let { return it }
            }
        }
        throw ClaudeException("Не удалось разобрать ответ модели")
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"

        fun textBlock(text: String): JSONObject = JSONObject().put("type", "text").put("text", text)

        fun imageBlock(base64Jpeg: String): JSONObject = JSONObject()
            .put("type", "image")
            .put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", base64Jpeg))

        fun stringProp(description: String? = null): JSONObject =
            JSONObject().put("type", "string").also { if (description != null) it.put("description", description) }

        fun boolProp(description: String? = null): JSONObject =
            JSONObject().put("type", "boolean").also { if (description != null) it.put("description", description) }

        fun intProp(description: String? = null): JSONObject =
            JSONObject().put("type", "integer").also { if (description != null) it.put("description", description) }

        fun objectSchema(properties: Map<String, JSONObject>, required: List<String> = properties.keys.toList()): JSONObject =
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().also { p -> properties.forEach { (k, v) -> p.put(k, v) } })
                .put("required", JSONArray(required))
                .put("additionalProperties", false)

        fun arrayOf(items: JSONObject): JSONObject = JSONObject().put("type", "array").put("items", items)

        fun tool(name: String, description: String, schema: JSONObject): JSONObject = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("strict", true)
            .put("input_schema", schema)
    }
}
