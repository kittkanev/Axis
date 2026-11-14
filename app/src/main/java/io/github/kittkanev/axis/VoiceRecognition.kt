package io.github.kittkanev.axis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

internal fun createDeviceRecognizer(context: Context): SpeechRecognizer? {
    val candidates = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        .map { it.serviceInfo }.filter { it.packageName != context.packageName }
    val configured = android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        ?.let(ComponentName::unflattenFromString)
    val selected = candidates.firstOrNull { ComponentName(it.packageName, it.name) == configured }
    if (selected != null) return SpeechRecognizer.createSpeechRecognizer(context)
    if (android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    }
    val fallback = candidates.firstOrNull() ?: return null
    return SpeechRecognizer.createSpeechRecognizer(context, ComponentName(fallback.packageName, fallback.name))
}

internal fun dictationIntent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
}

internal fun Bundle.firstRecognitionResult(): String =
    getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

internal fun recognitionError(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Разрешите Axis доступ к микрофону в Android."
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не распознана. Попробуйте ещё раз."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        "Язык распознавания недоступен. Измените его в настройках Axis."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Распознавателю нужна сеть."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Микрофон занят распознавателем."
    else -> "Не удалось распознать речь."
}

// Android 10 and 11 use this component when initializing the selected assistant.
class AxisRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null
    private var current: Callback? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        if (current != null) {
            callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        val instance = try {
            val attributed = if (android.os.Build.VERSION.SDK_INT >= 31) {
                createContext(android.content.ContextParams.Builder().setNextAttributionSource(callback.callingAttributionSource).build())
            } else this
            createDeviceRecognizer(attributed)
        } catch (_: SecurityException) {
            null
        }
        if (instance == null) {
            callback.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        recognizer = instance
        current = callback
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle) = relay(callback) { readyForSpeech(params) }
            override fun onBeginningOfSpeech() = relay(callback) { beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = relay(callback) { rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = relay(callback) { endOfSpeech() }
            override fun onPartialResults(results: Bundle) = relay(callback) { partialResults(results) }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onResults(results: Bundle) = complete(callback) { results(results) }
            override fun onError(error: Int) = complete(callback) { error(error) }
        })
        try {
            instance.startListening(dictationIntent(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: "ru-RU"))
        } catch (_: SecurityException) {
            complete(callback) { error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
        }
    }

    override fun onStopListening(callback: Callback) { if (current === callback) recognizer?.stopListening() }
    override fun onCancel(callback: Callback) { if (current === callback) clear() }

    private inline fun relay(callback: Callback, action: Callback.() -> Unit) {
        if (current !== callback) return
        try {
            callback.action()
        } catch (_: android.os.RemoteException) {
            clear()
        }
    }

    private inline fun complete(callback: Callback, action: Callback.() -> Unit) {
        if (current !== callback) return
        relay(callback, action)
        clear()
    }

    private fun clear() {
        current = null
        recognizer?.destroy()
        recognizer = null
    }

    override fun onDestroy() {
        clear()
        super.onDestroy()
    }
}
