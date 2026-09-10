package ru.tsakunov.pravka.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ParsedItem(val en: String, val ru: String, val kind: String)
data class ParsedVocabulary(val title: String, val items: List<ParsedItem>)

class ClaudeException(message: String) : Exception(message)

/**
 * Разбор фотографий страницы словаря через Claude Opus.
 *
 * Запрос собирается вручную поверх OkHttp: официальный Java SDK не заявлен для Android
 * и тянет ~27 МБ зависимостей (Jackson, kotlin-reflect, jsonschema-generator), что для
 * телефона неоправданно. Формат запроса — Messages API с принудительным вызовом
 * инструмента save_vocabulary (strict), чтобы ответ гарантированно был структурированным.
 */
class ClaudeVocabParser(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun parse(images: List<Uri>, apiKey: String, model: String): ParsedVocabulary = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ClaudeException("Сначала вставь API-ключ Anthropic в настройках")
        if (images.isEmpty()) throw ClaudeException("Нет фотографий")

        val content = JSONArray()
        for (uri in images) {
            val b64 = encodeImage(uri)
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64),
                    ),
            )
        }
        content.put(JSONObject().put("type", "text").put(
            "text",
            "На фото — страница(ы) школьного словаря по английскому. Извлеки все словарные пары " +
                "и сохрани их инструментом save_vocabulary. Сохраняй порядок как на странице.",
        ))

        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val text = send(buildBody(model, messages, withFallbacks = true), apiKey, withFallbacks = true)
            ?: send(buildBody(model, messages, withFallbacks = false), apiKey, withFallbacks = false)
            ?: throw ClaudeException("Пустой ответ сервера")
        parseResponse(text)
    }

    private fun buildBody(model: String, messages: JSONArray, withFallbacks: Boolean): JSONObject {
        val body = JSONObject()
            .put("model", model.ifBlank { "claude-opus-5" })
            .put("max_tokens", 16000)
            .put("system", SYSTEM_PROMPT)
            .put("tools", JSONArray().put(saveVocabularyTool()))
            .put("tool_choice", JSONObject().put("type", "tool").put("name", TOOL_NAME))
            .put("messages", messages)
        // Серверный fallback: если классификатор Opus 5 отклонит запрос, API сам перезапустит его
        // на другой модели. Если аккаунту эта beta недоступна (400), повторяем без неё.
        if (withFallbacks) body.put("fallbacks", "default")
        return body
    }

    /** Возвращает тело успешного ответа, null — если стоит повторить без fallbacks, иначе бросает исключение. */
    private fun send(body: JSONObject, apiKey: String, withFallbacks: Boolean): String? {
        val builder = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (withFallbacks) builder.header("anthropic-beta", "server-side-fallback-2026-07-01")

        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw ClaudeException("Нет связи с сервером: ${e.message ?: "проверь интернет"}")
        }
        response.use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.isSuccessful) return text
            val lower = text.lowercase()
            if (withFallbacks && resp.code == 400 && (lower.contains("fallback") || lower.contains("beta"))) return null
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

    private fun parseResponse(text: String): ParsedVocabulary {
        val root = JSONObject(text)
        when (root.optString("stop_reason")) {
            "refusal" -> throw ClaudeException("Модель отказалась разбирать это фото. Попробуй переснять.")
            "max_tokens" -> throw ClaudeException("Ответ модели оборвался: слишком много слов на фото. Попробуй снять по одной странице.")
        }
        val blocks = root.optJSONArray("content") ?: JSONArray()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            if (b.optString("type") == "tool_use" && b.optString("name") == TOOL_NAME) {
                return fromToolInput(b.getJSONObject("input"))
            }
        }
        // Запасной путь: если инструмент не вызван, ищем JSON в тексте.
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            if (b.optString("type") == "text") {
                val t = b.optString("text")
                val start = t.indexOf('{')
                val end = t.lastIndexOf('}')
                if (start >= 0 && end > start) {
                    runCatching { JSONObject(t.substring(start, end + 1)) }.getOrNull()?.let { return fromToolInput(it) }
                }
            }
        }
        throw ClaudeException("Не удалось разобрать ответ модели")
    }

    private fun fromToolInput(input: JSONObject): ParsedVocabulary {
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

    private fun saveVocabularyTool(): JSONObject {
        val itemSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("en", JSONObject().put("type", "string").put("description", "Английское слово или фраза так, как ребёнок будет его писать"))
                    .put("ru", JSONObject().put("type", "string").put("description", "Основной русский перевод, одно значение"))
                    .put("kind", JSONObject().put("type", "string").put("enum", JSONArray().put("word").put("phrase"))),
            )
            .put("required", JSONArray().put("en").put("ru").put("kind"))
            .put("additionalProperties", false)
        val schema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("title", JSONObject().put("type", "string").put("description", "Короткое название списка, например 'Lesson 3 — The Red Hen'"))
                    .put("items", JSONObject().put("type", "array").put("items", itemSchema)),
            )
            .put("required", JSONArray().put("title").put("items"))
            .put("additionalProperties", false)
        return JSONObject()
            .put("name", TOOL_NAME)
            .put("description", "Сохранить распознанный список словарных пар")
            .put("strict", true)
            .put("input_schema", schema)
    }

    // ---- Картинки ----

    /** Уменьшает фото до MAX_SIDE по длинной стороне, поворачивает по EXIF, кодирует в JPEG base64. */
    private fun encodeImage(uri: Uri): String {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: throw ClaudeException("Не удалось открыть фото")).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ClaudeException("Файл не похож на фото")

        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565 // без альфы: вдвое меньше памяти на большое фото
        }
        var bmp = (resolver.openInputStream(uri) ?: throw ClaudeException("Не удалось открыть фото")).use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw ClaudeException("Не удалось прочитать фото")

        val rotation = resolver.openInputStream(uri)?.use { s ->
            runCatching {
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }.getOrDefault(0f)
        } ?: 0f

        val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f || rotation != 0f) {
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rotation != 0f) m.postRotate(rotation)
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        const val TOOL_NAME = "save_vocabulary"
        private const val MAX_SIDE = 2400

        private val SYSTEM_PROMPT = """
            Ты помогаешь папе и семилетнему сыну, который учит английский во 2 классе российской школы и тренирует
            письмо прописными буквами. На фото — страница словаря из учебника (Active Vocabulary): левая колонка
            английское слово с транскрипцией, правая — русский перевод. Задача: извлечь пары для тренировки письма.

            Правила:
            - en: английское слово ровно так, как его будет писать ребёнок. Убирай начальные артикли a/an/the и
              частицу «to» у глаголов (a hen → hen, to plant → plant). Убирай транскрипцию и пометки в скобках.
            - Если есть отдельно указанная форма (например, plural: geese), делай отдельную пару (geese — гуси).
            - ru: одно основное значение, первое из перечисленных. Без пометок вроде «(неисчисляемое сущ.)»,
              без транскрипции, без скобок. Для множественного числа бери слово из скобок, если оно там.
            - kind: "word" для отдельных слов, "phrase" для выражений и предложений (их тоже включай, с пунктуацией).
            - Пропускай заголовки разделов (NOUNS, VERBS, ADJECTIVES и т.п.), колонтитулы и номера страниц.
            - Сохраняй порядок как на странице. Несколько фото — это одна страница за другой.
            - title: короткое название списка по заголовку страницы (например, "Lesson 3 — The Red Hen").
        """.trimIndent()
    }
}
