package io.github.kittkanev.axis

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as AxisApplication
    private val output by lazy { VoiceOutput(this) }
    private var microphoneRequest by mutableStateOf(false)
    private var microphoneAllowed by mutableStateOf(false)
    private var overlayAllowed by mutableStateOf(false)
    private var startOverlayAfterPermission = false
    private var wakeAfterPermission = false
    private var openOverlaySettings by mutableStateOf(false)
    private var assistantActive by mutableStateOf(false)
    private var wakeDownloading by mutableStateOf(false)
    private var wakeModelRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        receiveMicrophoneRequest()
        receiveOverlayRequest()
        startOverlayAfterPermission = savedInstanceState?.getBoolean("startOverlay") == true
        wakeAfterPermission = savedInstanceState?.getBoolean("wakeAfterPermission") == true
        val model = app.workspace
        setContent {
            val prefs by app.preferences.state.collectAsStateWithLifecycle()
            val wakeModelInstalled = remember(prefs.wakeLanguage, wakeModelRevision) {
                WakeModels.installed(this, prefs.wakeLanguage) != null
            }
            val dark = isDark(prefs)
            SideEffect {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            AxisTheme(prefs) {
                val state by model.state.collectAsStateWithLifecycle()
                val voices by output.voices.collectAsStateWithLifecycle()
                val overlayRunning by FloatingAssistantService.isRunning.collectAsStateWithLifecycle()
                val wakeStatus by FloatingAssistantService.wakeStatus.collectAsStateWithLifecycle()
                val accessibilityConnected by DeviceControlService.isConnected.collectAsStateWithLifecycle()
                val notices = remember { SnackbarHostState() }
                var localNotice by remember { mutableStateOf<String?>(null) }
                val recognizer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    FloatingAssistantService.setExternalDictation(false)
                    if (result.resultCode == Activity.RESULT_OK) {
                        val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
                        if (words.isNullOrEmpty()) localNotice = "Распознаватель не вернул текст."
                        else model.setDraft(words.take(MAX_PROMPT_LENGTH))
                    }
                }
                val chooseAssistant: () -> Unit = {
                    // Android exposes the assistant role through Settings, where the user selects Axis.
                    if (!openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS) &&
                        !openSettings(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    ) localNotice = "Откройте Android → Приложения по умолчанию → Цифровой ассистент."
                }
                val permit = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    refreshStatus()
                    if (wakeAfterPermission) {
                        wakeAfterPermission = false
                        if (granted) armWakeWord()
                    }
                    localNotice = if (granted) "Микрофон доступен в голосовом оверлее." else "Доступ к микрофону не получен."
                }
                LaunchedEffect(microphoneRequest) {
                    if (microphoneRequest) {
                        microphoneRequest = false
                        getPreferences(MODE_PRIVATE).edit { putBoolean("askedMic", true) }
                        permit.launch(Manifest.permission.RECORD_AUDIO)
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
                val requestMic: () -> Unit = {
                    if (microphoneAllowed || !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) &&
                        getPreferences(MODE_PRIVATE).getBoolean("askedMic", false)
                    ) {
                        if (!openAppDetails()) localNotice = "Откройте настройки приложения Axis вручную."
                    } else {
                        getPreferences(MODE_PRIVATE).edit { putBoolean("askedMic", true) }
                        permit.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                val openAccessibility: () -> Unit = {
                    if (!openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)) {
                        localNotice = "Откройте специальные возможности Android и включите службу Axis."
                    }
                }
                AxisApp(
                    state = state, model = model, notices = { SnackbarHost(notices) },
                    dictate = {
                        try {
                            FloatingAssistantService.setExternalDictation(true)
                            recognizer.launch(dictationIntent(prefs.recognitionLanguage))
                        } catch (_: ActivityNotFoundException) {
                            FloatingAssistantService.setExternalDictation(false)
                            localNotice = "На устройстве нет приложения для распознавания речи. Попробуйте голосовой оверлей."
                        } catch (_: SecurityException) {
                            FloatingAssistantService.setExternalDictation(false)
                            localNotice = "Система не разрешила запустить распознавание речи."
                        }
                    },
                    speak = { text ->
                        val profile = state.workspace.active
                        model.speak(output, profile, text, profile?.voice ?: ModelVoice()) { localNotice = it }
                    },
                    stopVoice = output::stop,
                    prefs = prefs, voices = voices, prepareVoices = output::prepare,
                    previewVoice = { profile, voice ->
                        val sample = mapOf(
                            "ru-RU" to "Привет. Это голос Axis.", "en-US" to "Hello. This is the Axis voice.",
                            "de-DE" to "Hallo. Das ist die Stimme von Axis.", "es-ES" to "Hola. Esta es la voz de Axis.",
                            "fr-FR" to "Bonjour. Voici la voix d’Axis.",
                        ).getValue(voice.language)
                        model.speak(output, profile, sample, voice) { localNotice = it }
                    },
                    openPanel = ::openPanel,
                    assistantSetup = chooseAssistant.takeUnless { assistantActive },
                    overlaySettingsRequested = openOverlaySettings,
                    consumeOverlaySettings = { openOverlaySettings = false },
                    tutorial = { models, overlay, settingsPage ->
                        SetupScreen(
                            state.workspace.active != null, assistantActive, microphoneAllowed, overlayRunning,
                            prefs.autoControl && accessibilityConnected, wakeStatus == WakeStatus.LISTENING,
                            models, chooseAssistant, requestMic, overlay, settingsPage,
                            tryCommand = {
                                if (!openAboutPhone(this@MainActivity)) localNotice = "Android не открыл раздел «О телефоне»."
                            },
                        )
                    },
                    overlaySettings = {
                        val canStart = overlayAllowed || (prefs.accessibilityOverlay && accessibilityConnected)
                        OverlaySettingsScreen(prefs, app.preferences::update, canStart, overlayRunning,
                            accessibilityConnected, start = {
                            if (canStart) {
                                if (!startFloatingPanel()) {
                                    localNotice = "Android не разрешил запустить плавающую панель."
                                }
                            } else {
                                startOverlayAfterPermission = openOverlayPermission()
                                if (!startOverlayAfterPermission) localNotice = "Откройте разрешение «Поверх других приложений» для Axis."
                            }
                        }, stop = {
                            stopService(Intent(this@MainActivity, FloatingAssistantService::class.java))
                        }, preview = ::openPanel, accessibilitySettings = openAccessibility, deviceSettings = {
                            if (!openAppDetails()) localNotice = "Откройте настройки приложения Axis вручную."
                        })
                    },
                    settings = {
                        SettingsScreen(
                            prefs, app.preferences::update, assistantActive, microphoneAllowed,
                            chooseAssistant = chooseAssistant,
                            addTile = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    getSystemService(StatusBarManager::class.java).requestAddTileService(
                                        ComponentName(this@MainActivity, AxisTileService::class.java), "Axis",
                                        Icon.createWithResource(this@MainActivity, R.drawable.ic_axis_tile), mainExecutor,
                                    ) { code ->
                                        if (code != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED &&
                                            code != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                                        ) localNotice = "Добавьте Axis через редактор плиток быстрых настроек."
                                    }
                                } else localNotice = "Откройте редактор плиток быстрых настроек и перетащите Axis."
                            },
                            microphone = requestMic,
                            accessibilityConnected = accessibilityConnected,
                            accessibilitySettings = openAccessibility,
                            wakeStatus = wakeStatus,
                            wakeModelInstalled = wakeModelInstalled,
                            wakeDownloading = wakeDownloading,
                            wakeWord = { enable ->
                                when {
                                    !enable -> app.preferences.update(app.preferences.state.value.copy(wakeWord = false))
                                    microphoneAllowed -> armWakeWord()
                                    else -> {
                                        wakeAfterPermission = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ||
                                            !getPreferences(MODE_PRIVATE).getBoolean("askedMic", false)
                                        requestMic()
                                    }
                                }
                            },
                            installWakeLanguage = { downloadWakeLanguage(prefs.wakeLanguage) },
                            preview = ::openPanel,
                        )
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveMicrophoneRequest()
        receiveOverlayRequest()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("startOverlay", startOverlayAfterPermission)
        outState.putBoolean("wakeAfterPermission", wakeAfterPermission)
        super.onSaveInstanceState(outState)
    }

    private fun receiveOverlayRequest() {
        if (intent.getBooleanExtra(EXTRA_OPEN_OVERLAY, false)) {
            intent.removeExtra(EXTRA_OPEN_OVERLAY)
            openOverlaySettings = true
        }
    }

    private fun openOverlayPermission() = openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())

    private fun openAppDetails() = openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    private fun packageUri() = android.net.Uri.fromParts("package", packageName, null)

    private fun receiveMicrophoneRequest() {
        microphoneRequest = app.requestMicrophone
        app.requestMicrophone = false
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (startOverlayAfterPermission) {
            startOverlayAfterPermission = false
            if (overlayAllowed && !startFloatingPanel()) {
                app.workspace.report("Android не разрешил запустить плавающую панель.")
            }
        }
    }

    private fun refreshStatus() {
        microphoneAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        overlayAllowed = Settings.canDrawOverlays(this)
        assistantActive = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    private fun startFloatingPanel(): Boolean {
        val prefs = app.preferences.state.value
        val armWake = prefs.wakeWord && microphoneAllowed && WakeModels.installed(this, prefs.wakeLanguage) != null
        return FloatingAssistantService.start(this, armWake = armWake)
    }

    private fun armWakeWord() {
        val prefs = app.preferences.state.value
        app.preferences.update(prefs.copy(wakeWord = true))
        if (WakeModels.installed(this, prefs.wakeLanguage) == null) return
        if (!FloatingAssistantService.canStart(this)) {
            startOverlayAfterPermission = openOverlayPermission()
            if (!startOverlayAfterPermission) app.workspace.report("Разрешите показ поверх приложений для Axis.")
        } else if (!startFloatingPanel()) {
            app.workspace.report("Android не разрешил запустить прослушивание.")
        }
    }

    private fun downloadWakeLanguage(language: String) {
        if (wakeDownloading) return
        wakeDownloading = true
        lifecycleScope.launch {
            try {
                WakeModels.install(this@MainActivity, language)
                wakeModelRevision++
                val prefs = app.preferences.state.value
                if (prefs.wakeWord && prefs.wakeLanguage == language && microphoneAllowed) armWakeWord()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                app.workspace.report(getString(R.string.wake_download_failed))
            } finally {
                wakeDownloading = false
            }
        }
    }

    private fun openSettings(action: String, uri: android.net.Uri? = null): Boolean = try {
        startActivity(Intent(action, uri))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun openPanel() {
        if (!FloatingAssistantService.openRunning()) startActivity(Intent(this, AssistantPanelActivity::class.java))
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed && event.isShiftPressed) {
            if (event.repeatCount == 0) openPanel()
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    override fun onStop() {
        output.stop()
        super.onStop()
    }

    override fun onDestroy() {
        output.close()
        super.onDestroy()
    }
}

@Composable
private fun isDark(prefs: AxisPreferences): Boolean = when (prefs.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

@Composable
internal fun AxisTheme(prefs: AxisPreferences, darkOverlay: Boolean = false, content: @Composable () -> Unit) {
    val tint = Color(prefs.tint)
    val ink = Color(prefs.accent.darkInk)
    val colors = if (darkOverlay || isDark(prefs)) {
        darkColorScheme(
            primary = tint,
            onPrimary = Color(0xFF261922),
            primaryContainer = ink,
            onPrimaryContainer = Color.White,
            background = Color(0xFF171722),
            surface = Color(0xFF171722),
            surfaceVariant = Color(0xFF2A293A),
            onSurface = Color(0xFFF1EFF9),
            onSurfaceVariant = Color(0xFFC7C4D9),
        )
    } else {
        lightColorScheme(
            primary = ink,
            primaryContainer = tint.copy(alpha = .34f),
            onPrimaryContainer = Color(0xFF211B2E),
            background = Color(0xFFFAF8FF),
            surface = Color(0xFFFAF8FF),
            surfaceVariant = Color(0xFFECE7F7),
            onSurface = Color(0xFF211B2E),
            onSurfaceVariant = Color(0xFF4D445C),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
