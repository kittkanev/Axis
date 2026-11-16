package io.github.kittkanev.axis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun ColumnScope.SettingsScreen(
    prefs: AxisPreferences,
    update: (AxisPreferences) -> Unit,
    assistantActive: Boolean,
    microphoneAllowed: Boolean,
    chooseAssistant: () -> Unit,
    addTile: () -> Unit,
    microphone: () -> Unit,
    preview: () -> Unit,
    accessibilityConnected: Boolean,
    accessibilitySettings: () -> Unit,
    wakeStatus: WakeStatus,
    wakeModelInstalled: Boolean,
    wakeDownloading: Boolean,
    wakeWord: (Boolean) -> Unit,
    installWakeLanguage: () -> Unit,
) {
    var consent by remember { mutableStateOf(false) }
    var controlConsent by remember { mutableStateOf(false) }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Системный ассистент") {
            Text(if (assistantActive) "Axis выбран в Android" else "Axis пока не выбран в Android")
            Button(onClick = chooseAssistant) {
                Text(if (assistantActive) "Настройки ассистента Android" else "Сделать Axis ассистентом")
            }
            Text(stringResource(R.string.assistant_gesture_help))
            OutlinedButton(onClick = addTile) { Text(stringResource(R.string.add_qs_tile)) }
            OutlinedButton(onClick = preview) { Text(stringResource(R.string.open_overlay_action)) }
        }
        Section("Внешний вид") {
            AxisPicker("Тема", prefs.theme.label, ThemeMode.entries.map { it to it.label }) {
                update(prefs.copy(theme = it))
            }

        }
        Section("Управление телефоном") {
            AxisSwitch("Автовыполнение без подтверждений", prefs.autoControl) {
                if (it) controlConsent = true else update(prefs.copy(autoControl = false))
            }
            Text(stringResource(R.string.sequence_help))
            if (!accessibilityConnected) {
                OutlinedButton(onClick = accessibilitySettings) { Text(stringResource(R.string.connect_axis_service)) }
            }
        }
        Section("Активация по фразе") {
            AxisSwitch("Слушать «Аксис» или «Axis»", prefs.wakeWord, wakeWord)
            if (prefs.wakeWord) {
                AxisPicker("Язык фразы", if (prefs.wakeLanguage == "ru-RU") "Русский" else "English",
                    listOf("ru-RU" to "Русский", "en-US" to "English")) {
                    update(prefs.copy(wakeLanguage = it))
                }
                val status = if (wakeModelInstalled) wakeStatus else WakeStatus.NEEDS_LANGUAGE
                Text(stringResource(when (status) {
                    WakeStatus.LISTENING -> R.string.wake_listening
                    WakeStatus.PAUSED -> R.string.wake_paused
                    WakeStatus.NEEDS_LANGUAGE -> R.string.wake_requirements
                    WakeStatus.UNAVAILABLE -> R.string.wake_unavailable
                }))
                if (!wakeModelInstalled) {
                    OutlinedButton(onClick = installWakeLanguage, enabled = !wakeDownloading) {
                        Text(stringResource(if (wakeDownloading) R.string.wake_downloading else R.string.download_language))
                    }
                } else if (status != WakeStatus.LISTENING) {
                    OutlinedButton(onClick = { wakeWord(true) }) { Text(stringResource(R.string.start_listening)) }
                }
            }
            Text(stringResource(R.string.wake_help))
        }
        Section("Голосовой режим") {
            OutlinedButton(onClick = microphone) {
                Text(if (microphoneAllowed) "Настройки микрофона" else "Разрешить микрофон")
            }
            Text(stringResource(R.string.system_speech_help))
            AxisPicker("Язык распознавания", voiceLanguages.toMap().getValue(prefs.recognitionLanguage), voiceLanguages) {
                update(prefs.copy(recognitionLanguage = it))
            }
            AxisSwitch("Начинать запись при вызове", prefs.autoListen) {
                if (it) consent = true else update(prefs.copy(autoListen = false))
            }
            AxisSwitch("Отправлять после паузы", prefs.autoSend) { update(prefs.copy(autoSend = it)) }
            AxisSwitch("Озвучивать ответы в оверлее", prefs.autoSpeak) { update(prefs.copy(autoSpeak = it)) }
        }
    }
    if (controlConsent) AlertDialog(
        onDismissRequest = { controlConsent = false },
        title = { Text(stringResource(R.string.confirm_auto_control)) },
        text = { Text(stringResource(R.string.auto_control_help)) },
        confirmButton = { TextButton(onClick = { update(prefs.copy(autoControl = true)); controlConsent = false }) { Text(stringResource(R.string.enable_access)) } },
        dismissButton = { TextButton(onClick = { controlConsent = false }) { Text(stringResource(R.string.cancel_action)) } },
    )
    if (consent) AlertDialog(
        onDismissRequest = { consent = false },
        title = { Text(stringResource(R.string.auto_mic_question)) },
        text = { Text(stringResource(R.string.auto_mic_notice)) },
        confirmButton = { TextButton(onClick = { update(prefs.copy(autoListen = true)); consent = false }) { Text(stringResource(R.string.enable_access)) } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text(stringResource(R.string.cancel_action)) } },
    )
}

