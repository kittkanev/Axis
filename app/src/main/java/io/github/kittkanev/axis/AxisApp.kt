package io.github.kittkanev.axis

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource

private enum class Screen(val label: Int) {
    CHAT(R.string.nav_chat), SETUP(R.string.nav_setup), MODELS(R.string.nav_models), DEVICE(R.string.nav_device),
    DOCUMENTS(R.string.nav_files), OVERLAY(R.string.nav_overlay), SETTINGS(R.string.nav_settings), CLOUD(R.string.nav_cloud)
}

@Composable
internal fun AxisApp(
    state: AxisState,
    model: AxisViewModel,
    notices: @Composable () -> Unit,
    dictate: () -> Unit,
    speak: (String) -> Unit,
    stopVoice: () -> Unit,
    prefs: AxisPreferences,
    voices: List<InstalledVoice>,
    prepareVoices: () -> Unit,
    previewVoice: (Profile, ModelVoice) -> Unit,
    openPanel: () -> Unit,
    assistantSetup: (() -> Unit)?,
    settings: @Composable ColumnScope.() -> Unit,
    tutorial: @Composable ColumnScope.(openModels: () -> Unit, openOverlay: () -> Unit, openSettings: () -> Unit) -> Unit,
    overlaySettings: @Composable ColumnScope.() -> Unit,
    overlaySettingsRequested: Boolean,
    consumeOverlaySettings: () -> Unit,
) {
    if (state.operation == Operation.LOADING) {
        AxisLoading(prefs)
        return
    }
    var page by rememberSaveable { mutableStateOf(Screen.CHAT) }
    LaunchedEffect(overlaySettingsRequested) {
        if (overlaySettingsRequested) {
            page = Screen.OVERLAY
            consumeOverlaySettings()
        }
    }
    val draft by model.draft.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fileBusy by model.documentBusy.collectAsStateWithLifecycle()
    val active = state.workspace.active
    val waiting = state.operation == Operation.SENDING
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = notices,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AxisHeader(page, waiting || fileBusy, openPanel) { screen ->
                stopVoice()
                page = screen
            }
            when {
                state.loadFailed -> InfoCard("Данные недоступны", "Повторите загрузку локального профиля и истории.") {
                    Button(onClick = model::load) { Text(stringResource(R.string.retry_action)) }
                }
                page == Screen.CHAT -> Chat(
                    state = state,
                    model = model,
                    active = active,
                    assistantSetup = assistantSetup,
                    draft = draft,
                    setDraft = model::setDraft,
                    openModels = { page = Screen.MODELS },
                    send = {
                        val aboutPhone = isAboutPhoneCommand(draft)
                        val requestedApp = if (aboutPhone) null else directAppQuery(draft)
                        if (aboutPhone) {
                            if (openAboutPhone(context)) {
                                model.setDraft("")
                                return@Chat
                            }
                            if (active == null) {
                                model.report("Android не открыл раздел «О телефоне».")
                                return@Chat
                            }
                        }
                        if (requestedApp != null) {
                            try {
                                launchRequestedApp(context, requestedApp)
                                model.setDraft("")
                            } catch (e: AxisException) {
                                model.report(e.message)
                            }
                        } else {
                            model.send(draft, automate = prefs.autoControl && isDeviceCommand(draft)) {
                                model.setDraft("")
                            }
                        }
                    },
                    cancel = model::cancelRequest,
                    clear = model::clearChat,
                    dictate = dictate,
                    speak = speak,
                    stopVoice = stopVoice,
                )
                page == Screen.SETUP -> tutorial({ page = Screen.MODELS }, { page = Screen.OVERLAY }, { page = Screen.SETTINGS })
                page == Screen.MODELS -> Models(state, model, voices, prepareVoices, previewVoice, stopVoice)
                page == Screen.SETTINGS -> settings()
                page == Screen.OVERLAY -> overlaySettings()
                page == Screen.DEVICE -> DeviceScreen(model, state)
                page == Screen.CLOUD -> CloudScreen(model)
                else -> DocumentScreen(model)
            }
        }
    }
}

