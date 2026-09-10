package ru.tsakunov.pravka.ui.vm

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.tsakunov.pravka.api.BatchItem
import ru.tsakunov.pravka.api.ClaudeApi
import ru.tsakunov.pravka.api.ClaudeBatch
import ru.tsakunov.pravka.api.ClaudeException
import ru.tsakunov.pravka.api.ClaudeGrammar
import ru.tsakunov.pravka.api.ClaudeHomework
import ru.tsakunov.pravka.api.ClaudeReading
import ru.tsakunov.pravka.api.ClaudeSorter
import ru.tsakunov.pravka.api.ClaudeVocabParser
import ru.tsakunov.pravka.api.ToolRequest
import ru.tsakunov.pravka.data.IntakeJob
import ru.tsakunov.pravka.data.Repository
import ru.tsakunov.pravka.data.Settings
import ru.tsakunov.pravka.domain.IntakeKind
import ru.tsakunov.pravka.domain.IntakePart
import java.util.UUID

sealed interface IntakeState {
    data object Idle : IntakeState
    data class Sorting(val photos: Int) : IntakeState
    data class Running(val done: Int, val total: Int) : IntakeState
    data class Submitting(val parts: Int) : IntakeState
    data class Done(val jobId: String, val queued: Boolean) : IntakeState
    data class Error(val message: String) : IntakeState
}

/**
 * Разбор всей домашки: фото сортируются по видам страниц, потом каждый вид уходит своему разборщику
 * (словарь, правило, упражнения, текст). Сразу — параллельные запросы; пакетом — Message Batches,
 * вдвое дешевле, итог забирается опросом.
 */
