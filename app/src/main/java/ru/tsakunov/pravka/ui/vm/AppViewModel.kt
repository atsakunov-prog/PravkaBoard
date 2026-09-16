package ru.tsakunov.pravka.ui.vm

import android.graphics.Typeface
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tsakunov.pravka.PravkaApp
import ru.tsakunov.pravka.api.ClaudeApi
import ru.tsakunov.pravka.api.ClaudeBatch
import ru.tsakunov.pravka.api.ClaudeSorter
import ru.tsakunov.pravka.api.ClaudeException
import ru.tsakunov.pravka.api.ClaudeGrammar
import ru.tsakunov.pravka.api.ClaudeReading
import ru.tsakunov.pravka.api.ClaudeReadingJudge
import ru.tsakunov.pravka.api.SentenceDraft
import ru.tsakunov.pravka.api.ClaudeHomework
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
import ru.tsakunov.pravka.data.SyncManager
import ru.tsakunov.pravka.data.SyncState
import ru.tsakunov.pravka.data.WordItem
import ru.tsakunov.pravka.data.WordList
import ru.tsakunov.pravka.data.GrammarProgress
import ru.tsakunov.pravka.data.ReadingRun
import ru.tsakunov.pravka.domain.HomeworkResult
import ru.tsakunov.pravka.domain.LocalReading
import ru.tsakunov.pravka.domain.ReadingDetail
import ru.tsakunov.pravka.domain.SentenceResult
import ru.tsakunov.pravka.domain.countLetters
import ru.tsakunov.pravka.domain.countWords
import ru.tsakunov.pravka.domain.movedIds
import ru.tsakunov.pravka.ui.components.CursiveFontFiles
import ru.tsakunov.pravka.ui.components.SpeechInput
import ru.tsakunov.pravka.ui.fmtTime

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

sealed interface GrammarState {
    data object Idle : GrammarState
    data class Running(val photos: Int) : GrammarState
    data class Error(val message: String) : GrammarState
    data class Done(val setId: String) : GrammarState
}

sealed interface ReadingState {
    data object Idle : ReadingState
    data class Running(val photos: Int) : ReadingState
    data class Error(val message: String) : ReadingState
    data class Done(val textId: String) : ReadingState
}

