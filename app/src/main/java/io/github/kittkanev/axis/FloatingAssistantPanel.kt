package io.github.kittkanev.axis

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun FloatingAssistantPanel(
    app: AxisApplication,
    actions: AssistantController,
    microphone: () -> Unit,
    editorFocus: (Boolean) -> Unit,
    request: Int,
    drag: (Offset) -> Unit,
    finishDrag: () -> Unit,
    settings: () -> Unit,
) {
    val prefs by app.preferences.state.collectAsState()
    val input by actions.state.collectAsState()
    val speaking by actions.voice.speaking.collectAsState()
    val focus = LocalFocusManager.current
    val onDrag by rememberUpdatedState(drag)
    val onFinish by rememberUpdatedState(finishDrag)
    val dragHandle = Modifier.pointerInput(Unit) {
        detectDragGestures(onDragEnd = { onFinish() }, onDragCancel = { onFinish() }) { change, amount ->
            change.consume()
            onDrag(amount)
        }
    }
    var expanded by remember(request) { mutableStateOf(request != 0) }
    var editing by remember { mutableStateOf(false) }
    var disclose by remember { mutableStateOf(false) }
    val recording = input.listening || input.finishingSpeech
    val show = expanded || recording || input.waiting || input.proposal != null || input.reply.isNotBlank()
    val panelHeight = (LocalConfiguration.current.screenHeightDp - 150).coerceIn(110, 310).dp
    val width = (LocalConfiguration.current.screenWidthDp - 32).coerceIn(200, 346).dp

    AxisTheme(prefs) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface.copy(alpha = prefs.overlayOpacity),
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                (if (show) Modifier.width(width) else Modifier).padding(horizontal = 5.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (show) {
                    Text("Потяните для перемещения", dragHandle.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelSmall)
                    Column(Modifier.fillMaxWidth().heightIn(max = panelHeight).verticalScroll(rememberScrollState()).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when {
                            input.listening -> Text("Слушаю", style = MaterialTheme.typography.titleMedium)
                            input.finishingSpeech -> Text(stringResource(R.string.recognizing_speech))
                            input.waiting -> TextButton(onClick = actions::startOrStop) { Text(stringResource(R.string.stop_request)) }
                        }
                        input.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (input.reply.isNotBlank()) {
                            Text(input.reply)
                            TextButton(onClick = actions::speakReply) { Text(if (speaking) "Остановить голос" else "Озвучить") }
                        }
                        val proposal = input.proposal
                        if (proposal != null) {
                            Text(proposal.screen.describe(proposal.call), style = MaterialTheme.typography.titleMedium)
                            Text(proposal.screen.packageName, style = MaterialTheme.typography.bodySmall)
                            Row {
                                Button(onClick = actions::executeTool) { Text(stringResource(R.string.run_action)) }
                                TextButton(onClick = actions::dismissTool) { Text(stringResource(R.string.cancel_action)) }
                            }
                        } else if (!recording && !input.waiting) {
                            if (editing || input.draft.isNotBlank() || input.screen != null) {
                                OutlinedTextField(
                                    input.draft, actions::edit, Modifier.fillMaxWidth(),
                                    label = { Text(if (input.screen == null) "Сообщение" else "Команда для экрана") },
                                    maxLines = 3,
                                )
                                Button(enabled = input.draft.isNotBlank(), onClick = {
                                    focus.clearFocus()
                                    editing = false
                                    editorFocus(false)
                                    actions.send()
                                }) { Text(stringResource(R.string.send_action)) }
                            }
                            if (disclose) {
                                Text(stringResource(R.string.send_label_context))
                                Row {
                                    TextButton(onClick = {
                                        disclose = false
                                        editing = actions.selectScreen()
                                        editorFocus(editing)
                                    }) { Text(stringResource(R.string.choose_screen)) }
                                    TextButton(onClick = { disclose = false }) { Text(stringResource(R.string.cancel_action)) }
                                }
                            } else if (input.screen == null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (!editing) TextButton(onClick = { editing = true; editorFocus(true) }) { Text(stringResource(R.string.text_label)) }
                                    TextButton(onClick = { disclose = true }) { Text(stringResource(R.string.screen_command)) }
                                    TextButton(onClick = actions::openApp) { Text("Axis") }
                                }
                            } else {
                                TextButton(onClick = actions::dismissTool) { Text(stringResource(R.string.remove_screen_selection)) }
                            }
                        } else if (input.draft.isNotBlank() && recording) {
                            Text(input.draft)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sphereSize = if (show) prefs.orbSize else prefs.compactSize
                    val label = if (show) "Свернуть панель" else "Развернуть панель"
                    IconButton(onClick = {
                        expanded = !show
                        if (expanded) actions.playOpeningSound()
                        if (!expanded) {
                            focus.clearFocus()
                            editing = false
                            disclose = false
                            actions.collapse()
                            editorFocus(false)
                        }
                    }, modifier = dragHandle.size((sphereSize + 4).dp).semantics { contentDescription = label }) {
                        AxisOrb(Modifier.size(sphereSize.dp), prefs.accent, prefs.motion,
                            recording || input.waiting || speaking, input.audioLevel, prefs.tint)
                    }
                    MicrophoneButton(recording) {
                        expanded = true
                        focus.clearFocus()
                        editing = false
                        editorFocus(false)
                        microphone()
                    }
                    IconButton(onClick = {
                        focus.clearFocus()
                        editing = false
                        editorFocus(false)
                        settings()
                    }) {
                        Image(painterResource(R.drawable.ic_axis_settings), "Настройки оверлея",
                            Modifier.size(23.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface))
                    }
                    PanelIcon("Закрыть плавающую панель", false, actions::close)
                }
            }
        }
    }
}
