package io.github.kittkanev.axis

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ColumnScope.SetupScreen(
    modelReady: Boolean,
    assistantReady: Boolean,
    microphoneReady: Boolean,
    overlayReady: Boolean,
    controlReady: Boolean,
    wakeActive: Boolean,
    openModels: () -> Unit,
    chooseAssistant: () -> Unit,
    microphone: () -> Unit,
    openOverlay: () -> Unit,
    openSettings: () -> Unit,
    tryCommand: () -> Unit,
) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Подключите модель") {
            Text(if (modelReady) "Профиль модели выбран." else "Выберите поставщика, вставьте API-ключ и выберите модель, которая поддерживает вызов инструментов.")
            OutlinedButton(onClick = openModels) { Text(stringResource(R.string.open_models)) }
        }
        Section("Разрешите голосовой вызов") {
            Text(if (assistantReady) "Axis выбран. На поддерживаемых устройствах откройте его жестом из нижнего угла." else
                "Выберите Axis цифровым ассистентом Android. На POCO можно также добавить плитку Axis в быстрые настройки.")
            if (!assistantReady) OutlinedButton(onClick = chooseAssistant) { Text(stringResource(R.string.set_axis_assistant)) }
            if (!microphoneReady) OutlinedButton(onClick = microphone) { Text(stringResource(R.string.allow_microphone)) }
        }
        Section("Закрепите панель поверх приложений") {
            Text(if (overlayReady) "Плавающая панель запущена." else "Разрешите показ поверх приложений и запустите панель.")
            Text(stringResource(R.string.drag_panel))
            OutlinedButton(onClick = openOverlay) { Text(stringResource(R.string.open_overlay_settings)) }
        }
        Section("Включите управление и голосовую активацию") {
            Text(if (controlReady) "Автовыполнение подключено." else
                "Включите службу Axis в специальных возможностях Android и автовыполнение в настройках Axis.")
            Text(if (wakeActive) "Прослушивание ключевой фразы запущено." else
                "Для вызова фразой включите «Слушать “Аксис” или “Axis”» в настройках Axis.")
            OutlinedButton(onClick = openSettings) { Text(stringResource(R.string.open_axis_settings)) }
        }
        Section("Попробуйте команду") {
            Text(stringResource(R.string.about_phone_example))
            OutlinedButton(onClick = tryCommand) { Text(stringResource(R.string.open_about_phone)) }
        }
    }
}
