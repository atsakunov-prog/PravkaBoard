package ru.tsakunov.pravka.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.tsakunov.pravka.data.Lang
import ru.tsakunov.pravka.domain.countLetters
import ru.tsakunov.pravka.ui.parseTimeInput
import ru.tsakunov.pravka.ui.theme.PravkaColors

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "Удалить",
    danger: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = if (danger) PravkaColors.Danger else PravkaColors.Ink)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ManualListDialog(onCreate: (title: String, text: String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Список слов вручную") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text("Название") },
                    placeholder = { Text("Например, Lesson 3") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Слова, по одному в строке") },
                    placeholder = { Text("hen - курица\ngoose - гусь\nseed - семя") },
                    minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
                Text("Разделитель: дефис или тире с пробелами, двоеточие или табуляция.", style = MaterialTheme.typography.bodySmall, color = PravkaColors.Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title, text) }, enabled = text.isNotBlank()) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ItemDialog(
    title: String,
    initialEn: String,
    initialRu: String,
    onSave: (en: String, ru: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var en by remember { mutableStateOf(initialEn) }
    var ru by remember { mutableStateOf(initialRu) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = en, onValueChange = { en = it }, label = { Text("English") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    supportingText = { if (en.isNotBlank()) Text("${countLetters(en)} букв") },
                )
                OutlinedTextField(
                    value = ru, onValueChange = { ru = it }, label = { Text("Русский") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    supportingText = { if (ru.isNotBlank()) Text("${countLetters(ru)} букв") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(en, ru) }, enabled = en.isNotBlank() || ru.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun RenameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Название списка") },
        text = {
            OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(title) }, enabled = title.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Результат, записанный на бумаге: язык, слово или число букв, время. */
@Composable
fun PaperDialog(onAdd: (lang: Lang, word: String?, letters: Int, ms: Long) -> Unit, onDismiss: () -> Unit) {
    var lang by remember { mutableStateOf(Lang.EN) }
    var word by remember { mutableStateOf("") }
    var lettersText by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }

    val lettersFromWord = countLetters(word)
    val letters = if (word.isNotBlank()) lettersFromWord else lettersText.toIntOrNull() ?: 0
    val ms = parseTimeInput(time)
    val valid = letters > 0 && ms != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Результат с бумаги") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Lang.entries.forEachIndexed { i, l ->
                        SegmentedButton(
                            selected = lang == l,
                            onClick = { lang = l },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = Lang.entries.size),
                        ) { Text(l.title()) }
                    }
                }
                OutlinedTextField(
                    value = word, onValueChange = { word = it }, label = { Text("Слово (если помнишь)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    value = if (word.isNotBlank()) lettersFromWord.toString() else lettersText,
                    onValueChange = { lettersText = it.filter { c -> c.isDigit() } },
                    label = { Text("Букв") }, singleLine = true, enabled = word.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = time, onValueChange = { time = it }, label = { Text("Время, мин:сек") }, placeholder = { Text("0:45") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    isError = time.isNotBlank() && ms == null,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(lang, word.ifBlank { null }, letters, ms!!) }, enabled = valid) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
