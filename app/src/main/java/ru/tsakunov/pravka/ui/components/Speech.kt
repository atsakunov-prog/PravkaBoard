package ru.tsakunov.pravka.ui.components

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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

/** Есть ли у распознавателя Google английский язык и где: на телефоне, в сети или ещё не скачан. */
data class EnglishSupport(val installed: Boolean, val downloadable: Boolean, val pending: Boolean, val online: Boolean, val error: String? = null) {
    val summary: String
        get() = when {
            error != null -> error
            installed -> "English скачан на телефон: распознавание идёт без сети и точнее"
            pending -> "English скачивается на телефон"
            downloadable && online -> "English есть только через интернет; можно скачать на телефон"
            downloadable -> "English можно скачать на телефон"
            online -> "English распознаётся только через интернет"
            else -> "Распознаватель не сообщил про English"
        }
}

/** Что сейчас слушает распознавание: телефон или внешний микрофон (наушники). */
data class MicRoute(
    /** Есть ли подключённый внешний вход (Bluetooth-наушники, гарнитура). */
    val externalName: String?,
    /** Удалось ли завернуть распознавание на встроенный микрофон телефона. */
    val forcedPhoneMic: Boolean,
) {
    val label: String
        get() = when {
            externalName == null -> "микрофон телефона"
            forcedPhoneMic -> "микрофон телефона (наушники «$externalName» не слушаем)"
            else -> "микрофон наушников «$externalName»: лучше отключить Bluetooth или снять их"
        }
}

/**
 * Обёртка над SpeechRecognizer: слушает одну реплику и отдаёт варианты расшифровки.
 * Распознаватель создаётся один раз и переиспользуется (cancel между репликами):
 * пересоздание на каждую реплику даёт ERROR_RECOGNIZER_BUSY на Android 12–14.
 *
 * Телефонный микрофон при наушниках: когда подключён Bluetooth-вход, система отдаёт распознаванию микрофон
 * наушников, а ребёнок говорит в телефон. На Android 13+ мы сами пишем звук со встроенного микрофона
 * (AudioRecord с предпочтительным устройством) и отдаём его распознавателю через EXTRA_AUDIO_SOURCE.
 * Если распознаватель источник не читает, за полторы секунды это видно по переполненной трубе:
 * запись останавливается, флаг «не поддерживается» запоминается через onPipeUnsupported.
 */
