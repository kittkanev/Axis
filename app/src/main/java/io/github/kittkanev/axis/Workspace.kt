package io.github.kittkanev.axis

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

internal const val MAX_PROMPT_LENGTH = 12_000
internal const val MAX_SAVED_MESSAGES = 100
private const val MAX_SAVED_CHAT_LENGTH = 200_000
internal const val MAX_CONTEXT_MESSAGES = 20
internal const val MAX_CONTEXT_LENGTH = 48_000
internal const val MAX_PROFILES = 12

internal data class Profile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val encryptedKey: String,
    val voice: ModelVoice = ModelVoice(),
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val concise: Boolean = true,
    val tools: Set<AxisTool> = defaultAxisTools,
    val managed: Boolean = false,
)

internal data class ProfileInput(
    val name: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val key: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val concise: Boolean = true,
    val tools: Set<AxisTool> = defaultAxisTools,
)

internal data class ProfileSettings(
    val name: String,
    val baseUrl: HttpUrl,
    val model: String,
    val key: String,
)

internal enum class Role(val apiName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

internal data class Message(val role: Role, val text: String)
internal data class Conversation(val id: String, val title: String, val messages: List<Message> = emptyList())
internal const val MAX_CONVERSATIONS = 30

internal data class Workspace(
    val profiles: List<Profile> = emptyList(),
    val activeId: String? = null,
    val chats: Map<String, List<Message>> = emptyMap(),
    val conversations: Map<String, List<Conversation>> = emptyMap(),
    val activeConversations: Map<String, String> = emptyMap(),
) {
    val active: Profile? get() = profiles.firstOrNull { it.id == activeId }
    fun conversationsFor(profileId: String): List<Conversation> = conversations[profileId]
        ?: chats[profileId]?.takeIf { it.isNotEmpty() }?.let { listOf(Conversation("legacy", "", it)) }.orEmpty()
    val threads: List<Conversation> get() = activeId?.let(::conversationsFor).orEmpty()
    val conversation: Conversation? get() = threads.firstOrNull { it.id == activeConversations[activeId] } ?: threads.firstOrNull()
    val messages: List<Message> get() = conversation?.messages.orEmpty()

    fun updateConversations(items: List<Conversation>, activeChat: String?): Workspace {
        val profileId = activeId ?: return this
        return copy(
            chats = chats - profileId,
            conversations = conversations + (profileId to items),
            activeConversations = if (activeChat == null) activeConversations - profileId
                else activeConversations + (profileId to activeChat),
        )
    }

    fun withMessages(messages: List<Message>): Workspace {
        val current = conversation
        val updated = current?.copy(messages = messages) ?: Conversation(UUID.randomUUID().toString(), "", messages)
        val items = if (current == null) threads + updated else threads.map { if (it.id == updated.id) updated else it }
        return updateConversations(items, updated.id)
    }

    fun saveProfile(profile: Profile): Workspace {
        val previous = profiles.firstOrNull { it.id == profile.id }
        val nextProfiles = if (previous == null) profiles + profile
        else profiles.map { if (it.id == profile.id) profile else it }
        val updated = copy(profiles = nextProfiles, activeId = activeId ?: profile.id)
        return if (previous == null || previous.baseUrl == profile.baseUrl) updated else updated.copy(
            chats = chats - profile.id, conversations = conversations - profile.id,
            activeConversations = activeConversations - profile.id,
        )
    }

    fun removeProfile(id: String): Workspace {
        val remaining = profiles.filterNot { it.id == id }
        val nextActive = if (activeId == id) remaining.firstOrNull()?.id else activeId
        return copy(profiles = remaining, activeId = nextActive, chats = chats - id,
            conversations = conversations - id, activeConversations = activeConversations - id)
    }
}

internal class AxisException(message: String, val httpStatus: Int? = null, val retryAfterMillis: Long? = null) : Exception(message)

internal fun validateProfile(input: ProfileInput, existing: Profile?): ProfileSettings {
    val name = input.name.trim()
    val model = input.model.trim()
    if (name.isEmpty() || name.length > 80) throw AxisException("Укажите название до 80 символов.")
    if (model.isEmpty() || model.length > 200) throw AxisException("Укажите ID модели до 200 символов.")
    val (baseUrl, key) = validateConnection(input.baseUrl, input.key, existing)
    return ProfileSettings(name, baseUrl, model, key)
}

internal fun validateConnection(rawUrl: String, rawKey: String, existing: Profile?): Pair<HttpUrl, String> {
    val key = rawKey.trim()
    val address = rawUrl.trim().toHttpUrlOrNull()
    if (address == null || !address.isHttps || address.username.isNotEmpty() ||
        address.password.isNotEmpty() || address.query != null || address.fragment != null ||
        address.encodedPath.trimEnd('/').let { it.endsWith("/chat/completions") || it.endsWith("/models") }
    ) {
        throw AxisException("Введите базовый адрес HTTPS, например https://api.openai.com/v1.")
    }
    val baseUrl = address.newBuilder().encodedPath(address.encodedPath.trimEnd('/').ifEmpty { "/" }).build()
    if (key.isEmpty() && (existing == null || existing.baseUrl != baseUrl.toString())) {
        throw AxisException("Введите ключ для этого адреса API.")
    }
    if (key.length > 4096 || key.any { it !in '!'..'~' }) {
        throw AxisException("Проверьте формат ключа API.")
    }
    return baseUrl to key
}

internal fun chatContext(history: List<Message>, prompt: Message): List<Message> {
    val selected = mutableListOf(prompt)
    var length = prompt.text.length
    for (turn in history.takeLast(MAX_CONTEXT_MESSAGES - 2).chunked(2).asReversed()) {
        val turnLength = turn.sumOf { it.text.length }
        if (length + turnLength > MAX_CONTEXT_LENGTH) break
        selected.addAll(turn.asReversed())
        length += turnLength
    }
    return selected.asReversed()
}

internal fun retainChat(messages: List<Message>): List<Message> {
    var length = 0
    val retained = messages.takeLast(MAX_SAVED_MESSAGES).chunked(2).asReversed().takeWhile { turn ->
        length += turn.sumOf { it.text.length }
        length <= MAX_SAVED_CHAT_LENGTH
    }
    return retained.asReversed().flatten()
}
