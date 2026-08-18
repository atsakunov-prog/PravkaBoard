// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.pravka

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// Direct Anthropic Messages API client, ported from the Pravka app: SSE
// streaming, prompt caching on the stable CLEAN prefix, one retry on
// transient failures. The API key is entered by the owner on the device and
// lives only in this app's preferences.
object PravkaApi {

    const val MODEL_SONNET = "claude-sonnet-5"
    const val MODEL_OPUS = "claude-opus-5"   // redo chips only

    class ApiException(message: String, val retryable: Boolean = false) : Exception(message)

    /** A finished fix plus its cost accounting (journaled to the history). */
    data class FixResult(
        val text: String,
        val model: String,
        val latencyMs: Long,
        val inputTokens: Int,
        val cacheWriteTokens: Int,
        val cacheReadTokens: Int,
        val outputTokens: Int,
    ) {
        val costUsd: Double get() {
            val (pIn, pOut) = when (model) {
                MODEL_OPUS -> 5.0 to 25.0
                else -> 3.0 to 15.0
            }
            return (inputTokens + 2.0 * cacheWriteTokens + 0.1 * cacheReadTokens) / 1_000_000.0 * pIn +
                outputTokens / 1_000_000.0 * pOut
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Proofreads [input]; blocking, call from a background dispatcher.
     * [onDelta] receives the ACCUMULATED reply as it streams in (display only).
     */
    fun proofread(
        apiKey: String,
        input: String,
        directive: String = "",
        contextBefore: String = "",
        model: String = MODEL_SONNET,
        onDelta: ((String) -> Unit)? = null,
        dictBlock: String = "",
    ): Result<String> = proofreadFull(apiKey, input, directive, contextBefore, model, onDelta, dictBlock).map { it.text }

    /**
     * Free-form assist task (summarize / reply / translate): [instruction]
     * plus the [content] in tags, NO CLEAN template. Blocking, IO thread.
     */
    fun assist(
        apiKey: String,
        instruction: String,
        content: String,
        model: String = MODEL_SONNET,
        onDelta: ((String) -> Unit)? = null,
    ): Result<FixResult> = runCatching {
        if (apiKey.isBlank()) throw ApiException("Не задан API-ключ: Настройки клавиатуры → Правка.")
        val prompt = instruction.trim() + "\n\n<текст>\n" + content + "\n</текст>"
        val parts = PravkaPrompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
        val started = System.currentTimeMillis()
        val r = try {
            request(apiKey, model, parts, "", onDelta)
        } catch (e: IOException) {
            request(apiKey, model, parts, "", onDelta)
        } catch (e: ApiException) {
            if (!e.retryable) throw e
            Thread.sleep(1500)
            request(apiKey, model, parts, "", onDelta)
        }
        r.copy(model = model, latencyMs = System.currentTimeMillis() - started)
    }

    /** Like [proofread], but returns the full accounting for the journal. */
    fun proofreadFull(
        apiKey: String,
        input: String,
        directive: String = "",
        contextBefore: String = "",
        model: String = MODEL_SONNET,
        onDelta: ((String) -> Unit)? = null,
        dictBlock: String = "",
        cleanTemplate: String = PravkaPrompts.CLEAN,
    ): Result<FixResult> = runCatching {
        if (apiKey.isBlank()) throw ApiException("Не задан API-ключ: Настройки клавиатуры → Правка.")
        val parts = PravkaPrompts.assemble(cleanTemplate, dictBlock, directive, contextBefore)
        val started = System.currentTimeMillis()
        val r = try {
            request(apiKey, model, parts, input, onDelta)
        } catch (e: IOException) {
            request(apiKey, model, parts, input, onDelta)
        } catch (e: ApiException) {
            if (!e.retryable) throw e
            Thread.sleep(1500)
            request(apiKey, model, parts, input, onDelta)
        }
        r.copy(model = model, latencyMs = System.currentTimeMillis() - started)
    }

    private fun request(
        apiKey: String,
        model: String,
        parts: PravkaPrompts.PromptParts,
        input: String,
        onDelta: ((String) -> Unit)?,
    ): FixResult {
        // Count the variable slot too: assist tasks carry their content there.
        val estimatedInputTokens = (input.length + parts.dictPart.length) / 2 + 1
        // Opus thinks adaptively and thinking tokens count toward max_tokens.
        val thinkingHeadroom = if (model == MODEL_SONNET) 0 else 8000
        val maxTokens = (estimatedInputTokens * 13 / 10 + 300 + thinkingHeadroom)
            .coerceIn(1024, 16384)

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("stream", true)
            if (model == MODEL_SONNET) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray().apply {
                                // Assist tasks have no stable prefix - an empty
                                // text block would be rejected by the API.
                                if (parts.stablePrefix.isNotBlank()) put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", parts.stablePrefix)
                                        if (model == MODEL_SONNET) {
                                            put(
                                                "cache_control",
                                                JSONObject().put("type", "ephemeral").put("ttl", "1h"),
                                            )
                                        }
                                    }
                                )
                                put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", parts.dictPart + input + parts.afterInput)
                                    }
                                )
                            }
                        )
                    }
                )
            )
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                val transient = response.code == 429 || response.code in 500..599
                throw ApiException(humanReadableError(response.code, responseBody), retryable = transient)
            }
            val source = response.body?.source() ?: throw ApiException("Пустой ответ API.")
            val sb = StringBuilder()
            var stopReason = ""
            var lastEmit = 0L
            var inputTokens = 0
            var cacheWrite = 0
            var cacheRead = 0
            var outputTokens = 0
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val event = runCatching { JSONObject(line.substring(6)) }.getOrNull() ?: continue
                when (event.optString("type")) {
                    "message_start" -> {
                        val usage = event.optJSONObject("message")?.optJSONObject("usage")
                        inputTokens = usage?.optInt("input_tokens") ?: 0
                        cacheWrite = usage?.optInt("cache_creation_input_tokens") ?: 0
                        cacheRead = usage?.optInt("cache_read_input_tokens") ?: 0
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta")
                        if (delta?.optString("type") == "text_delta") {
                            sb.append(delta.optString("text"))
                            if (onDelta != null) {
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 100) {
                                    lastEmit = now
                                    onDelta(sb.toString())
                                }
                            }
                        }
                    }
                    "message_delta" -> {
                        event.optJSONObject("delta")?.optString("stop_reason")
                            ?.takeIf { it.isNotEmpty() }?.let { stopReason = it }
                        event.optJSONObject("usage")?.let { outputTokens = it.optInt("output_tokens", outputTokens) }
                    }
                    "error" -> {
                        val err = event.optJSONObject("error")
                        val overloaded = err?.optString("type") == "overloaded_error"
                        throw ApiException(
                            "Anthropic: ${err?.optString("message") ?: "ошибка стрима"}",
                            retryable = overloaded,
                        )
                    }
                }
            }
            when (stopReason) {
                "end_turn", "stop_sequence" -> Unit
                "max_tokens" -> throw ApiException("Ответ модели обрезан по длине. Попробуй ещё раз или сократи текст.")
                "refusal" -> throw ApiException("Модель отказалась обрабатывать этот текст.")
                else -> throw ApiException("Неожиданный ответ модели ($stopReason).")
            }
            if (sb.isEmpty()) throw ApiException("Модель вернула пустой ответ.")
            onDelta?.invoke(sb.toString())
            return FixResult(
                text = cleanReply(sb.toString()),
                model = model, latencyMs = 0,
                inputTokens = inputTokens, cacheWriteTokens = cacheWrite,
                cacheReadTokens = cacheRead, outputTokens = outputTokens,
            )
        }
    }

    // ---- dictionary miner (ported from the Pravka app's DictMiner): finds
    // RECURRING recognition errors in the history worth a permanent entry.
    // Manual action from the dictionary screen; nothing is added automatically.

    data class DictSuggestion(
        val mode: PravkaStore.DictMode,
        val from: String,   // what recognition produces
        val to: String,     // what it should be ("" for PROTECT)
        val note: String,   // evidence, goes into the entry's note
    )

    fun mineDictionary(
        apiKey: String,
        pairs: List<Pair<String, String>>,
    ): Result<List<DictSuggestion>> = runCatching {
        require(apiKey.isNotBlank()) { "Не задан API-ключ." }
        require(pairs.isNotEmpty()) { "История правок пуста." }

        val samples = JSONArray()
        for ((input, output) in pairs) {
            samples.put(JSONObject().put("dictated", input).put("fixed", output))
        }
        val prompt = """
Ниже пары из истории диктовок: "dictated" — что выдало распознавание
речи, "fixed" — что в итоге исправила модель. Найди ПОВТОРЯЮЩИЕСЯ
ошибки распознавания, которые стоит закрепить словарной записью:
имена, названия, термины, которые распознавание стабильно пишет
неправильно. Одноразовые ошибки не предлагай.

Ответ — СТРОГО JSON-массив без пояснений и разметки, каждый элемент:
{"mode": "HARD" | "PROTECT", "from": "...", "to": "...", "note": "..."}
HARD: from — неправильная форма, to — правильная (автозамена).
PROTECT: from — правильное редкое слово, to — пустая строка
(защита от "исправления"). note — краткое обоснование по-русски.
Не больше 12 предложений. Если ничего повторяющегося нет — верни [].

$samples
""".trimIndent()

        val body = JSONObject().apply {
            put("model", MODEL_SONNET)
            put("max_tokens", 2048)
            put("thinking", JSONObject().put("type", "disabled"))
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            require(response.isSuccessful) { humanReadableError(response.code, text) }
            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            parseSuggestions(sb.toString())
        }
    }

    private fun parseSuggestions(raw: String): List<DictSuggestion> {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = JSONArray(text.substring(start, end + 1))
        val out = mutableListOf<DictSuggestion>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val mode = runCatching { PravkaStore.DictMode.valueOf(o.optString("mode")) }.getOrNull() ?: continue
            if (mode == PravkaStore.DictMode.HINT) continue  // miner only proposes HARD/PROTECT
            val from = o.optString("from").trim()
            if (from.isEmpty()) continue
            out.add(DictSuggestion(mode, from, o.optString("to").trim(), o.optString("note").trim()))
        }
        return out
    }

    // Strips accidental markdown fences / wrapping quotes, like Pravka's
    // ResponseCleaner (lenient: no length gate - directives legally change it).
    private fun cleanReply(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```") && text.endsWith("```")) {
            text = text.removePrefix("```").removeSuffix("```").trim()
        }
        if (text.isEmpty()) throw ApiException("Пустой результат после чистки ответа.")
        return text
    }

    private fun humanReadableError(code: Int, body: String): String {
        val serverMessage = runCatching {
            JSONObject(body).getJSONObject("error").getString("message")
        }.getOrNull()
        return when (code) {
            401 -> "Неверный API-ключ."
            403 -> "У ключа нет доступа к модели." + (serverMessage?.let { " ($it)" } ?: "")
            404 -> "Модель не найдена." + (serverMessage?.let { " ($it)" } ?: "")
            429 -> "Слишком много запросов, подожди немного."
            in 500..599 -> "Сервер Anthropic недоступен ($code), попробуй позже."
            else -> "Ошибка API $code" + (serverMessage?.let { ": $it" } ?: "")
        }
    }
}
