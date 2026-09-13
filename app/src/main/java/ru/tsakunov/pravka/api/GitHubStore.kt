package ru.tsakunov.pravka.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import ru.tsakunov.pravka.BuildConfig
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Файл в репозитории: sha блоба нужен, чтобы GitHub принял обновление, а не второй файл с тем же именем. */
data class RemoteFile(val sha: String, val text: String)

/** conflict = true: файл успел измениться, надо забрать свежую версию и повторить. */
class GitHubException(message: String, val conflict: Boolean = false) : Exception(message)

/**
 * Один файл данных в отдельной ветке репозитория PravkaBoard через REST API GitHub.
 * Ветка data не попадает под workflow сборки (он смотрит только main и ветки claude/...), поэтому каждая
 * синхронизация не запускает сборку APK. Если ветки ещё нет, она создаётся пустой (без истории кода):
 * блоб → дерево → коммит без родителей → ссылка.
 *
 * Чтение публичного репозитория работает без токена; запись требует fine-grained токен с правом
 * Contents: Read and write на этот репозиторий.
 */
class GitHubStore(
    private val repo: String = Updater.REPO,
    private val branch: String = BRANCH,
    private val path: String = FILE,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Текущий файл или null, если ветки или файла ещё нет. */
    suspend fun fetch(token: String?): RemoteFile? = withContext(Dispatchers.IO) {
        val req = request("$API/repos/$repo/contents/$path?ref=$branch", token).get().build()
        val json = call(req) { resp ->
            when {
                resp.code == 404 -> return@call null
                !resp.isSuccessful -> throw failure(resp, "прочитать файл")
                else -> JSONObject(resp.body?.string() ?: "{}")
            }
        } ?: return@withContext null
        val sha = json.optString("sha")
        val encoded = json.optString("content").replace("\n", "").replace("\r", "")
        val text = if (json.optString("encoding") == "base64" && encoded.isNotEmpty()) {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        } else {
            // Файлы больше мегабайта contents API отдаёт без содержимого: берём по прямой ссылке.
            val url = json.optString("download_url").ifBlank { throw GitHubException("GitHub не отдал содержимое файла") }
            call(request(url, token).get().build()) { resp ->
                if (!resp.isSuccessful) throw failure(resp, "скачать файл")
                resp.body?.string() ?: ""
            }
        }
        RemoteFile(sha, text)
    }

    /** Записывает файл; sha — от версии, которую читали (null для первой записи). Возвращает новый sha. */
    suspend fun push(token: String, text: String, sha: String?, message: String): String = withContext(Dispatchers.IO) {
        if (!branchExists(token)) return@withContext createBranchWithFile(token, text, message)
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
            .put("branch", branch)
        if (sha != null) body.put("sha", sha)
        val req = request("$API/repos/$repo/contents/$path", token).put(body.toString().toRequestBody(JSON)).build()
        call(req) { resp ->
            val body = resp.body?.string() ?: ""
            when {
                resp.isSuccessful -> JSONObject(body.ifBlank { "{}" }).optJSONObject("content")?.optString("sha") ?: ""
                // 409 — файл изменился; 422 про sha — записывали без sha, а файл уже есть. В обоих случаях перечитать и повторить.
                resp.code == 409 || (resp.code == 422 && body.contains("sha", ignoreCase = true)) ->
                    throw GitHubException("Файл на GitHub изменился, пока шла синхронизация", conflict = true)
                else -> throw failure(resp.code, body, "записать файл")
            }
        }
    }

    private fun branchExists(token: String): Boolean {
        val req = request("$API/repos/$repo/branches/$branch", token).get().build()
        return call(req) { resp ->
            when {
                resp.code == 404 -> false
                !resp.isSuccessful -> throw failure(resp, "проверить ветку")
                else -> true
            }
        }
    }

    /** Пустая ветка только с файлом данных: код приложения в ней не нужен. */
    private fun createBranchWithFile(token: String, text: String, message: String): String {
        val blob = post(token, "$API/repos/$repo/git/blobs", JSONObject().put("content", text).put("encoding", "utf-8"), "создать блоб").optString("sha")
        val tree = post(
            token, "$API/repos/$repo/git/trees",
            JSONObject().put("tree", JSONArray().put(JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", blob))),
            "создать дерево",
        ).optString("sha")
        val commit = post(
            token, "$API/repos/$repo/git/commits",
            JSONObject().put("message", message).put("tree", tree).put("parents", JSONArray()),
            "создать коммит",
        ).optString("sha")
        post(token, "$API/repos/$repo/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", commit), "создать ветку")
        return blob
    }

    private fun post(token: String, url: String, body: JSONObject, what: String): JSONObject {
        val req = request(url, token).post(body.toString().toRequestBody(JSON)).build()
        return call(req) { resp ->
            if (!resp.isSuccessful) throw failure(resp, what)
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun request(url: String, token: String?): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "PravkaBoard/${BuildConfig.VERSION_NAME}")
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        return b
    }

    private inline fun <T> call(req: Request, handle: (Response) -> T): T = try {
        http.newCall(req).execute().use(handle)
    } catch (e: IOException) {
        throw GitHubException("Нет связи с GitHub: ${e.message ?: "проверь интернет"}")
    }

    private fun failure(resp: Response, what: String): GitHubException = failure(resp.code, resp.body?.string() ?: "", what)

    private fun failure(code: Int, body: String, what: String): GitHubException {
        val detail = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
        return when (code) {
            401 -> GitHubException("GitHub не принял токен: проверь его в настройках")
            403 -> GitHubException("GitHub отказал ($what): ${detail.ifBlank { "нет прав или превышен лимит запросов" }}")
            404 -> GitHubException("Нет доступа к репозиторию ($what): токену нужны права Contents: Read and write")
            else -> GitHubException("GitHub ответил $code ($what)${if (detail.isNotBlank()) ": $detail" else ""}")
        }
    }

    companion object {
        const val API = "https://api.github.com"
        const val BRANCH = "data"
        const val FILE = "pravka-data.json"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
