package io.github.kittkanev.axis

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class AssistantInput(
    val draft: String = "",
    val showEditor: Boolean = false,
    val reply: String = "",
    val error: String? = null,
    val listening: Boolean = false,
    val audioLevel: Float = 0f,
    val finishingSpeech: Boolean = false,
    val waiting: Boolean = false,
)

internal class AssistantController(
    private val context: Context,
    private val app: AxisApplication,
    private val openAxis: (Boolean) -> Unit,
    private val closePanel: () -> Unit,
    private val launchApp: (DeviceApp) -> Boolean = { openApp(context, it) },
) {
    private val current = MutableStateFlow(AssistantInput())
    val state = current.asStateFlow()
    val voice = VoiceOutput(context)
    private var recognizer: SpeechRecognizer? = null
    private var request: Job? = null
    private var visible = false
    private var requestNumber = 0
    private val main = Handler(Looper.getMainLooper())
    private val recognitionTimeout = Runnable {
        disposeRecognition()
        fail("Время записи истекло. Попробуйте ещё раз.")
    }

    fun show() {
        visible = true
        current.value = AssistantInput()
        if (app.preferences.state.value.autoListen && hasMicrophone() && unlocked()) startListening()
    }

    fun hide() {
        visible = false
        cancel()
        voice.close()
    }

    fun close() {
        hide()
        closePanel()
    }

    fun openApp() {
        hide()
        openAxis(false)
    }

    fun edit(text: String) = current.update { it.copy(draft = text.take(MAX_PROMPT_LENGTH), error = null) }
    fun showEditor() = current.update { it.copy(showEditor = true) }

    fun startOrStop() {
        when {
            current.value.listening -> {
                current.update { it.copy(listening = false, finishingSpeech = true) }
                recognizer?.stopListening()
            }
            current.value.waiting || current.value.finishingSpeech -> cancel()
            voice.speaking.value -> voice.stop()
            else -> startListening()
        }
    }

    private fun hasMicrophone(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun unlocked(): Boolean = !context.getSystemService(KeyguardManager::class.java).isDeviceLocked

    private fun startListening() {
        if (!visible || !unlocked()) {
            fail("Разблокируйте телефон, чтобы говорить с Axis.")
            return
        }
        if (!hasMicrophone()) {
            hide()
            openAxis(true)
            return
        }
        voice.stop()
        val instance = try {
            createDeviceRecognizer(context)
        } catch (_: SecurityException) {
            fail("Android не разрешил запустить распознавание речи.")
            return
        }
        if (instance == null) {
            fail("На устройстве нет службы распознавания речи. Введите сообщение вручную.")
            return
        }
        recognizer = instance
        current.update { it.copy(listening = true, audioLevel = 0f, draft = "", showEditor = false, error = null, reply = "") }
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                if (recognizer === instance && rmsdB.isFinite()) {
                    current.update { it.copy(audioLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (recognizer === instance) current.update { it.copy(listening = false, finishingSpeech = true) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(results: Bundle) {
                if (recognizer === instance) results.firstRecognitionResult().takeIf { it.isNotBlank() }?.let(::edit)
            }
            override fun onResults(results: Bundle) {
                if (recognizer !== instance) return
                disposeRecognition()
                val words = results.firstRecognitionResult()
                if (words.isBlank()) {
                    fail("Речь не распознана.")
                } else {
                    edit(words)
                    if (app.preferences.state.value.autoSend) submit(voiceTurn = true)
                }
            }
            override fun onError(error: Int) {
                if (recognizer !== instance) return
                disposeRecognition()
                fail(recognitionError(error))
            }
        })
        try {
            instance.startListening(dictationIntent(app.preferences.state.value.recognitionLanguage))
            main.postDelayed(recognitionTimeout, 30_000)
        } catch (_: SecurityException) {
            disposeRecognition()
            fail("Android не разрешил использовать микрофон.")
        }
    }

    fun send() = submit(voiceTurn = true)

    private fun submit(voiceTurn: Boolean) {
        if (!visible || !unlocked()) {
            fail("Разблокируйте телефон, чтобы отправить запрос.")
            return
        }
        val text = current.value.draft.trim()
        if (text.isEmpty() || current.value.waiting || recognizer != null) return
        val requestedApp = directAppQuery(text)
        if (requestedApp != null) {
            try {
                launch(requestedApp(context, requestedApp))
            } catch (e: AxisException) {
                fail(e.message.orEmpty())
            }
            return
        }
        val model = app.workspace
        val snapshot = model.state.value
        if (snapshot.operation != null) {
            fail("Axis занят. Повторите через несколько секунд.")
            return
        }
        val profile = snapshot.workspace.active
        if (profile == null) {
            fail("Добавьте профиль модели в Axis.")
            return
        }

        val token = ++requestNumber
        voice.stop()
        current.update { it.copy(waiting = true, reply = "", error = null) }
        var replied = false
        val job = model.send(text, voiceTurn = voiceTurn, openModelApp = ::launch, onReply = { reply ->
            replied = true
            if (visible && token == requestNumber) {
                current.update { it.copy(draft = "", reply = reply, waiting = false) }
                if (app.preferences.state.value.autoSpeak) model.speak(voice, profile, reply, profile.voice, ::fail)
            }
        }, onSaved = {})
        request = job
        if (job == null) {
            current.update { it.copy(waiting = false) }
            fail("Чат недоступен. Откройте Axis и проверьте состояние профиля.")
            return
        }
        job.invokeOnCompletion {
            if (visible && token == requestNumber && !replied) {
                current.update { it.copy(waiting = false, error = model.state.value.notice ?: "Запрос остановлен.") }
            }
        }
    }

    private fun launch(target: DeviceApp): Boolean {
        if (!visible || !unlocked()) return false
        if (!launchApp(target)) throw AxisException("Не удалось открыть «${target.label}».")
        visible = false
        closePanel()
        return true
    }

    fun speakReply() {
        val profile = app.workspace.state.value.workspace.active ?: return
        val reply = current.value.reply.takeIf { it.isNotBlank() } ?: return
        if (voice.speaking.value) voice.stop()
        else app.workspace.speak(voice, profile, reply, profile.voice, ::fail)
    }

    private fun fail(message: String) = current.update { it.copy(error = message) }

    private fun disposeRecognition() {
        main.removeCallbacks(recognitionTimeout)
        val instance = recognizer
        recognizer = null
        instance?.destroy()
        current.update { it.copy(listening = false, finishingSpeech = false, audioLevel = 0f) }
    }

    private fun cancel() {
        requestNumber++
        if (app.workspace.state.value.operation != Operation.SAVING) request?.cancel()
        request = null
        disposeRecognition()
        voice.stop()
        current.update { it.copy(waiting = false) }
    }
}
