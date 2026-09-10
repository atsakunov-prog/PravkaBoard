package ru.tsakunov.pravka.domain

import org.json.JSONArray
import org.json.JSONObject

/** Один пункт упражнения: что написал Боря и что с этим. */
data class HwItem(
    val label: String,
    val answer: String,
    /** correct | wrong | missing */
    val status: String,
    val expected: String,
    val hint: String,
) {
    val ok: Boolean get() = status == STATUS_CORRECT
    val missing: Boolean get() = status == STATUS_MISSING

    companion object {
        const val STATUS_CORRECT = "correct"
        const val STATUS_WRONG = "wrong"
        const val STATUS_MISSING = "missing"
    }
}

data class HwExercise(val name: String, val instruction: String, val items: List<HwItem>)

data class HomeworkResult(val title: String, val exercises: List<HwExercise>) {
    val total: Int get() = exercises.sumOf { it.items.size }
    val correct: Int get() = exercises.sumOf { ex -> ex.items.count { it.ok } }
    val allCorrect: Boolean get() = total > 0 && correct == total

    fun toJson(): String {
        val root = JSONObject().put("title", title)
        val exs = JSONArray()
        for (ex in exercises) {
            val items = JSONArray()
            for (it in ex.items) {
                items.put(
                    JSONObject().put("label", it.label).put("answer", it.answer).put("status", it.status)
                        .put("expected", it.expected).put("hint", it.hint),
                )
            }
            exs.put(JSONObject().put("name", ex.name).put("instruction", ex.instruction).put("items", items))
        }
        root.put("exercises", exs)
        return root.toString()
    }

    companion object {
        fun fromJson(text: String): HomeworkResult = fromJsonObject(JSONObject(text))

        fun fromJsonObject(root: JSONObject): HomeworkResult {
            val exercises = ArrayList<HwExercise>()
            val exs = root.optJSONArray("exercises") ?: JSONArray()
            for (i in 0 until exs.length()) {
                val ex = exs.optJSONObject(i) ?: continue
                val items = ArrayList<HwItem>()
                val arr = ex.optJSONArray("items") ?: JSONArray()
                for (j in 0 until arr.length()) {
                    val o = arr.optJSONObject(j) ?: continue
                    val status = when (o.optString("status").lowercase()) {
                        HwItem.STATUS_CORRECT -> HwItem.STATUS_CORRECT
                        HwItem.STATUS_MISSING -> HwItem.STATUS_MISSING
                        else -> HwItem.STATUS_WRONG
                    }
                    items += HwItem(
                        label = o.optString("label").ifBlank { (j + 1).toString() },
                        answer = o.optString("answer"),
                        status = status,
                        expected = o.optString("expected"),
                        hint = o.optString("hint"),
                    )
                }
                if (items.isNotEmpty()) exercises += HwExercise(ex.optString("name").ifBlank { "Упражнение ${i + 1}" }, ex.optString("instruction"), items)
            }
            return HomeworkResult(root.optString("title").ifBlank { "Домашнее задание" }, exercises)
        }
    }
}
