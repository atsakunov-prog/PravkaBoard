// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.UserManagerCompat
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SettingsActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-update from GitHub, the same way as the Pravka app: CI publishes every build to the
 * apk-builds branch together with build-info.txt, the keyboard looks there once a day and on
 * request, downloads the APK and hands it to the system installer.
 *
 * apk-builds is force-pushed by every branch of the repository, and a build of another branch
 * has a higher number without containing this branch's work. So updates must pass several locks:
 *  1. the build comes from our branch (BuildConfig.BUILD_BRANCH, or the one set in settings)
 *  2. the APK file name is taken from build-info.txt
 *  3. the downloaded APK is this package with the announced versionCode, and signed with the same
 *     key as the installed app (otherwise Android refuses to install it over the current version)
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val INFO_URL = "https://raw.githubusercontent.com/atsakunov-prog/PravkaBoard/apk-builds/build-info.txt"
    private const val FALLBACK_APK = "pravkaboard-debug.apk"
    private const val CHECK_PERIOD_MILLIS = 24 * 3_600_000L
    // a failed automatic download (no space, network gone) should not be retried on every keyboard start
    private const val RETRY_MILLIS = 3_600_000L
    private const val MIN_APK_SIZE = 1_000_000L
    private const val DIR = "update"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 46
    const val EXTRA_OPEN_UPDATES = "open_updates"

    // internal state, separate from the settings so it's not part of a settings backup
    private const val PREFS = "updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_INFO = "info"
    private const val KEY_TRY_AT = "try_at"
    private const val KEY_TOLD = "told"

    /** What build-info.txt in apk-builds describes. */
    data class BuildInfo(
        val versionName: String,
        val versionCode: Int,
        val commit: String,
        val branch: String,
        val builtAt: String,
        val apkName: String,
    )

    data class State(
        val latest: BuildInfo? = null, // null: not checked yet, or no valid answer
        val checking: Boolean = false,
        val progress: Int = -1, // 0..100 while downloading
        val ready: File? = null, // downloaded and verified APK
        val lastCheck: Long = 0L,
        val error: String = "",
    )

    private val stateFlow = MutableStateFlow(State())
    private val loaded = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)

    fun state(context: Context): StateFlow<State> {
        if (loaded.compareAndSet(false, true)) {
            val prefs = internalPrefs(context)
            stateFlow.value = stateFlow.value.copy(
                latest = parseInfo(prefs.getString(KEY_INFO, "").orEmpty()),
                lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L),
            )
        }
        return stateFlow
    }

    /** The branch whose builds we accept: our own, unless another one is set in settings. */
    fun line(context: Context): String =
        context.prefs().getString(Settings.PREF_UPDATE_BRANCH, Defaults.PREF_UPDATE_BRANCH)!!.trim()
            .ifEmpty { BuildConfig.BUILD_BRANCH }

    fun isNewer(context: Context, build: BuildInfo) = isNewer(build, BuildConfig.VERSION_CODE, line(context))

    /** A build of another branch has a higher number, but is not newer: it lacks our branch's work. */
    fun isNewer(build: BuildInfo, installedVersionCode: Int, line: String) =
        build.versionCode > installedVersionCode && isSameLine(build, line)

    fun isSameLine(build: BuildInfo, line: String) =
        build.branch.isBlank() || line.isBlank() || build.branch.equals(line, ignoreCase = true)

    fun parseInfo(text: String): BuildInfo? {
        val map = text.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0) line.take(i).trim() to line.substring(i + 1).trim() else null
        }.toMap()
        val versionCode = map["versionCode"]?.toIntOrNull() ?: return null
        val apk = map["apk"]?.takeIf { it.endsWith(".apk") && !it.contains('/') && !it.contains("..") }
        return BuildInfo(
            versionName = map["versionName"]?.takeIf { it.isNotBlank() } ?: versionCode.toString(),
            versionCode = versionCode,
            commit = map["commit"].orEmpty().take(7),
            branch = map["branch"].orEmpty(),
            builtAt = map["builtAt"].orEmpty(),
            apkName = apk ?: FALLBACK_APK,
        )
    }

    // ---- checking ----

    /** Fetches build-info.txt. Blocking, don't call on the main thread. */
    fun check(context: Context): BuildInfo? {
        state(context)
        if (!checking.compareAndSet(false, true)) return stateFlow.value.latest
        stateFlow.value = stateFlow.value.copy(checking = true, error = "")
        try {
            val now = System.currentTimeMillis()
            val text = String(fetch(INFO_URL, 64 * 1024) { }, Charsets.UTF_8)
            val build = parseInfo(text) ?: throw IOException("build-info.txt is invalid")
            internalPrefs(context).edit {
                putLong(KEY_LAST_CHECK, now)
                putString(KEY_INFO, text.take(1000))
            }
            stateFlow.value = stateFlow.value.copy(
                latest = build, checking = false, lastCheck = now, error = "", ready = readyFile(context, build)
            )
            return build
        } catch (e: Exception) {
            Log.i(TAG, "update check failed: $e")
            stateFlow.value = stateFlow.value.copy(checking = false, error = readable(context, e))
            return null
        } finally {
            checking.set(false)
        }
    }

    fun checkInBackground(context: Context) {
        val appContext = context.applicationContext
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { check(appContext) }
    }

    // ---- downloading ----

    /** Downloads and verifies the APK. Blocking, don't call on the main thread. */
    fun download(context: Context, build: BuildInfo): File? {
        state(context)
        if (!downloading.compareAndSet(false, true)) return null
        try {
            readyFile(context, build)?.let { return it }
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val target = File(dir, "pravkaboard-${build.versionCode}.apk")
            val part = File(dir, target.name + ".part")
            stateFlow.value = stateFlow.value.copy(progress = 0, error = "")
            var shown = -1
            part.outputStream().use { out ->
                fetchTo(INFO_URL.substringBeforeLast('/') + "/" + build.apkName, out) { percent ->
                    if (percent != shown) {
                        shown = percent
                        stateFlow.value = stateFlow.value.copy(progress = percent)
                    }
                }
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("could not rename download")
            // the branch may have been overwritten while downloading, or the build may be signed with another key
            val problem = verify(context, target, build.versionCode)
            if (problem != null) {
                target.delete()
                throw IOException(problem)
            }
            dir.listFiles()?.forEach { if (it != target) it.delete() }
            stateFlow.value = stateFlow.value.copy(ready = target, progress = -1)
            return target
        } catch (e: Exception) {
            Log.w(TAG, "update download failed: $e")
            stateFlow.value = stateFlow.value.copy(progress = -1, error = readable(context, e))
            return null
        } finally {
            downloading.set(false)
        }
    }

    fun downloadInBackground(context: Context, build: BuildInfo) {
        val appContext = context.applicationContext
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { download(appContext, build) }
    }

    private fun readyFile(context: Context, build: BuildInfo): File? {
        if (!isNewer(context, build)) return null
        val file = File(File(context.cacheDir, DIR), "pravkaboard-${build.versionCode}.apk")
        return if (file.isFile && verify(context, file, build.versionCode) == null) file else null
    }

    /** Returns null if the APK can be installed over the current app, or the reason why not. */
    private fun verify(context: Context, file: File, versionCode: Int): String? {
        if (file.length() < MIN_APK_SIZE) return context.getString(R.string.update_error_wrong_apk)
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val archive = runCatching { pm.getPackageArchiveInfo(file.absolutePath, flags) }.getOrNull()
        if (archive == null || archive.packageName != context.packageName)
            return context.getString(R.string.update_error_wrong_apk)
        if (archive.versionCodeCompat() != versionCode)
            return context.getString(R.string.update_error_branch_changed)
        if (Build.VERSION.SDK_INT >= 28) {
            val installed = runCatching {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }.getOrNull()
            val installedSigners = installed?.signingInfo?.apkContentsSigners?.toSet()
            val newSigners = archive.signingInfo?.apkContentsSigners?.toSet()
            if (installedSigners != null && newSigners != null && installedSigners != newSigners)
                return context.getString(R.string.update_error_other_key)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat() = if (Build.VERSION.SDK_INT >= 28) longVersionCode.toInt() else versionCode

    // ---- installing ----

    /** Android 8+ asks the user once to allow installing apps from this app. */
    fun canInstall(context: Context) =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun allowInstallIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- daily automatic check ----

    /** Called when the keyboard is shown, does the actual work in background once a day. */
    @JvmStatic
    fun onKeyboardShown(context: Context) {
        val prefs = context.prefs()
        if (!prefs.getBoolean(Settings.PREF_UPDATE_AUTO, Defaults.PREF_UPDATE_AUTO)) return
        if (!UserManagerCompat.isUserUnlocked(context)) return
        val now = System.currentTimeMillis()
        if (now - internalPrefs(context).getLong(KEY_LAST_CHECK, 0L) < CHECK_PERIOD_MILLIS) return
        if (checking.get() || downloading.get()) return
        val appContext = context.applicationContext
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { autoUpdate(appContext) }
    }

    private fun autoUpdate(context: Context) {
        val build = check(context) ?: return
        if (!isNewer(context, build)) {
            File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() } // updated, the APK is not needed any more
            return
        }
        var ready = readyFile(context, build)
        val internal = internalPrefs(context)
        val now = System.currentTimeMillis()
        if (ready == null && mayDownloadNow(context) && now - internal.getLong(KEY_TRY_AT, 0L) >= RETRY_MILLIS) {
            internal.edit { putLong(KEY_TRY_AT, now) }
            ready = download(context, build)
        }
        if (ready != null) {
            notifyOnce(context, "${build.versionCode}:ready",
                context.getString(R.string.update_notification_ready, build.versionName),
                context.getString(R.string.update_notification_ready_text),
                installIntent(context, ready))
        } else {
            notifyOnce(context, "${build.versionCode}:found",
                context.getString(R.string.update_notification_found, build.versionName),
                context.getString(R.string.update_notification_found_text),
                Intent(context, SettingsActivity::class.java)
                    .putExtra(EXTRA_OPEN_UPDATES, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun mayDownloadNow(context: Context): Boolean {
        if (context.prefs().getBoolean(Settings.PREF_UPDATE_MOBILE, Defaults.PREF_UPDATE_MOBILE)) return true
        val cm = ContextCompat.getSystemService(context, ConnectivityManager::class.java) ?: return false
        return !cm.isActiveNetworkMetered
    }

    private fun notifyOnce(context: Context, mark: String, title: String, text: String, intent: Intent) {
        val internal = internalPrefs(context)
        if (internal.getString(KEY_TOLD, "") == mark) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.updates_title))
                .build()
        )
        val pendingIntent = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_update)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            internal.edit { putString(KEY_TOLD, mark) }
        } catch (e: SecurityException) {
            Log.w(TAG, "could not show update notification", e)
        }
    }

    fun dismissNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    // ---- networking ----

    private fun fetch(url: String, maxBytes: Int, onProgress: (Int) -> Unit): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fetchTo(url, out, onProgress)
        if (out.size() > maxBytes) throw IOException("response too large")
        return out.toByteArray()
    }

    // raw.githubusercontent.com is cached for 5 minutes, a manual check must see the current state
    private fun fetchTo(url: String, out: java.io.OutputStream, onProgress: (Int) -> Unit) {
        val connection = URL("$url?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLength.toLong()
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readable(context: Context, e: Exception): String = when (e) {
        is java.net.UnknownHostException -> context.getString(R.string.update_error_no_network)
        is java.net.SocketTimeoutException -> context.getString(R.string.update_error_timeout)
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun internalPrefs(context: Context): SharedPreferences {
        val storageContext = if (Build.VERSION.SDK_INT >= 24) context.createDeviceProtectedStorageContext() else context
        return storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