@Composable
private fun AxisHeader(page: Screen, waiting: Boolean, openPanel: () -> Unit, navigate: (Screen) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Axis", Modifier.weight(1f),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = openPanel, enabled = !waiting) { Text(stringResource(R.string.voice_action)) }
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(stringResource(page.label)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Screen.entries.forEach { screen ->
                    DropdownMenuItem(text = { Text(stringResource(screen.label)) }, onClick = {
                        expanded = false
                        navigate(screen)
                    })
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Chat(
    state: AxisState,
    model: AxisViewModel,
    active: Profile?,
    assistantSetup: (() -> Unit)?,
    draft: String,
    setDraft: (String) -> Unit,
    openModels: () -> Unit,
    send: () -> Unit,
    cancel: () -> Unit,
    clear: () -> Unit,
    dictate: () -> Unit,
    speak: (String) -> Unit,
    stopVoice: () -> Unit,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmVoice by rememberSaveable { mutableStateOf(false) }
    var chooseChat by rememberSaveable { mutableStateOf(false) }
    val ready = state.operation == null
    val messages = state.workspace.messages
    val visibleMessages = messages + listOfNotNull(state.pendingMessage, state.partialReply.takeIf(String::isNotEmpty)?.let { Message(Role.ASSISTANT, it) })
    val focus = LocalFocusManager.current

    if (assistantSetup != null && messages.isEmpty() && ready) {
        TextButton(onClick = assistantSetup) { Text(stringResource(R.string.set_axis_assistant)) }
    }
    if (active == null) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard("Подключите модель", "Добавьте базовый адрес API, ID модели и свой ключ.") {
                Button(onClick = openModels) { Text(stringResource(R.string.open_models)) }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.workspace.conversation?.title?.takeIf(String::isNotBlank) ?: active.name,
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1)
            TextButton(onClick = { chooseChat = true }, enabled = ready) { Text(stringResource(R.string.conversations)) }
        }
        if (chooseChat) ConversationChooser(state.workspace, model) { chooseChat = false }
        if (visibleMessages.isEmpty()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                InfoCard("Новый разговор", "При отправке Axis передаёт запрос и недавние сообщения выбранному провайдеру.") {}
            }
        } else {
            key(active.id, state.workspace.conversation?.id) {
                val list = rememberLazyListState(initialFirstVisibleItemIndex = visibleMessages.lastIndex)
                LaunchedEffect(visibleMessages.lastIndex) {
                    list.scrollToItem(visibleMessages.lastIndex)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(visibleMessages) { index, message ->
                        val title = when {
                            message.role == Role.ASSISTANT -> active.name
                            index == messages.size -> "Вы · запрос"
                            else -> "Вы"
                        }
                        MessageCard(title, message, speak, stopVoice, index >= messages.size)
                    }
                }
            }
        }
    }
    if (state.operation == Operation.SENDING) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    HorizontalDivider()
    OutlinedTextField(
        value = draft,
        onValueChange = { if (it.length <= MAX_PROMPT_LENGTH) setDraft(it) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
        label = { Text(if (active == null) "Команда, например: Открой Настройки" else "Сообщение") },
        maxLines = 4,
        enabled = ready,
    )
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.operation == Operation.SENDING) {
            OutlinedButton(onClick = cancel) { Text(stringResource(R.string.stop_request)) }
        } else {
            OutlinedButton(onClick = { confirmVoice = true }, enabled = ready) { Text(stringResource(R.string.microphone_action)) }
            Button(
                onClick = { focus.clearFocus(); send() },
                enabled = ready && draft.isNotBlank() && (active != null || directAppQuery(draft) != null || isAboutPhoneCommand(draft)),
            ) { Text(stringResource(R.string.send_action)) }
        }
    }
    if (messages.isNotEmpty() && ready) {
        TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.clear_chat)) }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.confirm_clear_chat)) },
            text = { Text(stringResource(R.string.clear_history_notice)) },
            confirmButton = {
                TextButton(onClick = { stopVoice(); clear(); confirmClear = false }) { Text(stringResource(R.string.clear_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel_action)) } },
        )
    }
    if (confirmVoice) {
        AlertDialog(
            onDismissRequest = { confirmVoice = false },
            title = { Text(stringResource(R.string.speech_recognition)) },
            text = { Text(stringResource(R.string.speech_recognition_help)) },
            confirmButton = {
                TextButton(onClick = { confirmVoice = false; stopVoice(); dictate() }) { Text(stringResource(R.string.speak_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmVoice = false }) { Text(stringResource(R.string.cancel_action)) } },
        )
    }
}

