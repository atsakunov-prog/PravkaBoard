// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.pravka

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

// Invisible one-shot: an InputMethodService can't request a runtime
// permission, so the first dictation tap bounces here. After the grant the
// owner taps the mic key again.
class MicPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 23) { finish(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            applicationContext,
            if (granted) "Микрофон разрешён — нажми кнопку диктовки ещё раз" else "Без микрофона диктовка не работает",
            Toast.LENGTH_LONG,
        ).show()
        finish()
    }
}
