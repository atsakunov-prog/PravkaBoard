package ru.tsakunov.pravka.ui.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.tsakunov.pravka.PravkaApp
import ru.tsakunov.pravka.api.ClaudeApi
import ru.tsakunov.pravka.api.ClaudeException
import ru.tsakunov.pravka.api.ClaudeJudge
import ru.tsakunov.pravka.api.ClaudeStory
import ru.tsakunov.pravka.api.ClaudeVocabParser
import ru.tsakunov.pravka.api.UpdateException
import ru.tsakunov.pravka.api.UpdateInfo
import ru.tsakunov.pravka.api.Updater
import java.io.File
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.Metric
import ru.tsakunov.pravka.data.Repository
import ru.tsakunov.pravka.data.Settings
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.data.WordList
import ru.tsakunov.pravka.domain.countLetters

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

sealed interface StoryState {
    data object Idle : StoryState
    data object Generating : StoryState
    data class Error(val message: String) : StoryState
}

sealed interface ParseState {
    data object Idle : ParseState
    data class Running(val photos: Int) : ParseState
    data class Error(val message: String) : ParseState
    data class Done(val list: WordList) : ParseState
}

class AppViewModel(
    private val repo: Repository,
    private val settings: Settings,
    private val parser: ClaudeVocabParser,
    private val updater: Updater,
    private val storyGen: ClaudeStory,
    private val judge: ClaudeJudge,
) : ViewModel() {

    // ---- Слова: рассказ, контроша, судья ----
    val quizRuns = repo.observeAllQuizRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun observeQuizRuns(listId: String) = repo.observeQuizRuns(listId)
    fun observeStory(listId: String) = repo.observeStory(listId)

    private val _story = MutableStateFlow<StoryState>(StoryState.Idle)
    val storyState: StateFlow<StoryState> = _story

    fun generateStory(listId: String, items: List<WordItem>, previousTitle: String?) {
        if (_story.value is StoryState.Generating) return
        val s = settings.state.value
        _story.value = StoryState.Generating
        viewModelScope.launch {
            try {
                val g = storyGen.generate(items, s.apiKey, s.model, previousTitle)
                repo.saveStory(listId, g.title, g.textEn, g.textRu)
                _story.value = StoryState.Idle
            } catch (e: ClaudeException) {
                _story.value = StoryState.Error(e.message ?: "Ошибка")
            } catch (e: Exception) {
                _story.value = StoryState.Error("Не получилось: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Запасной судья для фраз: спрашивает модель, засчитать ли ответ. При ошибке сети не засчитывает. */
    suspend fun judgeAnswer(ru: String, expectedEn: String, heard: List<String>): Boolean {
        val s = settings.state.value
        if (s.apiKey.isBlank()) return false
        return try { judge.accept(ru, expectedEn, heard, s.apiKey, s.model) } catch (e: Exception) { false }
    }

    fun saveQuizRun(listId: String, attempts: Int, durationMs: Long, words: Int) = viewModelScope.launch {
        repo.addQuizRun(listId, attempts, durationMs, words)
    }

    // ---- Обновления из GitHub ----
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update
    val currentBuild: Int get() = updater.currentBuild

    init {
        // Тихая проверка не чаще раза в 6 часов; результат виден как баннер на главном экране.
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheck > 6 * 60 * 60 * 1000L) checkUpdates(silent = true)
    }

    fun checkUpdates(silent: Boolean = false) {
        if (_update.value is UpdateState.Downloading) return
        if (!silent) _update.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val info = updater.check()
                settings.lastUpdateCheck = System.currentTimeMillis()
                _update.value = if (info != null) UpdateState.Available(info) else if (silent) UpdateState.Idle else UpdateState.UpToDate
            } catch (e: UpdateException) {
                if (!silent) _update.value = UpdateState.Error(e.message ?: "Ошибка")
            } catch (e: Exception) {
                if (!silent) _update.value = UpdateState.Error("Не удалось проверить: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun downloadUpdate(info: UpdateInfo) {
        if (_update.value is UpdateState.Downloading) return
        _update.value = UpdateState.Downloading(info, 0f)
        viewModelScope.launch {
            try {
                val file = updater.download(info) { p -> _update.value = UpdateState.Downloading(info, p) }
                _update.value = UpdateState.Ready(info, file)
                updater.install(file)
            } catch (e: UpdateException) {
                _update.value = UpdateState.Error(e.message ?: "Ошибка")
            } catch (e: Exception) {
                _update.value = UpdateState.Error("Не удалось обновить: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun installUpdate(file: File) {
        runCatching { updater.install(file) }.onFailure { showToast("Не удалось открыть установщик: ${it.message}") }
    }

    fun dismissUpdate() { _update.value = UpdateState.Idle }

    val settingsState = settings.state
    val lists = repo.observeLists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val attempts = repo.observeAttempts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _parse = MutableStateFlow<ParseState>(ParseState.Idle)
    val parse: StateFlow<ParseState> = _parse

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    fun toastShown() { _toast.value = null }
    fun showToast(msg: String) { _toast.value = msg }

    fun observeList(id: String) = repo.observeList(id)
    fun observeItems(listId: String) = repo.observeItems(listId)

    // ---- Фото → список ----
    fun parsePhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val s = settings.state.value
        _parse.value = ParseState.Running(uris.size)
        viewModelScope.launch {
            try {
                val parsed = parser.parse(uris, s.apiKey, s.model)
                val list = repo.createList(parsed.title, parsed.items.map { it.en to it.ru }, parsed.items.map { it.kind })
                _parse.value = ParseState.Done(list)
            } catch (e: ClaudeException) {
                _parse.value = ParseState.Error(e.message ?: "Ошибка")
            } catch (e: Exception) {
                _parse.value = ParseState.Error("Что-то пошло не так: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            }
        }
    }
    fun parseHandled() { _parse.value = ParseState.Idle }

    // ---- Списки ----
    fun createManualList(title: String, text: String, onDone: (WordList) -> Unit) {
        val pairs = text.lines().mapNotNull { line ->
            val l = line.trim()
            if (l.isEmpty()) return@mapNotNull null
            val sep = Regex("\\s*[-—–:=]\\s+|\\s+[-—–:=]\\s*|\\t|\\s*[—–]\\s*")
            val parts = l.split(sep, limit = 2).map { it.trim() }
            when (parts.size) {
                2 -> parts[0] to parts[1]
                else -> {
                    // Одно слово: определяем язык по алфавиту.
                    if (l.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }) "" to l else l to ""
                }
            }
        }
        if (pairs.isEmpty()) { showToast("Не нашёл ни одного слова"); return }
        viewModelScope.launch { onDone(repo.createList(title, pairs)) }
    }

    fun renameList(id: String, title: String) = viewModelScope.launch { repo.renameList(id, title) }
    fun deleteList(id: String) = viewModelScope.launch { repo.deleteList(id) }
    fun addItem(listId: String, en: String, ru: String) = viewModelScope.launch { repo.addItem(listId, en, ru) }
    fun updateItem(item: WordItem, en: String, ru: String) = viewModelScope.launch { repo.updateItem(item, en, ru) }
    fun deleteItem(item: WordItem) = viewModelScope.launch { repo.deleteItem(item) }

    // ---- Попытки ----
    suspend fun recordAttempt(item: WordItem, lang: Lang, ms: Long): Attempt {
        val word = if (lang == Lang.EN) item.en else item.ru
        return repo.addAttempt(
            lang = lang, word = word, letters = countLetters(word).coerceAtLeast(1), ms = ms,
            source = Attempt.SOURCE_APP, listId = item.listId, itemId = item.id,
        )
    }

    fun addPaperAttempt(lang: Lang, word: String?, letters: Int, ms: Long) = viewModelScope.launch {
        repo.addAttempt(lang, word, letters, ms, source = Attempt.SOURCE_MANUAL)
        showToast("Результат добавлен")
    }

    fun deleteAttempt(id: String) = viewModelScope.launch { repo.deleteAttempt(id) }

    // ---- Настройки ----
    fun setApiKey(v: String) = settings.setApiKey(v)
    fun setModel(v: String) = settings.setModel(v)
    fun setMetric(m: Metric) = settings.setMetric(m)

    suspend fun exportJson(): String = repo.exportJson()
    suspend fun importJson(text: String): Int = repo.importJson(text)
    fun resetAll() = viewModelScope.launch { repo.resetAll(); showToast("Данные сброшены, бумажная статистика оставлена") }

    class Factory(private val app: PravkaApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ClaudeApi().let { api ->
                AppViewModel(app.repository, app.settings, ClaudeVocabParser(app, api), Updater(app), ClaudeStory(api), ClaudeJudge(api)) as T
            }
    }
}
