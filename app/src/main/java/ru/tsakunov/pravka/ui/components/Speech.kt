package ru.tsakunov.pravka.ui.components

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Озвучка слов и текстов голосом Android (без сети, движок Google TTS). */
class Speaker(context: Context) {
    private var ready = false
    private var pending: Triple<String, Locale, Boolean>? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        // Движок поднимается 0,5–3 с: фраза, запрошенная до этого, произносится, как только он готов.
        pending?.let { (t, l, s) -> pending = null; speak(t, l, s) }
    }

    fun speak(text: String, locale: Locale = Locale.US, slow: Boolean = true) {
        if (text.isBlank()) return
        if (!ready) { pending = Triple(text, locale, slow); return }
        val lang = tts.setLanguage(locale)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) return
        tts.setSpeechRate(if (slow) 0.85f else 1.0f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pravka-${System.currentTimeMillis()}")
    }

    fun stop() = runCatching { tts.stop() }
    fun shutdown() = runCatching { tts.stop(); tts.shutdown() }
}

@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    return speaker
}

/**
 * Обёртка над SpeechRecognizer: слушает одну реплику и отдаёт варианты расшифровки.
 * Распознаватель создаётся один раз и переиспользуется (cancel между репликами):
 * пересоздание на каждую реплику даёт ERROR_RECOGNIZER_BUSY на Android 12–14.
 */
class SpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(
        language: String,
        onPartial: (String) -> Unit,
        onResult: (List<String>) -> Unit,
        onError: (Int) -> Unit,
    ) {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        runCatching { r.cancel() }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) = onError(error)
            override fun onResults(results: Bundle?) {
                onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.toList() ?: emptyList())
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        r.startListening(intent)
    }

    /** Прервать текущее прослушивание, распознаватель остаётся готовым к следующему. */
    fun stop() {
        recognizer?.let { runCatching { it.cancel() } }
    }

    /** Освободить распознаватель при уходе с экрана. */
    fun release() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    companion object {
        fun describeError(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Проблема с микрофоном"
            SpeechRecognizer.ERROR_CLIENT -> "Распознавание прервалось, попробуй ещё раз"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания речи"
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не услышал, скажи ещё раз"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Микрофон занят, секунду"
            SpeechRecognizer.ERROR_SERVER -> "Сервер распознавания недоступен"
            else -> "Ошибка распознавания ($code)"
        }

        fun isRetryable(code: Int): Boolean =
            code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || code == SpeechRecognizer.ERROR_CLIENT
    }
}
