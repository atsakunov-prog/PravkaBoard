package ru.tsakunov.pravka.ui.components

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ru.tsakunov.pravka.ui.theme.PravkaColors
import java.io.File

/** Набор фото для отправки в модель: камера или галерея; предел снимков задаёт экран (maxItems). */
class PhotoPicker internal constructor(
    val pending: List<Uri>,
    val openCamera: () -> Unit,
    val pickFromGallery: () -> Unit,
    val clear: () -> Unit,
)

@Composable
fun rememberPhotoPicker(maxItems: Int = 8, onError: (String) -> Unit): PhotoPicker {
    val context = LocalContext.current
    // Переживают уничтожение процесса, пока открыта камера (Uri — Parcelable).
    var pending by rememberSaveable(stateSaver = listSaver<List<Uri>, String>(save = { it.map(Uri::toString) }, restore = { it.map(Uri::parse) })) {
        mutableStateOf(emptyList())
    }
    var cameraTarget by rememberSaveable { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val target = cameraTarget
        if (ok && target != null) pending = (pending + target).takeLast(maxItems)
        cameraTarget = null
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems)) { uris ->
        pending = (pending + uris).distinct().takeLast(maxItems)
    }

    return remember(pending) {
        PhotoPicker(
            pending = pending,
            openCamera = {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val file = File(dir, "page_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                cameraTarget = uri
                try {
                    takePicture.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    cameraTarget = null
                    onError("На телефоне нет приложения камеры, выбери фото из галереи")
                }
            },
            pickFromGallery = {
                try {
                    pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: ActivityNotFoundException) {
                    onError("На телефоне нет приложения для выбора фото")
                }
            },
            clear = { pending = emptyList() },
        )
    }
}

/** Кнопки камеры и галереи плюс строка «выбрано фото: N» с действием отправки. */
@Composable
fun PhotoPickerControls(
    picker: PhotoPicker,
    cameraLabel: String,
    submitLabel: String,
    submitting: Boolean,
    onSubmit: () -> Unit,
    extraButton: (@Composable RowScope.() -> Unit)? = null,
) {
    BigButton(cameraLabel, onClick = picker.openCamera, icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) })
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Из галереи", onClick = picker.pickFromGallery, modifier = Modifier.weight(1f))
        extraButton?.invoke(this)
    }
    if (picker.pending.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = PravkaColors.Grid)
        Spacer(Modifier.height(10.dp))
        Text(
            "Выбрано фото: ${picker.pending.size}. Можно добавить ещё страницу или отправить.",
            style = MaterialTheme.typography.bodyMedium, color = PravkaColors.Ink2,
        )
        Spacer(Modifier.height(10.dp))
        BigButton(submitLabel, onClick = onSubmit, container = PravkaColors.En, enabled = !submitting)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = picker.clear) { Text("Сбросить фото", color = PravkaColors.Muted) }
        }
    }
}

/** Диалог «модель работает» с подписью о времени. */
@Composable
fun WorkingDialog(title: String, photos: Int) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator(color = PravkaColors.En)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (photos > 1) "Разбираю $photos фото, это обычно 20–40 секунд." else "Обычно это 15–30 секунд.",
                    style = MaterialTheme.typography.bodyMedium, color = PravkaColors.Ink2,
                )
            }
        },
    )
}
