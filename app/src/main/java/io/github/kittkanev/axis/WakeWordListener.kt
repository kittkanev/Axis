package io.github.kittkanev.axis

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

private val wakePrefix = Regex("""^[\s,.!?]*(?:аксис|axis)(?![\p{L}\p{N}])(?:[\s,.!?:—-]+|$)(.*)$""", RegexOption.IGNORE_CASE)

internal fun wakeCommand(utterance: String): String? =
    wakePrefix.matchEntire(utterance)?.groupValues?.get(1)?.trim()

internal fun hasLocalWakeRecognition(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

internal class WakeWordListener(
    private val context: Context,
    private val detected: (String) -> Unit,
    private val state: (WakeStatus) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var language: String? = null
    private var failures = 0
    private val next = Runnable { listen() }
    private val timeout = Runnable { restart(750) }

    fun update(nextLanguage: String?) {
        if (nextLanguage == language) return
        stopSession()
        language = nextLanguage
        failures = 0
        if (nextLanguage == null) state(WakeStatus.PAUSED)
        else handler.postDelayed(next, 650)
    }

    private fun listen() {
        val locale = language ?: return
        if (Build.VERSION.SDK_INT < 31 || !hasLocalWakeRecognition(context)) {
            unavailable()
            return
        }
        val instance = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (_: RuntimeException) {
            unavailable()
            return
        }
        recognizer = instance
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer === instance) state(WakeStatus.LISTENING)
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onResults(results: Bundle) {
                if (recognizer !== instance) return
                failures = 0
                val command = wakeCommand(results.firstRecognitionResult())
                if (command == null) {
                    restart(600)
                } else {
                    update(null)
                    detected(command)
                }
            }

            override fun onError(error: Int) {
                if (recognizer !== instance) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        failures = 0
                        restart(650)
                    }
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        unavailable(WakeStatus.NEEDS_LANGUAGE)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> unavailable()
                    else -> if (++failures < 4) restart(failures * 1_500L) else unavailable()
                }
            }
        })
        try {
            instance.startListening(dictationIntent(locale).apply {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
            handler.postDelayed(timeout, 30_000L)
        } catch (_: RuntimeException) {
            unavailable()
        }
    }

    private fun restart(delayMs: Long) {
        stopSession()
        handler.postDelayed(next, delayMs)
    }

    private fun unavailable(status: WakeStatus = WakeStatus.UNAVAILABLE) {
        stopSession()
        state(status)
    }

    private fun stopSession() {
        handler.removeCallbacks(next)
        handler.removeCallbacks(timeout)
        val old = recognizer
        recognizer = null
        old?.cancel()
        old?.destroy()
    }
}

internal enum class WakeStatus { PAUSED, LISTENING, NEEDS_LANGUAGE, UNAVAILABLE }
