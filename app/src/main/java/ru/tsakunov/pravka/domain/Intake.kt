package ru.tsakunov.pravka.domain

import org.json.JSONArray
import org.json.JSONObject

/** Раздел приложения, в который уходит страница домашки. */
enum class IntakeKind(val api: String, val label: String) {
    VOCABULARY("vocabulary", "Слова"),
    GRAMMAR("grammar", "Грамматика"),
    EXERCISE("exercise", "Домашка"),
    READING("reading", "Текст");

    companion object {
        fun fromApi(s: String): IntakeKind? = entries.firstOrNull { it.api == s }
    }
}

/** Одна часть разбора: страницы одного вида и что из них получилось. */
data class IntakePart(
    val kind: IntakeKind,
    val pages: List<Int>,
    val customId: String,
    /** pending, done или error. */
    val status: String = PENDING,
    /** id созданного урока, темы, домашки или текста. */
    val targetId: String? = null,
    val title: String? = null,
    val error: String? = null,
) {
    val done: Boolean get() = status == DONE
    val failed: Boolean get() = status == ERROR

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.api).put("pages", JSONArray(pages)).put("customId", customId).put("status", status)
        .put("targetId", targetId ?: JSONObject.NULL).put("title", title ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)

    companion object {
        const val PENDING = "pending"
        const val DONE = "done"
        const val ERROR = "error"

        fun fromJson(o: JSONObject): IntakePart? {
            val kind = IntakeKind.fromApi(o.optString("kind")) ?: return null
            val pages = o.optJSONArray("pages")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
            return IntakePart(
                kind = kind, pages = pages, customId = o.optString("customId"), status = o.optString("status", PENDING),
                targetId = o.optString("targetId").takeIf { it.isNotBlank() && it != "null" },
                title = o.optString("title").takeIf { it.isNotBlank() && it != "null" },
                error = o.optString("error").takeIf { it.isNotBlank() && it != "null" },
            )
        }

        fun listToJson(parts: List<IntakePart>): String = JSONArray().also { a -> parts.forEach { a.put(it.toJson()) } }.toString()
        fun listFromJson(text: String): List<IntakePart> = runCatching {
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { fromJson(it) }
        }.getOrDefault(emptyList())
    }
}