@Composable
internal fun ColumnScope.OverlaySettingsScreen(
    prefs: AxisPreferences,
    update: (AxisPreferences) -> Unit,
    allowed: Boolean,
    running: Boolean,
    accessibilityConnected: Boolean,
    start: () -> Unit,
    stop: () -> Unit,
    preview: () -> Unit,
    accessibilitySettings: () -> Unit,
    deviceSettings: () -> Unit,
) {
    var accessibilityConsent by remember { mutableStateOf(false) }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Плавающая панель") {
            if (running) {
                Text(stringResource(R.string.overlay_running))
                OutlinedButton(onClick = stop) { Text(stringResource(R.string.close_floating_panel)) }
            } else {
                Button(onClick = start) { Text(if (allowed) "Показать поверх приложений" else "Разрешить показ поверх приложений") }
            }
            OutlinedButton(onClick = preview) { Text(stringResource(R.string.open_voice_overlay)) }
            Text(stringResource(R.string.overlay_lock_notice))
            AxisSwitch("Звук при открытии", prefs.openingSound) { update(prefs.copy(openingSound = it)) }
        }
        Section("Работа в других приложениях") {
            AxisSwitch("Панель через специальные возможности", prefs.accessibilityOverlay) {
                update(prefs.copy(accessibilityOverlay = it))
            }
            if (prefs.accessibilityOverlay && !accessibilityConnected) {
                OutlinedButton(onClick = { accessibilityConsent = true }) { Text(stringResource(R.string.connect_axis_service)) }
            }
            if (isXiaomiDevice) {
                Text(stringResource(R.string.overlay_miui_hint))
                OutlinedButton(onClick = deviceSettings) { Text(stringResource(R.string.open_android_app_settings)) }
            }
        }
        Section("Внешний вид") {
            AxisPicker("Цвет сферы", if (prefs.customColor == null) prefs.accent.label else "Свой цвет",
                Accent.entries.map { it to it.label }) {
                update(prefs.copy(accent = it, customColor = null))
            }
            var custom by remember(prefs.accent) { mutableStateOf(prefs.customColor?.let { "%06X".format(it and 0xFFFFFFL) }.orEmpty()) }
            OutlinedTextField(custom, { custom = it.take(7) }, label = { Text(stringResource(R.string.custom_hex_color)) }, singleLine = true)
            val rgb = custom.trim().removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)
                ?.takeIf { it in 0..0xFFFFFF }
            TextButton(enabled = rgb != null, onClick = { update(prefs.copy(customColor = 0xFF000000L or requireNotNull(rgb))) }) {
                Text(stringResource(R.string.apply_color))
            }
            AxisOrb(Modifier.size(prefs.orbSize.dp).align(Alignment.CenterHorizontally), prefs.accent, prefs.motion, false, color = prefs.tint)
            Text("Размер раскрытой сферы · ${prefs.orbSize} dp")
            Slider(prefs.orbSize.toFloat(), { update(prefs.copy(orbSize = it.roundToInt())) }, valueRange = 64f..112f)
            Text("Размер свёрнутой сферы · ${prefs.compactSize} dp")
            Slider(prefs.compactSize.toFloat(), { update(prefs.copy(compactSize = it.roundToInt())) }, valueRange = 38f..64f)
            Text("Непрозрачность панели · ${(prefs.overlayOpacity * 100).roundToInt()}%")
            Slider(prefs.overlayOpacity, { update(prefs.copy(overlayOpacity = it)) }, valueRange = .35f..1f)
            Text("Затемнение голосового экрана · ${(prefs.dim * 100).roundToInt()}%")
            Slider(prefs.dim, { update(prefs.copy(dim = it)) }, valueRange = .2f.. .9f)
            AxisSwitch("Анимация", prefs.motion) { update(prefs.copy(motion = it)) }
        }
        Section("Расположение") {
            Text(stringResource(R.string.drag_panel_short))
            AxisPicker("Положение панели", if (prefs.overlayAnchor == null) prefs.overlayPosition.label else "Свободное",
                OverlayPosition.entries.map { it to it.label }) {
                update(prefs.copy(overlayPosition = it, overlayAnchor = null))
            }
            Text("Отступ снизу · ${prefs.overlayOffset} dp")
            Slider(prefs.overlayOffset.toFloat(), { update(prefs.copy(overlayOffset = it.roundToInt(), overlayAnchor = null)) }, valueRange = 0f..96f)
        }
    }
    if (accessibilityConsent) AlertDialog(
        onDismissRequest = { accessibilityConsent = false },
        title = { Text(stringResource(R.string.axis_service)) },
        text = { Text(stringResource(R.string.overlay_accessibility_use)) },
        confirmButton = { TextButton(onClick = {
            accessibilityConsent = false
            accessibilitySettings()
        }) { Text(stringResource(R.string.android_settings)) } },
        dismissButton = { TextButton(onClick = { accessibilityConsent = false }) { Text(stringResource(R.string.cancel_action)) } },
    )
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
internal fun AxisSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.toggleable(checked, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
internal fun <T> AxisPicker(label: String, selected: String, choices: List<Pair<T, String>>, pick: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }) { Text(selected, maxLines = 2) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; pick(value) })
            }
        }
    }
}

