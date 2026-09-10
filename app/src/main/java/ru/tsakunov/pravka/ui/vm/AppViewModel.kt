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
import ru.tsakunov.pravka.api.ClaudeException
import ru.tsakunov.pravka.api.ClaudeVocabParser
import ru.tsakunov.pravka.data.Attempt
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.data.Metric
import ru.tsakunov.pravka.data.Repository
import ru.tsakunov.pravka.data.Settings
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.data.WordList
import ru.tsakunov.pravka.domain.countLetters

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
) : ViewModel() {

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
            AppViewModel(app.repository, app.settings, ClaudeVocabParser(app)) as T
    }
}
