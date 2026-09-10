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

    /** Версия посева бумажной статистики; 0 — ещё не сеяли. Старый флаг seeded_v1 считается версией 1. */
    var seedVersion: Int
        get() = prefs.getInt(KEY_SEED_VERSION, if (prefs.getBoolean(KEY_SEEDED_V1, false)) 1 else 0)
        set(v) = prefs.edit().putInt(KEY_SEED_VERSION, v).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()

    private companion object {
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_METRIC = "metric"
        const val KEY_SEEDED_V1 = "seeded_v1"
        const val KEY_SEED_VERSION = "seed_version"
    }
}