class IntakeCoordinator(
    private val scope: CoroutineScope,
    private val repo: Repository,
    private val settings: Settings,
    private val api: ClaudeApi,
    private val batch: ClaudeBatch,
    private val sorter: ClaudeSorter,
    private val vocab: ClaudeVocabParser,
    private val grammar: ClaudeGrammar,
    private val homework: ClaudeHomework,
    private val reading: ClaudeReading,
) {
    private val _state = MutableStateFlow<IntakeState>(IntakeState.Idle)
    val state: StateFlow<IntakeState> = _state
    val jobs = repo.observeIntakeJobs().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var refreshing = false

    fun handled() { _state.value = IntakeState.Idle }

    val busy: Boolean get() = _state.value is IntakeState.Sorting || _state.value is IntakeState.Running || _state.value is IntakeState.Submitting

    fun run(uris: List<Uri>, batchMode: Boolean) {
        if (uris.isEmpty() || busy) return
        val s = settings.state.value
        _state.value = IntakeState.Sorting(uris.size)
        scope.launch {
            try {
                val sorted = sorter.sort(uris, s.apiKey, s.model)
                val parts = IntakeKind.entries.mapNotNull { kind ->
                    val pages = sorted.pages.filter { kind in it.kinds }.map { it.index }
                    if (pages.isEmpty()) null else IntakePart(kind, pages, customId = "${kind.api}-${UUID.randomUUID().toString().take(8)}")
                }
                if (parts.isEmpty()) {
                    val notes = sorted.pages.joinToString("; ") { "фото ${it.index}: ${it.note.ifBlank { "не понял" }}" }
                    _state.value = IntakeState.Error("На фото не нашлось ни словаря, ни правила, ни упражнений с ответами, ни текста. $notes")
                    return@launch
                }
                val requests = HashMap<String, ToolRequest>()
                for (p in parts) requests[p.customId] = request(p, uris)
                val now = System.currentTimeMillis()
                val job = IntakeJob(
                    id = repo.newIntakeId(), createdAt = now, updatedAt = now, title = sorted.title,
                    mode = if (batchMode) IntakeJob.MODE_BATCH else IntakeJob.MODE_SYNC,
                    status = if (batchMode) IntakeJob.QUEUED else IntakeJob.RUNNING,
                    photos = uris.size, batchId = null, partsJson = IntakePart.listToJson(parts), error = null,
                )
                if (batchMode) {
                    _state.value = IntakeState.Submitting(parts.size)
                    val batchId = batch.create(s.apiKey, s.model, parts.map { it.customId to requests.getValue(it.customId) })
                    repo.saveIntakeJob(job.copy(batchId = batchId, progress = "отправлено, ждём ответа"))
                    _state.value = IntakeState.Done(job.id, queued = true)
                } else {
                    repo.saveIntakeJob(job)
                    _state.value = IntakeState.Running(0, parts.size)
                    var current = parts
                    val deferred = parts.map { p -> async { p to runCatching { api.callTool(s.apiKey, s.model, requests.getValue(p.customId)) } } }
                    for (d in deferred) {
                        val (p, r) = d.await()
                        val applied = r.fold(onSuccess = { applyInput(p, it) }, onFailure = { p.copy(status = IntakePart.ERROR, error = it.message ?: it.javaClass.simpleName) })
                        current = current.map { if (it.customId == p.customId) applied else it }
                        repo.saveIntakeJob(job.copy(partsJson = IntakePart.listToJson(current)))
                        _state.value = IntakeState.Running(current.count { it.status != IntakePart.PENDING }, parts.size)
                    }
                    finalize(job, current)
                    _state.value = IntakeState.Done(job.id, queued = false)
                }
            } catch (e: ClaudeException) {
                _state.value = IntakeState.Error(e.message ?: "Ошибка")
            } catch (e: Exception) {
                _state.value = IntakeState.Error("Что-то пошло не так: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            }
        }
    }

    private suspend fun request(p: IntakePart, uris: List<Uri>): ToolRequest {
        val images = p.pages.mapNotNull { uris.getOrNull(it - 1) }
        return when (p.kind) {
            IntakeKind.VOCABULARY -> vocab.request(images)
            IntakeKind.GRAMMAR -> grammar.request(images)
            IntakeKind.EXERCISE -> homework.request(images, null)
            IntakeKind.READING -> reading.request(images)
        }
    }

    private fun toolName(kind: IntakeKind): String = when (kind) {
        IntakeKind.VOCABULARY -> ClaudeVocabParser.TOOL_NAME
        IntakeKind.GRAMMAR -> ClaudeGrammar.TOOL_NAME
        IntakeKind.EXERCISE -> ClaudeHomework.TOOL_NAME
        IntakeKind.READING -> ClaudeReading.TOOL_NAME
    }

    /** Ответ инструмента → запись в нужный раздел приложения. */
    private suspend fun applyInput(p: IntakePart, input: JSONObject): IntakePart = try {
        val (id, title) = when (p.kind) {
            IntakeKind.VOCABULARY -> vocab.fromToolInput(input).let { v ->
                val list = repo.createList(v.title, v.items.map { it.en to it.ru }, v.items.map { it.kind })
                list.id to list.title
            }
            IntakeKind.GRAMMAR -> grammar.fromToolInput(input).let { c -> repo.saveGrammarSet(c).id to c.title }
            IntakeKind.EXERCISE -> homework.fromToolInput(input).let { h -> repo.saveHomeworkCheck(null, h) to h.title }
            IntakeKind.READING -> reading.fromToolInput(input).let { t -> repo.saveReadingText(t.title, t.textEn, t.textRu).id to t.title }
        }
        p.copy(status = IntakePart.DONE, targetId = id, title = title, error = null)
    } catch (e: Exception) {
        p.copy(status = IntakePart.ERROR, error = e.message ?: e.javaClass.simpleName)
    }

    private suspend fun finalize(job: IntakeJob, parts: List<IntakePart>) {
        val allFailed = parts.all { it.failed }
        repo.saveIntakeJob(
            job.copy(
                status = if (allFailed) IntakeJob.ERROR else IntakeJob.DONE,
                partsJson = IntakePart.listToJson(parts),
                error = if (allFailed) parts.firstOrNull { it.failed }?.error else null,
                progress = null,
            ),
        )
    }

    /** Опрос отправленных пакетов плюс уборка разборов, прерванных закрытием приложения. */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                val stale = System.currentTimeMillis() - 10 * 60_000L
                for (job in repo.runningIntakeJobs()) {
                    if (job.updatedAt < stale && !busy) {
                        val parts = IntakePart.listFromJson(job.partsJson).map { if (it.status == IntakePart.PENDING) it.copy(status = IntakePart.ERROR, error = "Разбор прервался: приложение закрыли") else it }
                        finalize(job, parts)
                    }
                }
                for (job in repo.queuedIntakeJobs()) checkBatch(job)
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun checkBatch(job: IntakeJob) {
        val s = settings.state.value
        val batchId = job.batchId ?: run { repo.saveIntakeJob(job.copy(status = IntakeJob.ERROR, error = "Пакет не был отправлен")); return }
        try {
            val st = batch.status(s.apiKey, batchId)
            if (!st.ended) {
                repo.saveIntakeJob(job.copy(progress = if (st.total > 0) "готово ${st.succeeded + st.errored} из ${st.total}, ждём остальное" else "в очереди Anthropic"))
                return
            }
            val url = st.resultsUrl ?: run { repo.saveIntakeJob(job.copy(status = IntakeJob.ERROR, error = "Пакет завершён, но итогов нет", progress = null)); return }
            val parts = IntakePart.listFromJson(job.partsJson)
            val results = batch.results(s.apiKey, url, parts.associate { it.customId to toolName(it.kind) })
            val applied = parts.map { p ->
                when (val item = results[p.customId]) {
                    is BatchItem.Ok -> applyInput(p, item.input)
                    is BatchItem.Failed -> p.copy(status = IntakePart.ERROR, error = item.message)
                    null -> p.copy(status = IntakePart.ERROR, error = "Ответа на этот запрос в пакете нет")
                }
            }
            finalize(job, applied)
        } catch (e: Exception) {
            // Сеть или ключ: пакет остаётся в очереди, проверим в следующий раз.
            repo.saveIntakeJob(job.copy(progress = "не удалось проверить: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    fun delete(id: String) = scope.launch { repo.deleteIntakeJob(id) }
}