class SpeechInput(
    private val context: Context,
    private val preferPhoneMic: () -> Boolean = { true },
    private val pipeUnsupported: () -> Boolean = { false },
    private val onPipeUnsupported: () -> Unit = {},
) {
    private var recognizer: SpeechRecognizer? = null
    private var feeder: MicFeeder? = null
    private val audio: AudioManager? get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Сейчас звук идёт через нашу трубу со встроенного микрофона. */
    val usingPipe: Boolean get() = feeder != null

    /** Подключённый внешний вход (наушники), если есть. */
    fun externalInput(): AudioDeviceInfo? = audio?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull { d ->
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun builtInMic(): AudioDeviceInfo? = audio?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    private fun canForcePhoneMic(): Boolean =
        Build.VERSION.SDK_INT >= 33 && preferPhoneMic() && !pipeUnsupported() && externalInput() != null && builtInMic() != null

    /** Куда сейчас пойдёт звук, для подписи под микрофоном. */
    fun route(): MicRoute = MicRoute(externalInput()?.productName?.toString()?.ifBlank { "наушники" }, canForcePhoneMic())

    /**
     * silenceMs — сколько тишины считать концом реплики (подсказка движку; для чтения текста ребёнком
     * ставим больше, чем для одного слова). onBegin/onEnd — моменты начала и конца речи по данным движка.
     * biasing — слова, к которым распознавателю стоит склоняться (Android 13+, движок может игнорировать).
     */
    fun start(
        language: String,
        onPartial: (String) -> Unit,
        onResult: (List<String>) -> Unit,
        onError: (Int) -> Unit,
        silenceMs: Long? = null,
        onBegin: () -> Unit = {},
        onEnd: () -> Unit = {},
        biasing: List<String> = emptyList(),
    ) {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        runCatching { r.cancel() }
        stopFeeder()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() = onBegin()
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onEnd()
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) { stopFeeder(); onError(error) }
            override fun onResults(results: Bundle?) {
                stopFeeder()
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
            if (silenceMs != null) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            }
            if (Build.VERSION.SDK_INT >= 33 && biasing.isNotEmpty()) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing.distinct().take(200)))
            }
        }
        if (canForcePhoneMic()) {
            val f = MicFeeder.open(builtInMic(), onUnsupported = { stopFeeder(); onPipeUnsupported() })
            if (f != null) {
                feeder = f
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, f.readEnd)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicFeeder.SAMPLE_RATE)
            }
        }
        r.startListening(intent)
        feeder?.startWriting()
    }

    /** Закончить реплику и получить результат (кнопка «сказал»). */
    fun finish() {
        feeder?.finishInput()
        recognizer?.let { runCatching { it.stopListening() } }
    }

    /** Прервать текущее прослушивание без результата, распознаватель остаётся готовым к следующему. */
    fun stop() {
        stopFeeder()
        recognizer?.let { runCatching { it.cancel() } }
    }

    /** Освободить распознаватель при уходе с экрана. */
    fun release() {
        stopFeeder()
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    private fun stopFeeder() {
        feeder?.close()
        feeder = null
    }

    private fun englishIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
    }

    /** Отдельный распознаватель для проверок и загрузки: рабочий не трогаем. On-device, если он есть. */
    private fun probeRecognizer(): SpeechRecognizer? = runCatching {
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
    }.getOrNull()

    /**
     * Проверка английского у распознавателя (Android 13+). Колбэк приходит на главном потоке.
     * На старых версиях и при отказе движка — ответ с error.
     */
    fun checkEnglish(onResult: (EnglishSupport) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { onResult(EnglishSupport(false, false, false, false, "Проверка языков доступна с Android 13")); return }
        val r = probeRecognizer() ?: run { onResult(EnglishSupport(false, false, false, false, "Распознаватель недоступен")); return }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        try {
            r.checkRecognitionSupport(
                englishIntent(),
                { main.post(it) },
                object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(support: android.speech.RecognitionSupport) {
                        fun List<String>.en() = any { it.lowercase().startsWith("en") }
                        onResult(
                            EnglishSupport(
                                installed = support.installedOnDeviceLanguages.en(),
                                downloadable = support.supportedOnDeviceLanguages.en(),
                                pending = support.pendingOnDeviceLanguages.en(),
                                online = support.onlineLanguages.en(),
                            ),
                        )
                        runCatching { r.destroy() }
                    }
                    override fun onError(error: Int) {
                        onResult(EnglishSupport(false, false, false, false, "Движок не ответил про языки (код $error)"))
                        runCatching { r.destroy() }
                    }
                },
            )
        } catch (e: Exception) {
            onResult(EnglishSupport(false, false, false, false, "Не удалось проверить: ${e.message ?: e.javaClass.simpleName}"))
            runCatching { r.destroy() }
        }
    }

    /** Просит движок скачать английскую модель на телефон (Android 13+). true — запрос ушёл. */
    fun downloadEnglish(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val r = probeRecognizer() ?: return false
        return runCatching { r.triggerModelDownload(englishIntent()); true }.getOrDefault(false).also {
            // Загрузка живёт в сервисе Google, наш экземпляр больше не нужен.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { r.destroy() } }, 2_000)
        }
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

/**
 * Пишет звук со встроенного микрофона в трубу, читаемую распознавателем.
 * Запись 16 кГц, моно, PCM16 — формат по умолчанию для EXTRA_AUDIO_SOURCE.
 */
private class MicFeeder private constructor(
    private val record: AudioRecord,
    val readEnd: ParcelFileDescriptor,
    private val writeEnd: ParcelFileDescriptor,
    private val onUnsupported: () -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun startWriting() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "pravka-mic-feeder").also { it.start() }
    }

    private fun loop() {
        val buf = ByteArray(SAMPLE_RATE / 10 * 2) // 100 мс
        val fd = writeEnd.fileDescriptor
        var blockedSince = 0L
        try {
            runCatching { Os.fcntlInt(fd, OsConstants.F_SETFL, OsConstants.O_NONBLOCK) }
            record.startRecording()
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) break else continue }
                var off = 0
                while (off < n && running) {
                    try {
                        off += Os.write(fd, buf, off, n - off)
                        blockedSince = 0L
                    } catch (e: ErrnoException) {
                        if (e.errno != OsConstants.EAGAIN) throw e
                        // Труба полна: распознаватель её не читает. Полторы секунды — и сдаёмся.
                        val now = SystemClock.elapsedRealtime()
                        if (blockedSince == 0L) blockedSince = now
                        if (now - blockedSince > UNSUPPORTED_AFTER_MS) { running = false; onUnsupported(); return }
                        Thread.sleep(20)
                    }
                }
            }
        } catch (_: Exception) {
            // Запись сорвалась: распознаватель просто получит конец потока.
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { writeEnd.close() }
        }
    }

    /** Конец реплики по кнопке: закрываем запись, распознаватель видит конец потока. */
    fun finishInput() {
        running = false
    }

    fun close() {
        running = false
        runCatching { thread?.join(300) }
        runCatching { writeEnd.close() }
        runCatching { readEnd.close() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val UNSUPPORTED_AFTER_MS = 1_500L

        fun open(device: AudioDeviceInfo?, onUnsupported: () -> Unit): MicFeeder? {
            if (device == null) return null
            return try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2))
                if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return null }
                if (!record.setPreferredDevice(device)) { record.release(); return null }
                val pipe = ParcelFileDescriptor.createPipe()
                MicFeeder(record, pipe[0], pipe[1], onUnsupported)
            } catch (_: Exception) {
                null
            }
        }
    }
}