@Composable
internal fun VoiceEditor(
    profile: Profile,
    voices: List<InstalledVoice>,
    preview: (ModelVoice) -> Unit,
    close: () -> Unit,
    save: (ModelVoice) -> Unit,
) {
    var setting by remember(profile.id) { mutableStateOf(profile.voice) }
    val language = java.util.Locale.forLanguageTag(setting.language).language
    val available = voices.filter { java.util.Locale.forLanguageTag(it.language).language == language }
    fun label(voice: InstalledVoice) = if (voice.network) "${voice.name} (через интернет)" else voice.name
    val selected = available.firstOrNull { it.name == setting.name }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Голос · ${profile.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                val google = ProviderPreset.forUrl(profile.baseUrl) == ProviderPreset.GOOGLE
                val engines = listOf("Android" to "Android", "Piper" to "Piper") +
                    if (google) listOf("Gemini TTS" to "Gemini TTS") else emptyList()
                val chosen = if (setting.piper) "Piper" else if (setting.neuralVoice != null && google) "Gemini TTS" else "Android"
                AxisPicker(stringResource(R.string.voice_engine), chosen, engines) {
                    setting = setting.copy(piper = it == "Piper", neuralVoice = if (it == "Gemini TTS") "Aoede" else null)
                }
                if (setting.piper) Text(stringResource(R.string.cloud_piper_note))
                val neural = setting.neuralVoice
                if (neural != null && google && !setting.piper) {
                    AxisPicker("Голос Gemini", geminiVoices.getValue(neural), geminiVoices.toList()) {
                        setting = setting.copy(neuralVoice = it)
                    }
                    AxisPicker("Манера речи", setting.speechStyle.label, SpeechStyle.entries.map { it to it.label }) {
                        setting = setting.copy(speechStyle = it)
                    }
                    Text(stringResource(R.string.gemini_tts_privacy))
                }
                AxisPicker("Язык", voiceLanguages.toMap().getValue(setting.language), voiceLanguages) {
                    setting = setting.copy(language = it, name = null)
                }
                if (!setting.piper && (neural == null || !google)) {
                    val options = listOf(null to "По умолчанию") + available.map { it.name to label(it) }
                    AxisPicker("Голос Android", selected?.let(::label) ?: "По умолчанию", options) {
                        setting = setting.copy(name = it)
                    }
                    Text("Скорость · ${(setting.rate * 100).roundToInt()}%")
                    Slider(setting.rate, { setting = setting.copy(rate = it) }, valueRange = 0.6f..1.5f)
                    Text("Высота · ${(setting.pitch * 100).roundToInt()}%")
                    Slider(setting.pitch, { setting = setting.copy(pitch = it) }, valueRange = 0.7f..1.4f)
                }
                OutlinedButton(onClick = { preview(setting) }) { Text(stringResource(R.string.preview_action)) }
            }
        },
        confirmButton = { TextButton(onClick = { save(setting) }) { Text(stringResource(R.string.save_action)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel_action)) } },
    )
}
