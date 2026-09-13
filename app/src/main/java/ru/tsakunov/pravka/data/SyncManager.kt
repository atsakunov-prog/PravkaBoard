package ru.tsakunov.pravka.data

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.tsakunov.pravka.api.GitHubException
import ru.tsakunov.pravka.api.GitHubStore
import ru.tsakunov.pravka.domain.Backup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Done(val imported: Int, val pushed: Boolean, val at: Long) : SyncState
    data class Error(val message: String) : SyncState
}

/**
 * Синхронизация телефона и книжки через один файл в ветке data репозитория.
 * Один проход: забрать файл → влить в базу (слияние в Repository.importSnapshot) → если есть токен и
 * содержимое отличается, выгрузить свой слепок обратно. Каждое устройство сначала принимает чужое и
 * только потом отправляет, поэтому файл всегда содержит объединение. Если файл успел измениться между
 * чтением и записью, GitHub вернёт конфликт: забираем ещё раз и повторяем один раз.
 */
class SyncManager(
    private val repo: Repository,
    private val settings: Settings,
    private val store: GitHubStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state
    private val mutex = Mutex()
    private var lastAuto = 0L

    /** Тихая синхронизация при запуске и уходе в фон, не чаще раза в минуту. */
    fun autoSync() {
        if (!settings.state.value.syncEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastAuto < 60_000L) return
        lastAuto = now
        scope.launch { sync() }
    }

    fun syncNow() {
        scope.launch { sync() }
    }

    suspend fun sync(): SyncState {
        if (!mutex.tryLock()) return _state.value
        try {
            _state.value = SyncState.Running
            val token = settings.state.value.githubToken.ifBlank { null }
            val remote = store.fetch(token)
            val remoteSnapshot = remote?.let { Backup.parse(it.text) }
            var imported = remoteSnapshot?.let { repo.importSnapshot(it) } ?: 0
            var pushed = false
            if (token != null) {
                var local = repo.snapshot()
                val same = remoteSnapshot != null && Backup.fingerprint(remoteSnapshot) == Backup.fingerprint(local)
                if (!same) {
                    try {
                        store.push(token, Backup.toJson(local.copy(device = device)), remote?.sha, commitMessage(local.exportedAt))
                    } catch (e: GitHubException) {
                        if (!e.conflict) throw e
                        val again = store.fetch(token)
                        if (again != null) imported += repo.importSnapshot(Backup.parse(again.text))
                        local = repo.snapshot()
                        store.push(token, Backup.toJson(local.copy(device = device)), again?.sha, commitMessage(local.exportedAt))
                    }
                    pushed = true
                }
            }
            val note = buildString {
                append(if (imported > 0) "получено $imported" else "нового нет")
                if (pushed) append(", отправлено")
                else if (token == null) append(", без токена только приём")
            }
            settings.recordSync(ok = true, note = note)
            val done = SyncState.Done(imported, pushed, System.currentTimeMillis())
            _state.value = done
            return done
        } catch (e: GitHubException) {
            return fail(e.message ?: "Ошибка GitHub")
        } catch (e: Exception) {
            return fail("Синхронизация не удалась: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            mutex.unlock()
        }
    }

    private fun fail(message: String): SyncState {
        settings.recordSync(ok = false, note = message)
        val err = SyncState.Error(message)
        _state.value = err
        return err
    }

    private val device: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun commitMessage(at: Long): String =
        "pravka sync: $device, ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(at))}"
}