sealed interface HomeworkState {
    data object Idle : HomeworkState
    data class Running(val photos: Int) : HomeworkState
    data class Error(val message: String) : HomeworkState
    data class Done(val homeworkId: String, val result: HomeworkResult) : HomeworkState
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

/** Подсказка внизу экрана; с actionLabel справа появляется кнопка (например, «Вернуть»). */
data class ToastMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/**
 * Отменяемое действие в списке слов: удаление, правка, перемещение слова, записанный результат письма.
 * key называет тронутую запись, чтобы новое действие над ней заменяло прежнюю запись стека; label — то, что
 * подставляется после «Отменено:».
 */
class UndoEntry(
    val key: String,
    val listId: String,
    val label: String,
    val undo: suspend () -> Unit,
    val redo: suspend () -> Unit,
)

class AppViewModel(
    private val repo: Repository,
    private val settings: Settings,
    private val parser: ClaudeVocabParser,
    private val updater: Updater,
    private val storyGen: ClaudeStory,
    private val judge: ClaudeJudge,
    private val homeworkChecker: ClaudeHomework,
    private val grammarBuilder: ClaudeGrammar,
    private val readingExtractor: ClaudeReading,
    private val readingJudge: ClaudeReadingJudge,
    private val syncManager: SyncManager,
    /** Каталог своих шрифтов прописи (filesDir/fonts); между устройствами они не ходят. */
    private val fontsDir: File,
    api: ClaudeApi,
    batch: ClaudeBatch,
    sorter: ClaudeSorter,
) : ViewModel() {

    // ---- Синхронизация через GitHub ----
    val syncState: StateFlow<SyncState> = syncManager.state
    fun syncNow() = syncManager.syncNow()
    fun setSyncEnabled(on: Boolean) { settings.setSyncEnabled(on); if (on) syncManager.syncNow() }
    fun setGithubToken(v: String) = settings.setGithubToken(v)

    // ---- Экран ----
    fun setReaderMode(on: Boolean) = settings.setReaderMode(on)
    fun setUiScale(scale: Float) = settings.setUiScale(scale)
    val isEink: Boolean get() = settings.isEink

    // ---- Журнал занятий (обучалка, училка, тренажёр) для общей статистики ----
    val activity = repo.observeActivity().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun logActivity(kind: String, refId: String?, durationMs: Long, total: Int, correct: Int) = viewModelScope.launch {
        repo.logActivity(kind, refId, durationMs, total, correct)
    }

    // ---- Вся домашка одним пакетом фото ----
    val intake = IntakeCoordinator(viewModelScope, repo, settings, api, batch, sorter, parser, grammarBuilder, homeworkChecker, readingExtractor)
    fun setBatchMode(on: Boolean) = settings.setBatchMode(on)

    // ---- Грамматика ----
    val grammarSets = repo.observeGrammarSets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val grammarProgress = repo.observeAllGrammarProgress().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun observeGrammarSet(id: String) = cached("gs:$id") { repo.observeGrammarSet(id) }
    fun observeGrammarProgress(setId: String) = cached("gp:$setId") { repo.observeGrammarProgress(setId) }

    private val _grammar = MutableStateFlow<GrammarState>(GrammarState.Idle)
    val grammarState: StateFlow<GrammarState> = _grammar

    fun buildGrammar(uris: List<Uri>) {
        if (uris.isEmpty() || _grammar.value is GrammarState.Running) return
        val s = settings.state.value
        _grammar.value = GrammarState.Running(uris.size)
        viewModelScope.launch {
            try {
                val content = grammarBuilder.build(uris, s.apiKey, s.model)
                val set = repo.saveGrammarSet(content)
                _grammar.value = GrammarState.Done(set.id)
            } catch (e: ClaudeException) {
                _grammar.value = GrammarState.Error(e.message ?: "Ошибка")
            } catch (e: Exception) {
                _grammar.value = GrammarState.Error("Что-то пошло не так: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            }
        }
    }
    fun grammarHandled() { _grammar.value = GrammarState.Idle }
    fun deleteGrammarSet(id: String) = viewModelScope.launch { repo.deleteGrammarSet(id) }
    suspend fun recordGrammarAnswer(setId: String, ruleIndex: Int, correct: Boolean, streak: Int): GrammarProgress =
        repo.recordGrammarAnswer(setId, ruleIndex, correct, streak)

    // ---- Чтение ----
    val readingTexts = repo.observeReadingTexts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val readingRuns = repo.observeReadingRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allStories = repo.observeAllStories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun observeReadingText(id: String) = cached("rt:$id") { repo.observeReadingText(id) }
    fun observeStoryById(id: String) = cached("sb:$id") { repo.observeStoryById(id) }

    private val _reading = MutableStateFlow<ReadingState>(ReadingState.Idle)
    val readingState: StateFlow<ReadingState> = _reading

    fun extractReading(uris: List<Uri>) {
        if (uris.isEmpty() || _reading.value is ReadingState.Running) return
        val s = settings.state.value
        _reading.value = ReadingState.Running(uris.size)
        viewModelScope.launch {
            try {
                val t = readingExtractor.extract(uris, s.apiKey, s.model)
                val saved = repo.saveReadingText(t.title, t.textEn, t.textRu)
                _reading.value = ReadingState.Done(saved.id)
            } catch (e: ClaudeException) {
                _reading.value = ReadingState.Error(e.message ?: "Ошибка")
            } catch (e: Exception) {
                _reading.value = ReadingState.Error("Что-то пошло не так: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            }
        }
    }
    fun readingHandled() { _reading.value = ReadingState.Idle }
    fun deleteReadingText(id: String) = viewModelScope.launch { repo.deleteReadingText(id) }
    fun deleteStory(id: String) = viewModelScope.launch { repo.deleteStory(id) }
    suspend fun saveReadingRun(textId: String, durationMs: Long, stumbles: Int, words: Int): ReadingRun = repo.addReadingRun(textId, durationMs, stumbles, words)
    fun deleteReadingRun(id: String) = viewModelScope.launch { repo.deleteReadingRun(id) }

    /**
     * Разбор чтения с микрофоном. Если модель недоступна (нет ключа, сети), чтение оцениваем локально по
     * совпадению слов, перевод остаётся неизвестным; ошибка возвращается вторым значением для подсказки.
     */
    suspend fun judgeReading(textRu: String, drafts: List<SentenceDraft>): Pair<ReadingDetail, String?> {
        val s = settings.state.value
        val local = ReadingDetail(
            sentences = drafts.map { d ->
                SentenceResult(
                    text = d.text, heardEn = d.heardEn, heardRu = d.heardRu,
                    reading = if (d.enSkipped) SentenceResult.SKIPPED else LocalReading.readingVerdict(d.text, d.heardEn),
                    translation = if (d.ruSkipped) SentenceResult.SKIPPED else SentenceResult.UNKNOWN,
                    comment = "", readMs = d.readMs,
                )
            },
            praise = "", judged = false,
        )
        if (s.apiKey.isBlank()) return local to "Без API-ключа перевод не проверяется, только чтение"
        return try {
            readingJudge.judge(textRu, drafts, s.apiKey, s.model) to null
        } catch (e: ClaudeException) {
            local to (e.message ?: "Модель недоступна")
        } catch (e: Exception) {
            local to "Не удалось проверить перевод: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Вердикт модели и запись чтения: в области ViewModel, чтобы уход с экрана не потерял результат. */
    fun finishMicReading(textId: String, textRu: String, drafts: List<SentenceDraft>, totalMs: Long): Deferred<Triple<ReadingRun, ReadingDetail, String?>> =
        viewModelScope.async {
            val (detail, warning) = judgeReading(textRu, drafts)
            val readingMs = drafts.sumOf { it.readMs }.takeIf { it > 0 } ?: totalMs
            val words = drafts.filter { !it.enSkipped }.sumOf { countWords(it.text) }.coerceAtLeast(1)
            val run = repo.addMicReadingRun(textId, totalMs, readingMs, words, detail)
            Triple(run, detail, warning)
        }

    // ---- Домашка ----
    val homeworks = repo.observeHomeworks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val homeworkChecks = repo.observeAllHomeworkChecks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun observeHomework(id: String) = cached("hw:$id") { repo.observeHomework(id) }
    fun observeHomeworkChecks(id: String) = cached("hwc:$id") { repo.observeHomeworkChecks(id) }

    private val _homework = MutableStateFlow<HomeworkState>(HomeworkState.Idle)
    val homeworkState: StateFlow<HomeworkState> = _homework

    /** Проверка домашки. homeworkId == null — новая работа, иначе повторная проверка после исправлений. */
    fun checkHomework(uris: List<Uri>, homeworkId: String?, previous: HomeworkResult?) {
        if (uris.isEmpty() || _homework.value is HomeworkState.Running) return
        val s = settings.state.value
        _homework.value = HomeworkState.Running(uris.size)
        viewModelScope.launch {
            try {
                val result = homeworkChecker.check(uris, previous, s.apiKey, s.model)
                val id = repo.saveHomeworkCheck(homeworkId, result)
                _homework.value = HomeworkState.Done(id, result)
            } catch (e: ClaudeException) {
                _homework.value = HomeworkState.Error(e.message ?: "Ошибка")
            } catch (e: Exception) {
                _homework.value = HomeworkState.Error("Что-то пошло не так: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            }
        }
    }
    fun homeworkHandled() { _homework.value = HomeworkState.Idle }
    fun deleteHomework(id: String) = viewModelScope.launch { repo.deleteHomework(id) }

    // ---- Слова: рассказ, контроша, судья ----
    val quizRuns = repo.observeAllQuizRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun observeQuizRuns(listId: String) = cached("quiz:$listId") { repo.observeQuizRuns(listId) }
    fun observeStory(listId: String) = cached("story:$listId") { repo.observeStory(listId) }

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
        // Отправленные пакеты проверяем при каждом запуске.
        intake.refresh(startup = true)
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

    private val _toast = MutableStateFlow<ToastMessage?>(null)
    val toast: StateFlow<ToastMessage?> = _toast
    fun toastShown() { _toast.value = null }
    fun showToast(msg: String) = showToast(ToastMessage(msg))
    fun showToast(t: ToastMessage) { _toast.value = t }

    // ---- Отмена действий в списках слов ----
    private val _undoStack = MutableStateFlow<List<UndoEntry>>(emptyList())
    /** Последние отменяемые действия; экран берёт последнее по своему listId. Живёт, пока живёт ViewModel. */
    val undoStack: StateFlow<List<UndoEntry>> = _undoStack

    /** Повторное действие над той же записью заменяет её прежнюю запись стека; глубина ограничена. */
    private fun pushUndo(e: UndoEntry) = _undoStack.update { (it.filter { x -> x.key != e.key } + e).takeLast(UNDO_DEPTH) }
    private fun forgetUndo(key: String) = _undoStack.update { it.filter { x -> x.key != key } }

    /** Стрелка отмены на экранах списка и тренировки: откатывает последнее действие в этом списке и предлагает вернуть его. */
    fun undoLast(listId: String) {
        val e = _undoStack.value.lastOrNull { it.listId == listId } ?: return
        _undoStack.update { it - e }
        viewModelScope.launch {
            if (!attempt(e.undo, "Не получилось отменить")) return@launch
            showToast(ToastMessage("Отменено: ${e.label}", "Вернуть") { viewModelScope.launch { if (attempt(e.redo, "Не получилось вернуть")) pushUndo(e) } })
        }
    }

    /** «Вернуть» в подсказке сразу после действия: откат без встречного предложения. */
    private fun undoEntry(e: UndoEntry) {
        if (e !in _undoStack.value) return // уже откатили стрелкой
        _undoStack.update { it - e }
        viewModelScope.launch { attempt(e.undo, "Не получилось вернуть") }
    }

    /** Откат может упереться в базу (список уже удалили, пришла синхронизация): не падаем, а говорим словами. */
    private suspend fun attempt(action: suspend () -> Unit, failure: String): Boolean =
        runCatching { action() }.onFailure { showToast("$failure: ${it.message ?: it.javaClass.simpleName}") }.isSuccess

    /** Удалённый список забирает с собой и свои отменяемые действия. */
    private fun forgetUndoForList(listId: String) = _undoStack.update { it.filter { x -> x.listId != listId } }

    // ---- Шрифты прописи в Гармошке ----
    private val _cursiveFonts = MutableStateFlow(readCursiveFonts())
    /** Свои файлы шрифтов прописи (null — встроенный шрифт). */
    val cursiveFonts: StateFlow<CursiveFontFiles> = _cursiveFonts
    private fun cursiveFile(lang: Lang) = File(fontsDir, "cursive_${lang.code}.ttf")
    /** version — время файлов: замена шрифта по тому же пути иначе не отличалась бы от прежнего состояния. */
    private fun readCursiveFonts(): CursiveFontFiles {
        val en = cursiveFile(Lang.EN).takeIf { it.isFile }
        val ru = cursiveFile(Lang.RU).takeIf { it.isFile }
        return CursiveFontFiles(en = en, ru = ru, version = (en?.lastModified() ?: 0L) + (ru?.lastModified() ?: 0L))
    }

    /** Ставит свой шрифт прописи из содержимого файла; false — Android не прочитал его как шрифт, ничего не меняется. */
    suspend fun installCursiveFont(lang: Lang, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        fontsDir.mkdirs()
        val tmp = File(fontsDir, "cursive_${lang.code}.tmp")
        tmp.writeBytes(bytes)
        val ok = runCatching { Typeface.Builder(tmp).build() != null }.getOrDefault(false)
        if (ok) {
            val target = cursiveFile(lang)
            target.delete()
            tmp.renameTo(target)
        } else {
            tmp.delete()
        }
        _cursiveFonts.value = readCursiveFonts()
        ok
    }

    fun removeCursiveFont(lang: Lang) {
        cursiveFile(lang).delete()
        _cursiveFonts.value = readCursiveFonts()
    }

    // Потоки по идентификатору кэшируются: collectAsStateWithLifecycle пересобирается при смене
    // экземпляра Flow, а Repository возвращал бы новый на каждую перекомпозицию.
    private val flowCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String, make: () -> kotlinx.coroutines.flow.Flow<T>): kotlinx.coroutines.flow.Flow<T> =
        flowCache.getOrPut(key) { make() } as kotlinx.coroutines.flow.Flow<T>

    fun observeList(id: String) = cached("list:$id") { repo.observeList(id) }
    fun observeItems(listId: String) = cached("items:$listId") { repo.observeItems(listId) }

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
    fun deleteList(id: String) = viewModelScope.launch { repo.deleteList(id); forgetUndoForList(id) }
    fun addItem(listId: String, en: String, ru: String) = viewModelScope.launch { repo.addItem(listId, en, ru) }

    fun updateItem(item: WordItem, en: String, ru: String) = viewModelScope.launch {
        val updated = repo.updateItem(item.id, en, ru) ?: return@launch
        if (updated.en == item.en && updated.ru == item.ru) return@launch
        pushUndo(
            UndoEntry(
                "item:${item.id}", item.listId, "правка «${updated.label}»",
                undo = { repo.updateItem(item.id, item.en, item.ru) }, redo = { repo.updateItem(item.id, updated.en, updated.ru) },
            ),
        )
    }

    /** Удаляет сразу, без вопроса: слово возвращается кнопкой «Вернуть» в подсказке или стрелкой отмены на экране списка. */
    fun deleteItem(item: WordItem) = viewModelScope.launch {
        repo.deleteItem(item)
        val e = UndoEntry(
            "item:${item.id}", item.listId, "удаление «${item.label}»",
            undo = { if (!repo.restoreItem(item)) error("список уже удалён") }, redo = { repo.deleteItem(item) },
        )
        pushUndo(e)
        showToast(ToastMessage("Слово «${item.label}» удалено", "Вернуть") { undoEntry(e) })
    }

    /**
     * Новый порядок слов после перетаскивания; movedId — какое слово тянули, для подписи отмены. Несколько
     * перестановок подряд складываются в одну запись стека: отмена возвращает порядок до первой из них.
     */
    fun reorderItems(listId: String, orderedIds: List<String>, movedId: String?) = viewModelScope.launch {
        val before = repo.getItems(listId)
        val beforeIds = before.map { it.id }
        if (beforeIds == orderedIds) return@launch
        repo.reorderItems(listId, orderedIds)
        val key = "order:$listId"
        val previous = _undoStack.value.lastOrNull { it.key == key }
        val label = before.firstOrNull { it.id == movedId }?.label ?: "слова"
        pushUndo(
            UndoEntry(
                key, listId, "перемещение «$label»",
                undo = previous?.undo ?: { repo.reorderItems(listId, beforeIds) }, redo = { repo.reorderItems(listId, orderedIds) },
            ),
        )
    }

    /** Сдвиг слова из меню строки: −1 выше, +1 ниже. */
    fun moveItem(listId: String, itemId: String, delta: Int) = viewModelScope.launch {
        val ids = repo.getItems(listId).map { it.id }
        val moved = movedIds(ids, itemId, delta) ?: return@launch
        reorderItems(listId, moved, itemId)
    }

    // ---- Попытки ----
    /** Записывает результат письма и кладёт его в стек отмены: после «Дальше» его ещё можно снять стрелкой. */
    suspend fun recordAttempt(item: WordItem, lang: Lang, ms: Long): Attempt {
        val word = if (lang == Lang.EN) item.en else item.ru
        val a = repo.addAttempt(
            lang = lang, word = word, letters = countLetters(word).coerceAtLeast(1), ms = ms,
            source = Attempt.SOURCE_APP, listId = item.listId, itemId = item.id,
        )
        // Возвращённая попытка получает новый id, поэтому запись стека держит текущую копию.
        var current = a
        pushUndo(
            UndoEntry(
                "attempt:${a.id}", item.listId, "результат «$word» ${fmtTime(ms, tenths = true)}",
                undo = { repo.deleteAttempt(current.id) }, redo = { current = repo.restoreAttempt(current) },
            ),
        )
        return a
    }

    fun addPaperAttempt(lang: Lang, word: String?, letters: Int, ms: Long) = viewModelScope.launch {
        repo.addAttempt(lang, word, letters, ms, source = Attempt.SOURCE_MANUAL)
        showToast("Результат добавлен")
    }

    /** «Отменить» на экране результата: попытка удаляется и из стека отмены тоже. */
    fun deleteAttempt(id: String) = viewModelScope.launch { repo.deleteAttempt(id); forgetUndo("attempt:$id") }

    // ---- Микрофон ----
    /** Распознаватель с настройками телефонного микрофона; освобождать в DisposableEffect экрана. */
    fun speechInput(context: android.content.Context) = SpeechInput(
        context,
        preferPhoneMic = { settings.state.value.phoneMic },
        pipeUnsupported = { settings.micPipeBroken },
        onPipeUnsupported = { settings.micPipeBroken = true; showToast("Распознавание не берёт звук с микрофона телефона, слушаем как обычно") },
    )
    fun setPhoneMic(on: Boolean) { settings.setPhoneMic(on); if (on) settings.micPipeBroken = false }
    fun setQuizMic(on: Boolean) = settings.setQuizMic(on)
    val micPipeBroken: Boolean get() = settings.micPipeBroken

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
                AppViewModel(
                    app.repository, app.settings, ClaudeVocabParser(app, api), Updater(app),
                    ClaudeStory(api), ClaudeJudge(api), ClaudeHomework(app, api),
                    ClaudeGrammar(app, api), ClaudeReading(app, api), ClaudeReadingJudge(api),
                    app.sync, File(app.filesDir, "fonts"), api, ClaudeBatch(api), ClaudeSorter(app, api),
                ) as T
            }
    }

    private companion object {
        /** Сколько последних действий помнит стек отмены. */
        const val UNDO_DEPTH = 12
    }
}
