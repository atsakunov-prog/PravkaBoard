package ru.tsakunov.pravka.api

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.tsakunov.pravka.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val buildNumber: Int,
    val versionName: String,
    val title: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val releaseUrl: String,
)

class UpdateException(message: String) : Exception(message)

/**
 * Обновление из GitHub Releases: workflow кладёт APK в релиз с тегом build-N,
 * приложение сравнивает N со своим BuildConfig.BUILD_NUMBER, скачивает APK и
 * передаёт системному установщику. Ключ подписи общий, поэтому ставится поверх.
 */
class Updater(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    val currentBuild: Int get() = BuildConfig.BUILD_NUMBER

    /** Возвращает информацию о релизе, если он новее установленной сборки, иначе null. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PravkaBoard/${BuildConfig.VERSION_NAME}")
            .build()
        val body = try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@withContext null
                if (!resp.isSuccessful) throw UpdateException("GitHub ответил ${resp.code}")
                resp.body?.string() ?: ""
            }
        } catch (e: IOException) {
            throw UpdateException("Нет связи с GitHub: ${e.message ?: "проверь интернет"}")
        }
        val json = JSONObject(body)
        val tag = json.optString("tag_name")
        val build = tag.removePrefix("build-").toIntOrNull() ?: return@withContext null
        val assets = json.optJSONArray("assets") ?: return@withContext null
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { apk = a; break }
        }
        val asset = apk ?: return@withContext null
        val info = UpdateInfo(
            buildNumber = build,
            versionName = Regex("pravka-([^-]+)-build").find(asset.optString("name"))?.groupValues?.get(1) ?: "",
            title = json.optString("name"),
            downloadUrl = asset.optString("browser_download_url"),
            sizeBytes = asset.optLong("size"),
            releaseUrl = json.optString("html_url"),
        )
        if (info.buildNumber > currentBuild) info else null
    }

    /** Скачивает APK в кэш приложения; onProgress получает доли от 0 до 1. */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "pravka-build${info.buildNumber}.apk")
        val req = Request.Builder().url(info.downloadUrl).header("User-Agent", "PravkaBoard").build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw UpdateException("Не удалось скачать: ${resp.code}")
                val body = resp.body ?: throw UpdateException("Пустой ответ при скачивании")
                val total = if (body.contentLength() > 0) body.contentLength() else info.sizeBytes
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw UpdateException("Скачивание прервалось: ${e.message ?: "проверь интернет"}")
        }
        target
    }

    /** Открывает системный установщик. Android один раз попросит разрешить установку из этого приложения. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        const val REPO = "atsakunov-prog/PravkaBoard"
        const val RELEASES_URL = "https://github.com/$REPO/releases"
    }
}
