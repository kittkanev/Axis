package io.github.kittkanev.axis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

private val wakePrefix = Regex("""^[\s,.!?]*(?:аксис|(?:hey\s+)?axis)(?![\p{L}\p{N}])(?:[\s,.!?:—-]+|$)(.*)$""", RegexOption.IGNORE_CASE)

internal fun wakeCommand(utterance: String): String? =
    wakePrefix.matchEntire(utterance)?.groupValues?.get(1)?.trim()

internal class WakeWordListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val detected: (String) -> Unit,
    private val state: (WakeStatus) -> Unit,
) {
    // Accessed only on the main dispatcher, including microphone handoff to dictation.
    private var recording: AudioRecord? = null
    private var language: String? = null
    private var job: Job? = null

    fun update(nextLanguage: String?) {
        if (nextLanguage == language) return
        job?.cancel()
        stopRecording()
        language = nextLanguage
        state(WakeStatus.PAUSED)
        job = nextLanguage?.let { locale ->
            scope.launch {
                // Avoid opening the mic during the overlay's opening sound.
                delay(650)
                val directory = WakeModels.installed(context, locale)
                if (directory == null) {
                    state(WakeStatus.NEEDS_LANGUAGE)
                    return@launch
                }
                try {
                    val command = listen(directory)
                    language = null
                    state(WakeStatus.PAUSED)
                    detected(command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    state(WakeStatus.UNAVAILABLE)
                } catch (_: LinkageError) {
                    state(WakeStatus.UNAVAILABLE)
                }
            }
        }
    }

    private fun stopRecording() {
        val previous = recording ?: return
        recording = null
        runCatching { previous.stop() }
    }

    @SuppressLint("MissingPermission") // Arm is only available after the user grants RECORD_AUDIO.
    private suspend fun listen(directory: File): String = withContext(Dispatchers.IO) {
        val rate = 16_000
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        Model(directory.absolutePath).use { model ->
            Recognizer(model, rate.toFloat()).use { recognizer ->
                coroutineContext.ensureActive()
                val audio = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(minimum, 6_400))
                    .build()
                try {
                    check(audio.state == AudioRecord.STATE_INITIALIZED)
                    withContext(Dispatchers.Main.immediate) {
                        audio.startRecording()
                        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        recording = audio
                        state(WakeStatus.LISTENING)
                    }
                    val chunk = ShortArray(1_600)
                    var command: String? = null
                    while (command == null) {
                        coroutineContext.ensureActive()
                        val read = audio.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                        coroutineContext.ensureActive()
                        check(read > 0)
                        if (recognizer.acceptWaveForm(chunk, read)) {
                            command = wakeCommand(JSONObject(recognizer.result).optString("text"))
                        }
                    }
                    command
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (recording === audio) stopRecording()
                    }
                    audio.release()
                }
            }
        }
    }
}

internal enum class WakeStatus { PAUSED, LISTENING, NEEDS_LANGUAGE, UNAVAILABLE }
