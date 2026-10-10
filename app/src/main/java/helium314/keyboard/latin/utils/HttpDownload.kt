// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads [url] into [out], reporting progress in percent if the size is known. Blocking.
 * raw.githubusercontent.com is cached for 5 minutes, so a cache-busting parameter is added.
 */
@Throws(IOException::class)
fun httpDownload(url: String, out: OutputStream, onProgress: (Int) -> Unit = { }) {
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
