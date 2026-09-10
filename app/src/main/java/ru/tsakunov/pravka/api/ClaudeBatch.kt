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

/** Состояние пакета Message Batches API. */
data class BatchStatus(
    val id: String,
    val processingStatus: String,
    val processing: Int,
    val succeeded: Int,
    val errored: Int,
    val canceled: Int,
    val expired: Int,
    val resultsUrl: String?,
) {
    val ended: Boolean get() = processingStatus == "ended"
    val total: Int get() = processing + succeeded + errored + canceled + expired
}

/** Итог одного запроса пакета: input инструмента или причина неудачи. */
sealed interface BatchItem {
    class Ok(val input: JSONObject) : BatchItem
    class Failed(val message: String) : BatchItem
}

/**
 * Пакетная обработка (Message Batches): те же запросы, что и обычные, но вдвое дешевле и с ответом
 * не сразу (обычно минуты, по договору до суток). Пакет создаётся, потом опрашивается, потом скачиваются итоги.
 */
class ClaudeBatch(private val api: ClaudeApi) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    /** Создаёт пакет и возвращает его id. requests: custom_id → запрос. */
    suspend fun create(apiKey: String, model: String, requests: List<Pair<String, ToolRequest>>): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ClaudeException("Сначала вставь API-ключ Anthropic в настройках")
        if (requests.isEmpty()) throw ClaudeException("Пустой пакет")
        // Серверный fallback в пакетах не поддерживается, поэтому сразу без него; tool_choice при отказе — auto.
        var withFallbacks = false
        var forceTool = true
        repeat(4) {
            val body = JSONObject().put(
                "requests",
                JSONArray().also { arr ->
                    for ((customId, req) in requests) {
                        arr.put(JSONObject().put("custom_id", customId).put("params", api.requestBody(model, req, withFallbacks, forceTool)))
                    }
                },
            )
            val builder = baseRequest(apiKey, "$BASE/v1/messages/batches").post(body.toString().toRequestBody(JSON))
            if (withFallbacks) builder.header("anthropic-beta", ClaudeApi.FALLBACK_BETA)
            val (code, text) = execute(builder.build())
            if (code in 200..299) return@withContext JSONObject(text).getString("id")
            val lower = text.lowercase()
            when {
                code == 400 && withFallbacks && (lower.contains("fallback") || lower.contains("beta")) -> withFallbacks = false
                code == 400 && forceTool && lower.contains("tool_choice") -> forceTool = false
                else -> throw ClaudeException(api.describeError(code, text))
            }
        }
        throw ClaudeException("Не удалось создать пакет")
    }

    suspend fun status(apiKey: String, batchId: String): BatchStatus = withContext(Dispatchers.IO) {
        val (code, text) = execute(baseRequest(apiKey, "$BASE/v1/messages/batches/$batchId").get().build())
        if (code == 404) throw BatchGoneException("Пакет не найден")
        if (code !in 200..299) throw ClaudeException(api.describeError(code, text))
        val o = JSONObject(text)
        val c = o.optJSONObject("request_counts") ?: JSONObject()
        BatchStatus(
            id = o.optString("id", batchId),
            processingStatus = o.optString("processing_status"),
            processing = c.optInt("processing"), succeeded = c.optInt("succeeded"), errored = c.optInt("errored"),
            canceled = c.optInt("canceled"), expired = c.optInt("expired"),
            resultsUrl = o.optString("results_url").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /** Скачивает итоги (JSONL) и разбирает каждый: toolNames — custom_id → имя инструмента. */
    suspend fun results(apiKey: String, resultsUrl: String, toolNames: Map<String, String>): Map<String, BatchItem> = withContext(Dispatchers.IO) {
        val (code, text) = execute(baseRequest(apiKey, resultsUrl).get().build())
        if (code == 404) throw BatchGoneException("Итоги пакета больше недоступны")
        if (code !in 200..299) throw ClaudeException(api.describeError(code, text))
        val out = LinkedHashMap<String, BatchItem>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val customId = o.optString("custom_id")
            val result = o.optJSONObject("result") ?: continue
            out[customId] = when (result.optString("type")) {
                "succeeded" -> {
                    val message = result.optJSONObject("message")
                    val toolName = toolNames[customId]
                    if (message == null || toolName == null) BatchItem.Failed("Пустой ответ")
                    else try { BatchItem.Ok(api.parseToolMessage(message, toolName)) } catch (e: ClaudeException) { BatchItem.Failed(e.message ?: "Ошибка разбора") }
                }
                "errored" -> BatchItem.Failed(result.optJSONObject("error")?.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "Сервер вернул ошибку")
                "expired" -> BatchItem.Failed("Запрос не успел обработаться за сутки")
                "canceled" -> BatchItem.Failed("Запрос отменён")
                else -> BatchItem.Failed("Неизвестный статус")
            }
        }
        out
    }

    private fun baseRequest(apiKey: String, url: String) = Request.Builder()
        .url(url)
        .header("x-api-key", apiKey.trim())
        .header("anthropic-version", "2023-06-01")
        .header("content-type", "application/json")

    private fun execute(request: Request): Pair<Int, String> {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ClaudeException("Нет связи с сервером: ${e.message ?: "проверь интернет"}")
        }
        response.use { resp -> return resp.code to (resp.body?.string() ?: "") }
    }

    companion object {
        private const val BASE = "https://api.anthropic.com"
        private val JSON = "application/json".toMediaType()
    }
}