@Composable
private fun MessageCard(title: String, message: Message, speak: (String) -> Unit, stop: () -> Unit, pending: Boolean) {
    val isReply = message.role == Role.ASSISTANT
    val color = if (isReply) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
    val onColor = if (isReply) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(shape = MaterialTheme.shapes.large, color = color, contentColor = onColor) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            if (isReply) MarkdownMessage(message.text)
            else SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
            if (isReply && !pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CopyReply(message.text)
                    TextButton(onClick = { speak(message.text) }) { Text(stringResource(R.string.read_aloud)) }
                    TextButton(onClick = stop) { Text(stringResource(R.string.stop_action)) }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Models(
    state: AxisState, model: AxisViewModel, voices: List<InstalledVoice>,
    prepareVoices: () -> Unit, previewVoice: (Profile, ModelVoice) -> Unit, stopVoice: () -> Unit,
) {
    var voiceProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorId by remember { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    val current = state.workspace.profiles
    val ready = state.operation == null
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Модели", style = MaterialTheme.typography.headlineSmall)
        Button(enabled = ready && current.size < MAX_PROFILES, onClick = { editorId = "" }) {
            Text(stringResource(R.string.add_profile))
        }
        if (current.isEmpty()) {
            Text(stringResource(R.string.provider_https_hint))
        }
        if (state.testing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = model::cancelTest) { Text(stringResource(R.string.stop_test)) }
        }
        current.forEach { profile ->
            val active = profile.id == state.workspace.activeId
            InfoCard(profile.name, "${profile.model}\n${profile.baseUrl}") {
                if (active) Text("Активная модель", style = MaterialTheme.typography.labelLarge)
                else TextButton(onClick = { model.selectProfile(profile.id) }, enabled = ready) { Text(stringResource(R.string.select_action)) }
                TextButton(onClick = { model.testProfile(profile) }, enabled = ready && !state.testing) { Text(stringResource(R.string.test_connection)) }
                TextButton(onClick = { prepareVoices(); voiceProfileId = profile.id }, enabled = ready) { Text(stringResource(R.string.model_voice)) }
                if (!profile.managed) TextButton(onClick = { editorId = profile.id }, enabled = ready) { Text(stringResource(R.string.edit_action)) }
                TextButton(onClick = { removeId = profile.id }, enabled = ready) { Text(stringResource(R.string.delete_action)) }
            }
        }
    }

    current.firstOrNull { it.id == voiceProfileId }?.let { profile ->
        VoiceEditor(profile, voices, { previewVoice(profile, it) }, close = { stopVoice(); voiceProfileId = null }) {
            model.saveVoice(profile.id, it)
            stopVoice()
            voiceProfileId = null
        }
    }
    editorId?.let { id ->
        val profile = current.firstOrNull { it.id == id }
        ProfileEditor(profile, ready, state.notice, model::availableModels, close = { editorId = null }) { input ->
            model.saveProfile(profile?.id, input) { editorId = null }
        }
    }
    val deleting = current.firstOrNull { it.id == removeId }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { removeId = null },
            title = { Text(stringResource(R.string.confirm_delete_profile)) },
            text = { Text("Axis удалит профиль «${deleting.name}», его ключ и историю чата.") },
            confirmButton = {
                TextButton(onClick = { model.removeProfile(deleting.id); removeId = null }, enabled = ready) {
                    Text(stringResource(R.string.delete_action))
                }
            },
            dismissButton = { TextButton(onClick = { removeId = null }) { Text(stringResource(R.string.cancel_action)) } },
        )
    }
}

