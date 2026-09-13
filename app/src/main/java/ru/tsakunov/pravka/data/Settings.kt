package ru.tsakunov.pravka.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.tsakunov.pravka.domain.isEinkDevice

/** Метрика скорости на графиках. */
enum class Metric { SEC_PER_LETTER, LETTERS_PER_MIN }

data class SettingsState(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val metric: Metric = Metric.SEC_PER_LETTER,
    /** Разбор всей домашки через Message Batches: вдвое дешевле, ответ не сразу. */
    val batchMode: Boolean = false,
    /** При подключённых наушниках распознавание слушает микрофон телефона, а не наушников. */
    val phoneMic: Boolean = true,
    /** Контроша с микрофоном (true) или папа отмечает ответы кнопками (false). */
    val quizMic: Boolean = true,
    /** Режим ридера: контрастная палитра, без узора и анимаций. По умолчанию включён на электронных книгах. */
    val readerMode: Boolean = false,
    /** Масштаб интерфейса: 1.0 как на телефоне, больше — крупнее всё разом. */
    val uiScale: Float = 1f,
    /** Синхронизация через файл в ветке data репозитория PravkaBoard. */
    val syncEnabled: Boolean = false,
    /** Токен GitHub с правом Contents: read and write. Без него приложение только принимает данные. */
    val githubToken: String = "",
    val lastSyncAt: Long = 0L,
    /** Короткий итог последней синхронизации: «получено 12» или текст ошибки. */
    val lastSyncNote: String = "",
    val lastSyncOk: Boolean = true,
) {
    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
        /** Шаги масштаба в настройках. */
        val UI_SCALES = listOf(1f, 1.25f, 1.5f, 1.75f)
    }
}

class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pravka_settings", Context.MODE_PRIVATE)

    /** Электронная книга: режим ридера и крупный интерфейс включаются сами, пока Саша не решит иначе. */
    val isEink: Boolean = isEinkDevice(Build.MANUFACTURER, Build.BRAND, Build.MODEL)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state

    private fun read() = SettingsState(
        apiKey = prefs.getString(KEY_API, "") ?: "",
        model = prefs.getString(KEY_MODEL, SettingsState.DEFAULT_MODEL)?.ifBlank { SettingsState.DEFAULT_MODEL }
            ?: SettingsState.DEFAULT_MODEL,
        metric = if (prefs.getString(KEY_METRIC, "spl") == "lpm") Metric.LETTERS_PER_MIN else Metric.SEC_PER_LETTER,
        batchMode = prefs.getBoolean(KEY_BATCH, false),
        phoneMic = prefs.getBoolean(KEY_PHONE_MIC, true),
        quizMic = prefs.getBoolean(KEY_QUIZ_MIC, true),
        readerMode = prefs.getBoolean(KEY_READER_MODE, isEink),
        uiScale = prefs.getFloat(KEY_UI_SCALE, if (isEink) 1.5f else 1f).coerceIn(1f, 2f),
        syncEnabled = prefs.getBoolean(KEY_SYNC_ENABLED, false),
        githubToken = prefs.getString(KEY_GITHUB_TOKEN, "") ?: "",
        lastSyncAt = prefs.getLong(KEY_LAST_SYNC_AT, 0L),
        lastSyncNote = prefs.getString(KEY_LAST_SYNC_NOTE, "") ?: "",
        lastSyncOk = prefs.getBoolean(KEY_LAST_SYNC_OK, true),
    )

    fun setReaderMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_READER_MODE, on).apply()
        _state.value = read()
    }

    fun setUiScale(scale: Float) {
        prefs.edit().putFloat(KEY_UI_SCALE, scale.coerceIn(1f, 2f)).apply()
        _state.value = read()
    }

    fun setSyncEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_ENABLED, on).apply()
        _state.value = read()
    }

    fun setGithubToken(value: String) {
        prefs.edit().putString(KEY_GITHUB_TOKEN, value.trim()).apply()
        _state.value = read()
    }

    fun recordSync(ok: Boolean, note: String) {
        prefs.edit()
            .putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
            .putString(KEY_LAST_SYNC_NOTE, note)
            .putBoolean(KEY_LAST_SYNC_OK, ok)
            .apply()
        _state.value = read()
    }

    fun setQuizMic(on: Boolean) {
        prefs.edit().putBoolean(KEY_QUIZ_MIC, on).apply()
        _state.value = read()
    }

    fun setPhoneMic(on: Boolean) {
        prefs.edit().putBoolean(KEY_PHONE_MIC, on).apply()
        _state.value = read()
    }

    /** Распознаватель на этом телефоне не читает внешний источник звука: больше не пробуем. */
    var micPipeBroken: Boolean
        get() = prefs.getBoolean(KEY_MIC_PIPE_BROKEN, false)
        set(v) = prefs.edit().putBoolean(KEY_MIC_PIPE_BROKEN, v).apply()

    fun setBatchMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_BATCH, on).apply()
        _state.value = read()
    }

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
        const val KEY_BATCH = "batch_mode"
        const val KEY_PHONE_MIC = "phone_mic"
        const val KEY_QUIZ_MIC = "quiz_mic"
        const val KEY_MIC_PIPE_BROKEN = "mic_pipe_broken"
        const val KEY_READER_MODE = "reader_mode"
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_SYNC_ENABLED = "sync_enabled"
        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_SYNC_NOTE = "last_sync_note"
        const val KEY_LAST_SYNC_OK = "last_sync_ok"
    }
}
