package io.github.kittkanev.axis

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

internal data class InstalledVoice(val name: String, val language: String, val network: Boolean)

internal class VoiceOutput(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gemini = GeminiVoice()
    private var synthesis: Job? = null
    private var player: MediaPlayer? = null
    private var engine: TextToSpeech? = null
    private var pending: ((TextToSpeech?) -> Unit)? = null
    private var generation = 0
    private var playback: String? = null
    private val mutableVoices = MutableStateFlow<List<InstalledVoice>>(emptyList())
    val voices = mutableVoices.asStateFlow()
    private val mutableSpeaking = MutableStateFlow(false)
    val speaking = mutableSpeaking.asStateFlow()

    fun prepare() {
        if (engine != null) return
        val id = ++generation
        engine = TextToSpeech(context) { status ->
            main.post {
                if (id != generation) return@post
                val tts = engine
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    mutableVoices.value = tts.voices.orEmpty().filterNot {
                        it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
                    }.map { InstalledVoice(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired) }
                        .sortedWith(compareBy({ it.network }, { it.language }, { it.name }))
                    ready = true
                } else {
                    tts?.shutdown()
                    engine = null
                }
                val next = pending
                pending = null
                next?.invoke(if (ready) engine else null)
            }
        }
    }

    private var ready = false

    fun speakNeural(text: String, voice: String, style: SpeechStyle, key: suspend () -> String, error: (String) -> Unit) {
        speakAudio({ gemini.synthesize(key(), text, voice, style).wave() }, error)
    }

    fun speakAudio(load: suspend () -> ByteArray, error: (String) -> Unit) {
        stop()
        mutableSpeaking.value = true
        synthesis = scope.launch {
            try {
                val audio = load()
                withContext(Dispatchers.IO) {
                    val file = File.createTempFile("axis-speech-", ".wav", context.cacheDir)
                    try {
                        file.writeBytes(audio)
                        withContext(Dispatchers.Main) { playAudio(file, error) }
                    } finally {
                        file.delete()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AxisException) {
                stop()
                error(e.message.orEmpty())
            } catch (_: IOException) {
                stop()
                error("Не удалось воспроизвести звук.")
            }
        }
    }

    private fun playAudio(file: File, error: (String) -> Unit) {
        val audio = MediaPlayer()
        player = audio
        try {
            audio.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            file.inputStream().use { audio.setDataSource(it.fd) }
            audio.setOnPreparedListener { if (player === it) it.start() }
            audio.setOnCompletionListener { if (player === it) stop() }
            audio.setOnErrorListener { current, _, _ ->
                if (player === current) {
                    stop()
                    error("Не удалось воспроизвести звук.")
                }
                true
            }
            audio.prepareAsync()
        } catch (_: IllegalStateException) {
            stop()
            error("Не удалось воспроизвести звук.")
        }
    }

    fun speak(text: String, voice: ModelVoice, error: (String) -> Unit) {
        stop()
        if (!ready) {
            pending = { tts ->
                if (tts == null) error("Синтезатор речи Android недоступен.")
                else play(tts, text, voice, error)
            }
            prepare()
        } else {
            engine?.let { play(it, text, voice, error) }
        }
    }

    private fun play(tts: TextToSpeech, text: String, voice: ModelVoice, error: (String) -> Unit) {
        val locale = Locale.forLanguageTag(voice.language)
        val available = tts.voices.orEmpty()
        val chosen = available.firstOrNull { it.name == voice.name } ?: available
            .filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language &&
                it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .maxByOrNull { if (it.locale.country == locale.country) it.quality + 10_000 else it.quality }
        val configured = if (chosen != null) tts.setVoice(chosen) == TextToSpeech.SUCCESS
        else tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
        if (!configured) {
            error("Выбранный язык недоступен. Установите голос в настройках Android.")
            return
        }
        tts.setSpeechRate(voice.rate)
        tts.setPitch(voice.pitch)
        val token = UUID.randomUUID().toString()
        playback = token
        mutableSpeaking.value = true
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) { if (utteranceId == token) finish(token) }
            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String) = failed(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = failed(utteranceId)
            private fun failed(utteranceId: String) {
                if (utteranceId.startsWith(token)) finish(token) { error("Не удалось озвучить ответ.") }
            }
        })
        val parts = text.chunked(TextToSpeech.getMaxSpeechInputLength() / 2)
        parts.forEachIndexed { index, part ->
            val id = if (index == parts.lastIndex) token else "$token-$index"
            if (tts.speak(part, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
                stop()
                error("Не удалось озвучить ответ.")
                return
            }
        }
    }

    private fun finish(token: String, onError: (() -> Unit)? = null) {
        main.post {
            if (playback == token) {
                stop()
                onError?.invoke()
            }
        }
    }

    fun stop() {
        synthesis?.cancel()
        synthesis = null
        player?.release()
        player = null
        pending = null
        playback = null
        mutableSpeaking.value = false
        engine?.stop()
    }

    fun close() {
        stop()
        generation++
        ready = false
        engine?.shutdown()
        engine = null
    }
}
