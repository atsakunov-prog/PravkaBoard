// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gesture typing needs Google's closed source library, which can't be part of this open source app.
 * It can be downloaded on request from the place the HeliBoard readme links to, and is only
 * installed if it is exactly the library HeliBoard expects (SHA-256 checksum in [JniUtils]).
 */
object GestureLibrary {
    private const val TAG = "GestureLibrary"
    private const val URL = "https://raw.githubusercontent.com/erkserkserks/openboard/" +
            "46fdf2b550035ca69299ce312fa158e7ade36967/app/src/main/jniLibs/%s/libjni_latinimegoogle.so"

    /** progress in percent while downloading, -1 otherwise */
    data class State(val progress: Int = -1, val error: String = "")

    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow
    private val downloading = AtomicBoolean(false)

    /** Downloads, verifies and installs the library for this device, then restarts the app to load it. */
    fun downloadInBackground(context: Context) {
        val appContext = context.applicationContext
        if (!downloading.compareAndSet(false, true)) return
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            val tempFile = File(appContext.filesDir, "tmplib")
            try {
                stateFlow.value = State(progress = 0)
                tempFile.delete()
                tempFile.outputStream().use { out ->
                    httpDownload(URL.format(Build.SUPPORTED_ABIS[0]), out) { stateFlow.value = State(progress = it) }
                }
                val checksum = ChecksumCalculator.checksum(tempFile) ?: ""
                if (checksum != JniUtils.expectedDefaultChecksum())
                    throw IOException(appContext.getString(R.string.gesture_library_download_wrong_file))
                install(appContext, tempFile, checksum)
            } catch (e: Exception) {
                Log.w(TAG, "could not download gesture library", e)
                tempFile.delete()
                stateFlow.value = State(error = if (e is java.net.UnknownHostException)
                    appContext.getString(R.string.update_error_no_network) else e.message ?: e.javaClass.simpleName)
            } finally {
                downloading.set(false)
            }
        }
    }

    /** Replaces the current library with [file], and restarts the app so the library gets loaded. */
    fun install(context: Context, file: File, checksum: String) {
        val libFile = File(context.filesDir, JniUtils.JNI_LIB_IMPORT_FILE_NAME)
        libFile.setWritable(true)
        libFile.delete()
        // store checksum in default preferences (see JniUtils)
        context.protectedPrefs().edit(commit = true) { putString(Settings.PREF_LIBRARY_CHECKSUM, checksum) }
        file.copyTo(libFile)
        libFile.setReadOnly() // as per recommendations in https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading
        file.delete()
        Runtime.getRuntime().exit(0) // exit will restart the app, so library will be loaded
    }
}