@Composable
private fun ProfileEditor(
    profile: Profile?,
    enabled: Boolean,
    notice: String?,
    loadModels: suspend (String, String, Profile?) -> List<String>,
    close: () -> Unit,
    save: (ProfileInput) -> Unit,
) {
    var preset by remember(profile?.id) {
        mutableStateOf(profile?.let { ProviderPreset.forUrl(it.baseUrl) } ?: ProviderPreset.GOOGLE)
    }
    var input by remember(profile?.id) {
        mutableStateOf(
            if (profile == null) preset.profileInput()
            else ProfileInput(profile.name, profile.baseUrl, profile.model,
                systemPrompt = profile.systemPrompt, concise = profile.concise, tools = profile.tools),
        )
    }
    val canList = input.key.isNotBlank() || profile?.baseUrl?.trimEnd('/') == input.baseUrl.trim().trimEnd('/')
    var catalog by remember(input.key, input.baseUrl) { mutableStateOf<List<String>?>(null) }
    var catalogError by remember(input.key, input.baseUrl) { mutableStateOf<String?>(null) }
    var loading by remember(input.key, input.baseUrl) { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    LaunchedEffect(input.key, input.baseUrl, refresh) {
        if (!canList || input.baseUrl.isBlank()) return@LaunchedEffect
        loading = true
        catalogError = null
        try {
            delay(900)
            val available = loadModels(input.baseUrl, input.key, profile)
            catalog = available
        } catch (e: AxisException) {
            catalogError = e.message
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (enabled) close() },
        title = { Text(if (profile == null) "Новая модель" else "Изменить модель") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.provider_key_notice))
                AxisPicker("Провайдер", preset.title, ProviderPreset.entries.map { it to it.title }) { next ->
                    if (!enabled || next == preset) return@AxisPicker
                    preset = next
                    val presetInput = next.profileInput()
                    input = input.copy(
                        name = presetInput.name.ifBlank { input.name }, baseUrl = presetInput.baseUrl,
                        model = presetInput.model, key = "",
                    )
                }
                OutlinedTextField(
                    input.name, { input = input.copy(name = it) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.name_field)) }, singleLine = true, enabled = enabled,
                )
                if (preset == ProviderPreset.CUSTOM) OutlinedTextField(
                    input.baseUrl, { input = input.copy(baseUrl = it) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.base_api)) }, singleLine = true, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    input.model, { input = input.copy(model = it) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.model_id_label)) }, singleLine = true, enabled = enabled,
                )
                OutlinedTextField(
                    input.key, { input = input.copy(key = it) }, Modifier.fillMaxWidth(),
                    label = { Text(if (profile == null) "Ключ API" else "Новый ключ API") },
                    singleLine = true, enabled = enabled,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (profile != null) {
                        { Text(stringResource(R.string.keep_api_key)) }
                    } else null,
                )
                when {
                    loading -> Text(stringResource(R.string.loading_models))
                    catalogError != null -> Text(catalogError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    catalog?.isEmpty() == true -> Text(stringResource(R.string.empty_model_catalog))
                }
                Row {
                    if (!catalog.isNullOrEmpty()) {
                        TextButton(enabled = enabled, onClick = { picker = true }) { Text("Выбрать модель (${catalog?.size})") }
                    }
                    if (canList && input.baseUrl.isNotBlank()) {
                        TextButton(enabled = enabled && !loading, onClick = { refresh++ }) { Text(stringResource(R.string.refresh_list)) }
                    }
                }
                OutlinedTextField(
                    input.systemPrompt, { input = input.copy(systemPrompt = it.take(4000)) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.system_prompt_label)) }, minLines = 2, maxLines = 4, enabled = enabled,
                )
                AxisSwitch("Короткие ответы", input.concise) { if (enabled) input = input.copy(concise = it) }
                Text("Инструменты модели", style = MaterialTheme.typography.titleMedium)
                AxisTool.entries.forEach { tool ->
                    AxisSwitch(tool.label, tool in input.tools) { checked ->
                        if (enabled) input = input.copy(tools = if (checked) input.tools + tool else input.tools - tool)
                    }
                }
                if (profile != null && input.baseUrl.trim().trimEnd('/') != profile.baseUrl.trimEnd('/')) {
                    Text(stringResource(R.string.provider_address_change))
                }
                if (notice != null) Text(notice, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { save(input) }, enabled = enabled) { Text(stringResource(R.string.save_action)) } },
        dismissButton = { TextButton(onClick = close, enabled = enabled) { Text(stringResource(R.string.cancel_action)) } },
    )
    if (picker) {
        var search by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { picker = false }, title = { Text(stringResource(R.string.available_models)) }, text = {
            Column {
                OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.model_search)) }, singleLine = true)
                val matched = catalog.orEmpty().filter { it.contains(search.trim(), ignoreCase = true) }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    itemsIndexed(matched) { _, id ->
                        TextButton(onClick = { input = input.copy(model = id); picker = false }) { Text(id) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { picker = false }) { Text(stringResource(R.string.close_action)) } })
    }
}

@Composable
private fun InfoCard(title: String, text: String, actions: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(text, style = MaterialTheme.typography.bodyLarge)
            actions()
        }
    }
}

@Composable
private fun AxisLoading(prefs: AxisPreferences) {
    val accent = Color(prefs.tint)
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF171321), Color(0xFF171321), accent.copy(alpha = .19f))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.drawable.ic_axis_splash), contentDescription = null, modifier = Modifier.size(156.dp))
            Text("Axis", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(Modifier.size(width = 108.dp, height = 3.dp), color = accent)
            Text("Загрузка профилей", color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
