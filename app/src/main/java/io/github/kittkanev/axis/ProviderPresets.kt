package io.github.kittkanev.axis

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val GOOGLE_API_HOST = "generativelanguage.googleapis.com"
internal const val GOOGLE_CHAT_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
internal const val DEFAULT_SYSTEM_PROMPT = "Ты Axis, помощник на Android. Отвечай на языке пользователя."

internal enum class ProviderPreset(val title: String, val baseUrl: String, val model: String) {
    GOOGLE("Google AI Studio", GOOGLE_CHAT_URL, "gemini-3.1-flash-lite"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-3.1-flash-lite"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    GROQ("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    CUSTOM("Свой HTTPS API", "", ""),
    ;

    fun profileInput() = ProfileInput(name = if (this == CUSTOM) "" else title, baseUrl = baseUrl, model = model)

    companion object {
        fun forUrl(raw: String): ProviderPreset {
            val address = raw.trim().toHttpUrlOrNull() ?: return CUSTOM
            return entries.firstOrNull { address.toString().trimEnd('/') == it.baseUrl } ?: CUSTOM
        }
    }
}

internal fun Profile.systemMessage(voiceTurn: Boolean): Message {
    val brevity = when {
        voiceTurn -> "Для голосового ответа используй 1–2 коротких предложения без Markdown."
        concise -> "По умолчанию отвечай прямо, в 1–3 коротких предложениях. Раскрывай детали, только если пользователь попросил."
        else -> ""
    }
    return Message(Role.SYSTEM, listOf(systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }, brevity)
        .filter(String::isNotBlank).joinToString("\n\n"))
}
