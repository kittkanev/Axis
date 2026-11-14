package io.github.kittkanev.axis

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val model: AxisViewModel by viewModels()
    private var voice: TextToSpeech? = null
    private var voiceReady = false
    private var pendingSpeech: Pair<String, (String) -> Unit>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AxisTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val notices = remember { SnackbarHostState() }
                var localNotice by remember { mutableStateOf<String?>(null) }
                val recognizer = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                            ?.firstOrNull()?.trim()
                        if (!words.isNullOrEmpty()) {
                            model.setDraft(words.take(MAX_PROMPT_LENGTH))
                        } else {
                            localNotice = "Распознаватель не вернул текст."
                        }
                    }
                }

                LaunchedEffect(state.notice) {
                    state.notice?.let {
                        notices.showSnackbar(it)
                        model.dismissNotice()
                    }
                }
                LaunchedEffect(localNotice) {
                    localNotice?.let {
                        notices.showSnackbar(it)
                        localNotice = null
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    AxisApp(
                        state = state,
                        notices = { SnackbarHost(notices) },
                        model = model,
                        dictate = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                            }
                            try {
                                recognizer.launch(intent)
                            } catch (_: ActivityNotFoundException) {
                                localNotice = "На устройстве нет приложения для распознавания речи."
                            } catch (_: SecurityException) {
                                localNotice = "Система не разрешила запустить распознавание речи."
                            }
                        },
                        speak = { text -> speak(text) { localNotice = it } },
                        stopVoice = ::stopSpeech,
                    )
                }
            }
        }
    }

    private fun speak(text: String, onError: (String) -> Unit) {
        if (!voiceReady) {
            pendingSpeech = text to onError
            if (voice == null) {
                voice = TextToSpeech(this) { status ->
                    mainHandler.post {
                        if (isDestroyed) return@post
                        val pending = pendingSpeech
                        pendingSpeech = null
                        if (status == TextToSpeech.SUCCESS) {
                            voiceReady = true
                            pending?.let { (message, report) -> speak(message, report) }
                        } else {
                            voice?.shutdown()
                            voice = null
                            pending?.second?.invoke("Синтезатор речи недоступен.")
                        }
                    }
                }
            }
            return
        }
        val engine = voice ?: return
        val language = engine.setLanguage(Locale.forLanguageTag("ru-RU"))
        if (language < TextToSpeech.LANG_AVAILABLE) {
            onError("Установите русский голос в настройках синтеза речи Android.")
            return
        }
        val chunkSize = TextToSpeech.getMaxSpeechInputLength() / 2
        engine.stop()
        for (part in text.chunked(chunkSize)) {
            if (engine.speak(part, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString()) == TextToSpeech.ERROR) {
                engine.stop()
                onError("Не удалось озвучить ответ.")
                return
            }
        }
    }

    private fun stopSpeech() {
        pendingSpeech = null
        voice?.stop()
    }

    override fun onStop() {
        stopSpeech()
        super.onStop()
    }

    override fun onDestroy() {
        stopSpeech()
        voice?.shutdown()
        voice = null
        super.onDestroy()
    }
}

@Composable
private fun AxisTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFFC6B7FF),
            onPrimary = Color(0xFF2B155D),
            primaryContainer = Color(0xFF423079),
            onPrimaryContainer = Color(0xFFF0EAFF),
            background = Color(0xFF171722),
            surface = Color(0xFF171722),
            surfaceVariant = Color(0xFF2A293A),
            onSurface = Color(0xFFF1EFF9),
            onSurfaceVariant = Color(0xFFC7C4D9),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF5639AC),
            primaryContainer = Color(0xFFE9DEFF),
            onPrimaryContainer = Color(0xFF230E58),
            background = Color(0xFFFAF8FF),
            surface = Color(0xFFFAF8FF),
            surfaceVariant = Color(0xFFECE7F7),
            onSurface = Color(0xFF211B2E),
            onSurfaceVariant = Color(0xFF4D445C),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
