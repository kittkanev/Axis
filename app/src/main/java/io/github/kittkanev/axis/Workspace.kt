package io.github.kittkanev.axis

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
)

internal data class ProfileInput(
    val name: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val key: String = "",
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

internal data class Workspace(
    val profiles: List<Profile> = emptyList(),
    val activeId: String? = null,
    val chats: Map<String, List<Message>> = emptyMap(),
) {
    val active: Profile? get() = profiles.firstOrNull { it.id == activeId }
    val messages: List<Message> get() = chats[activeId].orEmpty()

    fun saveProfile(profile: Profile): Workspace {
        val previous = profiles.firstOrNull { it.id == profile.id }
        val nextProfiles = if (previous == null) profiles + profile
        else profiles.map { if (it.id == profile.id) profile else it }
        val nextChats = if (previous != null && previous.baseUrl != profile.baseUrl) {
            chats - profile.id
        } else {
            chats
        }
        return copy(profiles = nextProfiles, activeId = activeId ?: profile.id, chats = nextChats)
    }

    fun removeProfile(id: String): Workspace {
        val remaining = profiles.filterNot { it.id == id }
        val nextActive = if (activeId == id) remaining.firstOrNull()?.id else activeId
        return copy(profiles = remaining, activeId = nextActive, chats = chats - id)
    }
}

internal class AxisException(message: String) : Exception(message)

internal fun validateProfile(input: ProfileInput, existing: Profile?): ProfileSettings {
    val name = input.name.trim()
    val model = input.model.trim()
    val key = input.key.trim()
    val address = input.baseUrl.trim().toHttpUrlOrNull()
    if (name.isEmpty() || name.length > 80) throw AxisException("Укажите название до 80 символов.")
    if (model.isEmpty() || model.length > 200) throw AxisException("Укажите ID модели до 200 символов.")
    if (address == null || !address.isHttps || address.username.isNotEmpty() ||
        address.password.isNotEmpty() || address.query != null || address.fragment != null ||
        address.encodedPath.trimEnd('/').endsWith("/chat/completions")
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
    return ProfileSettings(name, baseUrl, model, key)
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
