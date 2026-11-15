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
    wakeAvailable: Boolean,
    wakeWord: (Boolean) -> Unit,
    installWakeLanguage: (() -> Unit)?,
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
            Text("При жестовой навигации проведите из нижнего угла. Если прошивка не передаёт этот жест, используйте назначенное сочетание Android или плитку Axis.")
            OutlinedButton(onClick = addTile) { Text("Добавить плитку Axis") }
            OutlinedButton(onClick = preview) { Text("Открыть оверлей") }
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
            Text("Axis выполняет шаги вашей команды по очереди. Инструменты настраиваются отдельно для каждой модели.")
            if (!accessibilityConnected) {
                OutlinedButton(onClick = accessibilitySettings) { Text("Подключить службу Axis") }
            }
        }
        Section("Активация по фразе") {
            if (wakeAvailable || prefs.wakeWord) {
                AxisSwitch("Слушать «Аксис» или «Axis»", prefs.wakeWord, wakeWord)
                if (prefs.wakeWord) {
                    AxisPicker("Язык фразы", if (prefs.wakeLanguage == "ru-RU") "Русский" else "English",
                        listOf("ru-RU" to "Русский", "en-US" to "English")) {
                        update(prefs.copy(wakeLanguage = it))
                    }
                    Text(when (wakeStatus) {
                        WakeStatus.LISTENING -> "Слушаю фразу. Скажите «Аксис» и команду."
                        WakeStatus.PAUSED -> "Ожидание. Откройте плавающую панель или запустите прослушивание."
                        WakeStatus.NEEDS_LANGUAGE -> "Загрузите язык для локального распознавания речи в Android."
                        WakeStatus.UNAVAILABLE -> "Локальное распознавание недоступно. Проверьте микрофон и настройки речи в Android."
                    })
                    if (wakeStatus != WakeStatus.LISTENING) {
                        OutlinedButton(onClick = { wakeWord(true) }) { Text("Запустить прослушивание") }
                    }
                    if (installWakeLanguage != null && wakeStatus != WakeStatus.LISTENING) {
                        OutlinedButton(onClick = installWakeLanguage) { Text("Загрузить язык") }
                    }
                }
                Text("Панель слушает ключевую фразу локально на разблокированном экране. Закройте панель, чтобы остановить прослушивание.")
            } else {
                Text("Требуется Android 12 или новее и поддержка локального распознавания речи на устройстве.")
            }
        }
        Section("Голосовой режим") {
            OutlinedButton(onClick = microphone) {
                Text(if (microphoneAllowed) "Настройки микрофона" else "Разрешить микрофон")
            }
            Text("Служба Android распознаёт речь и может использовать сеть. Голоса для ответа выбираются в разделе «Модели».")
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
        title = { Text("Выполнять команды без подтверждений?") },
        text = { Text("При команде управления Axis отправляет выбранной модели доступные названия элементов экрана. Модель может нажимать, вводить текст и прокручивать экран по вашей команде. В процессе доступна кнопка «Остановить запрос».") },
        confirmButton = { TextButton(onClick = { update(prefs.copy(autoControl = true)); controlConsent = false }) { Text("Включить") } },
        dismissButton = { TextButton(onClick = { controlConsent = false }) { Text("Отмена") } },
    )
    if (consent) AlertDialog(
        onDismissRequest = { consent = false },
        title = { Text("Начинать запись автоматически?") },
        text = { Text("Axis включит микрофон после вызова голосового оверлея на разблокированном экране. Если включена отправка после паузы, распознанная фраза отправится автоматически.") },
        confirmButton = { TextButton(onClick = { update(prefs.copy(autoListen = true)); consent = false }) { Text("Включить") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Отмена") } },
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
                Text("Панель работает поверх приложений")
                OutlinedButton(onClick = stop) { Text("Закрыть плавающую панель") }
            } else {
                Button(onClick = start) { Text(if (allowed) "Показать поверх приложений" else "Разрешить показ поверх приложений") }
            }
            OutlinedButton(onClick = preview) { Text("Открыть голосовой оверлей") }
            Text("На экране блокировки панель скрыта.")
            AxisSwitch("Звук при открытии", prefs.openingSound) { update(prefs.copy(openingSound = it)) }
        }
        Section("Работа в других приложениях") {
            AxisSwitch("Панель через специальные возможности", prefs.accessibilityOverlay) {
                update(prefs.copy(accessibilityOverlay = it))
            }
            if (prefs.accessibilityOverlay && !accessibilityConnected) {
                OutlinedButton(onClick = { accessibilityConsent = true }) { Text("Подключить службу Axis") }
            }
            if (isXiaomiDevice) {
                Text("На POCO, Xiaomi и Redmi разрешите всплывающие окна в фоне. Если панель всё ещё исчезает, выберите для Axis «Нет ограничений» в настройках батареи.")
                OutlinedButton(onClick = deviceSettings) { Text("Открыть настройки Axis в Android") }
            }
        }
        Section("Внешний вид") {
            AxisPicker("Цвет сферы", if (prefs.customColor == null) prefs.accent.label else "Свой цвет",
                Accent.entries.map { it to it.label }) {
                update(prefs.copy(accent = it, customColor = null))
            }
            var custom by remember(prefs.accent) { mutableStateOf(prefs.customColor?.let { "%06X".format(it and 0xFFFFFFL) }.orEmpty()) }
            OutlinedTextField(custom, { custom = it.take(7) }, label = { Text("Свой цвет HEX") }, singleLine = true)
            val rgb = custom.trim().removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)
                ?.takeIf { it in 0..0xFFFFFF }
            TextButton(enabled = rgb != null, onClick = { update(prefs.copy(customColor = 0xFF000000L or requireNotNull(rgb))) }) {
                Text("Применить цвет")
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
            Text("Перетащите сферу или потяните за верх раскрытой панели.")
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
        title = { Text("Служба Axis") },
        text = { Text("Служба показывает панель поверх приложений и может читать доступные элементы экрана для ваших команд управления. Данные экрана передаются модели при выборе команды экрану или в режиме автовыполнения.") },
        confirmButton = { TextButton(onClick = {
            accessibilityConsent = false
            accessibilitySettings()
        }) { Text("Настройки Android") } },
        dismissButton = { TextButton(onClick = { accessibilityConsent = false }) { Text("Отмена") } },
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
                if (ProviderPreset.forUrl(profile.baseUrl) == ProviderPreset.GOOGLE) {
                    val engines = listOf(false to "Android", true to "Gemini TTS")
                    AxisPicker("Синтез речи", if (setting.neuralVoice == null) "Android" else "Gemini TTS", engines) {
                        setting = setting.copy(neuralVoice = if (it) "Aoede" else null)
                    }
                }
                val neural = setting.neuralVoice
                if (neural != null && ProviderPreset.forUrl(profile.baseUrl) == ProviderPreset.GOOGLE) {
                    AxisPicker("Голос Gemini", geminiVoices.getValue(neural), geminiVoices.toList()) {
                        setting = setting.copy(neuralVoice = it)
                    }
                    AxisPicker("Манера речи", setting.speechStyle.label, SpeechStyle.entries.map { it to it.label }) {
                        setting = setting.copy(speechStyle = it)
                    }
                    Text("Gemini получает текст для озвучивания отдельным запросом.")
                }
                AxisPicker("Язык", voiceLanguages.toMap().getValue(setting.language), voiceLanguages) {
                    setting = setting.copy(language = it, name = null)
                }
                if (neural == null || ProviderPreset.forUrl(profile.baseUrl) != ProviderPreset.GOOGLE) {
                    val options = listOf(null to "По умолчанию") + available.map { it.name to label(it) }
                    AxisPicker("Голос Android", selected?.let(::label) ?: "По умолчанию", options) {
                        setting = setting.copy(name = it)
                    }
                    Text("Скорость · ${(setting.rate * 100).roundToInt()}%")
                    Slider(setting.rate, { setting = setting.copy(rate = it) }, valueRange = 0.6f..1.5f)
                    Text("Высота · ${(setting.pitch * 100).roundToInt()}%")
                    Slider(setting.pitch, { setting = setting.copy(pitch = it) }, valueRange = 0.7f..1.4f)
                }
                OutlinedButton(onClick = { preview(setting) }) { Text("Прослушать") }
            }
        },
        confirmButton = { TextButton(onClick = { save(setting) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = close) { Text("Отмена") } },
    )
}
