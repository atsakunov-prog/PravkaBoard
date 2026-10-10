// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.AppUpdater
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import java.text.DateFormat
import java.util.Date

@Composable
fun UpdatesScreen(
    onClickBack: () -> Unit,
) {
    val items = listOf(
        SettingsWithoutKey.UPDATE_STATUS,
        Settings.PREF_UPDATE_AUTO,
        Settings.PREF_UPDATE_MOBILE,
        Settings.PREF_UPDATE_BRANCH,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.updates_title),
        settings = items
    )
}

/** Short update status for the main settings screen. */
@Composable
fun updatesSummary(): String {
    val ctx = LocalContext.current
    val state by AppUpdater.state(ctx).collectAsState()
    val latest = state.latest
    return if (latest != null && AppUpdater.isNewer(ctx, latest))
        stringResource(R.string.update_available, latest.versionName)
    else stringResource(R.string.update_installed_version, BuildConfig.VERSION_NAME)
}

fun createUpdateSettings(context: Context) = listOf(
    Setting(context, SettingsWithoutKey.UPDATE_STATUS, R.string.update_check) {
        UpdateStatus(it)
    },
    Setting(context, Settings.PREF_UPDATE_AUTO, R.string.update_auto, R.string.update_auto_summary) { setting ->
        val requestNotifications = notificationPermissionRequest()
        SwitchPreference(setting, Defaults.PREF_UPDATE_AUTO) { if (it) requestNotifications() }
    },
    Setting(context, Settings.PREF_UPDATE_MOBILE, R.string.update_mobile) {
        SwitchPreference(it, Defaults.PREF_UPDATE_MOBILE)
    },
    Setting(context, Settings.PREF_UPDATE_BRANCH, R.string.update_branch) {
        TextInputPreference(it, Defaults.PREF_UPDATE_BRANCH,
            info = stringResource(R.string.update_branch_info, BuildConfig.BUILD_BRANCH.ifEmpty { "—" }))
    },
)

/** Returns a function that asks for the notification permission on Android 13+, if not granted yet. */
@Composable
private fun notificationPermissionRequest(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun UpdateStatus(setting: Setting) {
    val ctx = LocalContext.current
    val state by AppUpdater.state(ctx).collectAsState()
    val latest = state.latest
    val newer = latest != null && AppUpdater.isNewer(ctx, latest)

    // without the permission, the daily check can't say that an update is there
    val requestNotifications = notificationPermissionRequest()
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!askedForNotifications && ctx.prefs().getBoolean(Settings.PREF_UPDATE_AUTO, Defaults.PREF_UPDATE_AUTO)) {
            askedForNotifications = true
            requestNotifications()
        }
    }

    Preference(
        name = stringResource(R.string.update_installed),
        description = BuildConfig.VERSION_NAME + (if (BuildConfig.BUILD_BRANCH.isEmpty()) "" else " · " + BuildConfig.BUILD_BRANCH),
        onClick = { },
        icon = R.drawable.ic_settings_about_version
    )
    val status = when {
        state.checking -> stringResource(R.string.update_checking)
        state.error.isNotEmpty() -> stringResource(R.string.update_error, state.error)
        latest == null -> stringResource(R.string.update_never_checked)
        newer -> stringResource(R.string.update_found, latest.versionName, latest.builtAt)
        latest.versionCode > BuildConfig.VERSION_CODE ->
            stringResource(R.string.update_other_branch, latest.versionName, latest.branch)
        else -> stringResource(R.string.update_latest)
    }
    val checked = if (state.lastCheck > 0 && !state.checking)
        "\n" + stringResource(R.string.update_checked,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastCheck)))
    else ""
    Preference(
        name = setting.title,
        description = status + checked,
        onClick = { if (!state.checking) AppUpdater.checkInBackground(ctx) },
        icon = R.drawable.ic_settings_updates
    )
    if (!newer || latest == null) return
    val ready = state.ready
    when {
        state.progress >= 0 -> Preference(
            name = stringResource(R.string.update_downloading, state.progress),
            onClick = { },
        )
        ready != null -> Preference(
            name = stringResource(R.string.update_install, latest.versionName),
            description = if (AppUpdater.canInstall(ctx)) null else stringResource(R.string.update_allow_install),
            onClick = {
                val intent = if (AppUpdater.canInstall(ctx)) AppUpdater.installIntent(ctx, ready)
                    else AppUpdater.allowInstallIntent(ctx)
                AppUpdater.dismissNotification(ctx)
                try {
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(ctx, ctx.getString(R.string.update_install_failed, e.message), Toast.LENGTH_LONG).show()
                }
            },
        )
        else -> Preference(
            name = stringResource(R.string.update_download, latest.versionName),
            description = latest.commit.takeIf { it.isNotEmpty() }?.let { "${latest.branch} · $it" },
            onClick = { AppUpdater.downloadInBackground(ctx, latest) },
        )
    }
}

@Preview
@Composable
private fun Preview() {
    SettingsActivity.settingsContainer = SettingsContainer(LocalContext.current)
    Theme(previewDark) {
        Surface {
            UpdatesScreen { }
        }
    }
}
