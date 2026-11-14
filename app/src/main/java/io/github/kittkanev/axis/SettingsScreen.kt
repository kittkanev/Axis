package io.github.kittkanev.axis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
) {
    var consent by remember { mutableStateOf(false) }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Системный ассистент") {
            Text(if (assistantActive) "Axis выбран в Android" else "Axis пока не выбран в Android")
            Button(onClick = chooseAssistant) { Text("Выбрать ассистента") }
            Text("Долгое нажатие кнопки питания, кнопки «Домой» или жест из угла зависят от настроек телефона. Ctrl + Shift + пробел открывает оверлей внутри Axis.")
            OutlinedButton(onClick = addTile) { Text("Добавить плитку Axis") }
            OutlinedButton(onClick = preview) { Text("Открыть оверлей") }
        }
        Section("Внешний вид") {
            AxisPicker("Тема", prefs.theme.label, ThemeMode.entries.map { it to it.label }) {
                update(prefs.copy(theme = it))
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
    start: () -> Unit,
    stop: () -> Unit,
    preview: () -> Unit,
) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Плавающая панель") {
            Button(onClick = start) { Text(if (allowed) "Показать поверх приложений" else "Разрешить показ поверх приложений") }
            if (allowed) OutlinedButton(onClick = stop) { Text("Закрыть плавающую панель") }
            OutlinedButton(onClick = preview) { Text("Открыть голосовой оверлей") }
            Text("Android может скрывать панель на экране блокировки и защищённых экранах.")
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
            AxisPicker("Положение панели", prefs.overlayPosition.label, OverlayPosition.entries.map { it to it.label }) {
                update(prefs.copy(overlayPosition = it))
            }
            Text("Отступ снизу · ${prefs.overlayOffset} dp")
            Slider(prefs.overlayOffset.toFloat(), { update(prefs.copy(overlayOffset = it.roundToInt())) }, valueRange = 0f..96f)
        }
    }
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, change)
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
