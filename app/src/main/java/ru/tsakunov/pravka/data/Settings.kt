package ru.tsakunov.pravka.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Метрика скорости на графиках. */
enum class Metric { SEC_PER_LETTER, LETTERS_PER_MIN }

data class SettingsState(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val metric: Metric = Metric.SEC_PER_LETTER,
) {
    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
    }
}

class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pravka_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state

    private fun read() = SettingsState(
        apiKey = prefs.getString(KEY_API, "") ?: "",
        model = prefs.getString(KEY_MODEL, SettingsState.DEFAULT_MODEL)?.ifBlank { SettingsState.DEFAULT_MODEL }
            ?: SettingsState.DEFAULT_MODEL,
        metric = if (prefs.getString(KEY_METRIC, "spl") == "lpm") Metric.LETTERS_PER_MIN else Metric.SEC_PER_LETTER,
    )

    fun setApiKey(value: String) {
        prefs.edit().putString(KEY_API, value.trim()).apply()
        _state.value = read()
    }

    fun setModel(value: String) {
        prefs.edit().putString(KEY_MODEL, value.trim()).apply()
        _state.value = read()
    }

    fun setMetric(metric: Metric) {
        prefs.edit().putString(KEY_METRIC, if (metric == Metric.LETTERS_PER_MIN) "lpm" else "spl").apply()
        _state.value = read()
    }

    var seeded: Boolean
        get() = prefs.getBoolean(KEY_SEEDED, false)
        set(v) = prefs.edit().putBoolean(KEY_SEEDED, v).apply()

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_METRIC = "metric"
        const val KEY_SEEDED = "seeded_v1"
    }
}
