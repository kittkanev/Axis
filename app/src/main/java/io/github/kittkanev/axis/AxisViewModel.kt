package io.github.kittkanev.axis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.UUID

internal enum class Operation { LOADING, SAVING, SENDING, TESTING }

internal data class AxisState(
    val workspace: Workspace = Workspace(),
    val operation: Operation? = Operation.LOADING,
    val loadFailed: Boolean = false,
    val notice: String? = null,
    val pendingMessage: Message? = null,
)

internal class AxisViewModel(application: Application) : AndroidViewModel(application) {
    private val store = WorkspaceStore(application)
    private val provider = OpenAiProvider()
    private val mutableState = MutableStateFlow(AxisState())
    val state = mutableState.asStateFlow()
    private val mutableDraft = MutableStateFlow("")
    val draft = mutableDraft.asStateFlow()
    private var request: Job? = null

    fun setDraft(text: String) { mutableDraft.value = text }

    init { load() }

    fun load() {
        update { copy(operation = Operation.LOADING, loadFailed = false, notice = null) }
        viewModelScope.launch {
            try {
                val workspace = withContext(Dispatchers.IO) { store.load() }
                update { copy(workspace = workspace, operation = null) }
            } catch (e: AxisException) {
                update { copy(operation = null, loadFailed = true, notice = e.message) }
            } catch (_: IOException) {
                update { copy(operation = null, loadFailed = true, notice = "Не удалось открыть локальные данные.") }
            }
        }
    }

    fun saveProfile(id: String?, input: ProfileInput, onSaved: () -> Unit) {
        run(Operation.SAVING) { workspace ->
            val existing = workspace.profiles.firstOrNull { it.id == id }
            if (existing == null && workspace.profiles.size >= MAX_PROFILES) {
                throw AxisException("Можно сохранить до $MAX_PROFILES профилей.")
            }
            val settings = validateProfile(input, existing)
            val profileId = id ?: UUID.randomUUID().toString()
            val baseUrl = settings.baseUrl.toString()
            val encryptedKey = if (settings.key.isNotEmpty()) {
                withContext(Dispatchers.IO) { store.encryptKey(profileId, baseUrl, settings.key) }
            } else {
                requireNotNull(existing).encryptedKey
            }
            persist(workspace.saveProfile(Profile(profileId, settings.name, baseUrl, settings.model, encryptedKey)))
            onSaved()
        }
    }

    fun selectProfile(id: String) = run(Operation.SAVING) { workspace ->
        if (workspace.profiles.any { it.id == id }) persist(workspace.copy(activeId = id))
    }

    fun removeProfile(id: String) = run(Operation.SAVING) { workspace ->
        persist(workspace.removeProfile(id))
    }

    fun clearChat() = run(Operation.SAVING) { workspace ->
        val id = workspace.activeId ?: return@run
        persist(workspace.copy(chats = workspace.chats - id))
    }

    fun send(text: String, onSaved: () -> Unit) = run(Operation.SENDING) { workspace ->
        val profile = workspace.active ?: throw AxisException("Сначала добавьте профиль модели.")
        val prompt = Message(Role.USER, text.trim())
        if (prompt.text.isEmpty() || prompt.text.length > MAX_PROMPT_LENGTH) {
            throw AxisException("Введите от 1 до $MAX_PROMPT_LENGTH символов.")
        }
        update { copy(pendingMessage = prompt) }
        val key = withContext(Dispatchers.IO) { store.readKey(profile) }
        val reply = provider.complete(
            profile.baseUrl.toHttpUrl(), profile.model, key, chatContext(workspace.messages, prompt),
        )
        val nextMessages = retainChat(workspace.messages + prompt + Message(Role.ASSISTANT, reply))
        update { copy(operation = Operation.SAVING) }
        persist(workspace.copy(chats = workspace.chats + (profile.id to nextMessages)))
        onSaved()
    }

    fun testProfile(profile: Profile) = run(Operation.TESTING) {
        val key = withContext(Dispatchers.IO) { store.readKey(profile) }
        provider.complete(
            profile.baseUrl.toHttpUrl(), profile.model, key,
            listOf(Message(Role.USER, "Ответь одним словом: OK.")),
        )
        update { copy(notice = "Модель «${profile.name}» ответила на тестовый запрос.") }
    }

    fun cancelRequest() {
        val operation = mutableState.value.operation
        if (operation != Operation.SENDING && operation != Operation.TESTING) return
        request?.cancel()
        update { copy(notice = "Запрос остановлен.") }
    }

    fun dismissNotice() = update { copy(notice = null) }

    private fun run(operation: Operation, block: suspend (Workspace) -> Unit) {
        val current = mutableState.value
        if (current.operation != null || current.loadFailed) return
        update { copy(operation = operation, notice = null) }
        request = viewModelScope.launch {
            try {
                block(current.workspace)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AxisException) {
                update { copy(notice = e.message) }
            } catch (_: IOException) {
                update { copy(notice = "Не удалось сохранить или прочитать локальные данные.") }
            } finally {
                update { copy(operation = null, pendingMessage = null) }
            }
        }
    }

    private suspend fun persist(workspace: Workspace) {
        withContext(Dispatchers.IO) { store.save(workspace) }
        val previous = mutableState.value.workspace.active
        if (previous?.id != workspace.active?.id || previous?.baseUrl != workspace.active?.baseUrl) {
            setDraft("")
        }
        update { copy(workspace = workspace) }
    }

    private inline fun update(crossinline transform: AxisState.() -> AxisState) {
        mutableState.update { it.transform() }
    }
}
