package io.github.kittkanev.axis

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal val geminiVoices = linkedMapOf(
    "Aoede" to "Aoede · мягкий",
    "Kore" to "Kore · уверенный",
    "Puck" to "Puck · живой",
    "Charon" to "Charon · спокойный",
    "Zephyr" to "Zephyr · яркий",
    "Leda" to "Leda · лёгкий",
    "Achernar" to "Achernar · мягкий",
    "Sadachbia" to "Sadachbia · энергичный",
    "Sulafat" to "Sulafat · тёплый",
    "Gacrux" to "Gacrux · зрелый",
)

internal enum class SpeechStyle(val label: String, val direction: String) {
    NATURAL("Разговорный", "Speak to one person in a relaxed conversation. Use natural phrasing, small pauses and subtle changes of emphasis. Preserve the original language."),
    WARM("Тёплый", "Speak with warmth and a gentle smile, with relaxed timing and light emphasis. Preserve the original language."),
    CALM("Спокойный", "Use a calm, thoughtful voice with steady pacing and short pauses between ideas. Preserve the original language."),
    ENERGETIC("Энергичный", "Use a lively, expressive delivery with conversational rhythm and clear emphasis at key words. Preserve the original language."),
}

internal data class SpeechPcm(val data: ByteArray, val sampleRate: Int) {
    fun wave(): ByteArray = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data.size)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
        putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)).putInt(data.size).put(data)
    }.array()
}

internal class GeminiVoice(
    private val client: OkHttpClient = axisHttpClient(),
    private val api: HttpUrl = "https://$GOOGLE_API_HOST/v1beta/".toHttpUrl(),
) {
    suspend fun synthesize(key: String, text: String, voice: String, style: SpeechStyle): SpeechPcm {
        val transcript = text.trim().take(1200)
        if (transcript.isEmpty()) throw AxisException("Нет текста для озвучивания.")
        return try {
            val annotation = JSONObject().put("type", "speech_metadata").put("style", style.direction)
            val content = JSONObject().put("type", "text").put("text", transcript)
                .put("annotations", JSONArray().put(annotation))
            val input = JSONObject().put("type", "user_input").put("content", JSONArray().put(content))
            val format = JSONObject().put("type", "audio").put("mime_type", "audio/l16").put("sample_rate", 24_000)
            val generation = JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice)))
            val body = JSONObject().put("model", "gemini-3.8-flash-tts").put("input", JSONArray().put(input))
                .put("response_format", format).put("generation_config", generation).put("store", false)
            request("interactions", key, body, ::parseInteraction)
        } catch (e: AxisException) {
            if (e.httpStatus != 404) throw e
            legacySpeech(key, transcript, voice, style)
        }
    }

    private suspend fun legacySpeech(key: String, text: String, voice: String, style: SpeechStyle): SpeechPcm {
        val message = JSONObject().put("text", "${style.direction} Read this transcript verbatim.\n\n$text")
        val config = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
        val payload = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(message))))
            .put("generationConfig", config)
        return request("models/gemini-3.1-flash-tts-preview:generateContent", key, payload) { response ->
            val parts = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val audio = (0 until (parts?.length() ?: 0)).firstNotNullOfOrNull { index ->
                parts?.optJSONObject(index)?.optJSONObject("inlineData")
            } ?: throw AxisException("Gemini TTS не вернул звук.")
            val mime = audio.getString("mimeType")
            val rate = Regex("""(?:^|;)\s*rate=(\d+)""").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
            decodeSpeech(audio.getString("data"), mime, rate)
        }
    }

    private suspend fun request(path: String, key: String, body: JSONObject, parse: (JSONObject) -> SpeechPcm): SpeechPcm {
        val url = requireNotNull(api.resolve(path))
        val request = jsonRequest(url, body).header("x-goog-api-key", key).build()
        return client.readJson(request, 6 * 1024 * 1024, parse)
    }

    private fun parseInteraction(response: JSONObject): SpeechPcm {
        val steps = response.getJSONArray("steps")
        for (stepIndex in steps.length() - 1 downTo 0) {
            val step = steps.optJSONObject(stepIndex) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (index in content.length() - 1 downTo 0) {
                val part = content.optJSONObject(index) ?: continue
                if (part.optString("type") == "audio") {
                    return decodeSpeech(part.getString("data"), part.optString("mime_type", "audio/l16"),
                        part.optInt("sample_rate", 24_000))
                }
            }
        }
        throw AxisException("Gemini TTS не вернул звук.")
    }
}

private fun decodeSpeech(data: String, mime: String, rate: Int): SpeechPcm {
    val bytes = try {
        java.util.Base64.getDecoder().decode(data)
    } catch (_: IllegalArgumentException) {
        throw AxisException("Gemini TTS вернул повреждённое аудио.")
    }
    if (!mime.substringBefore(';').equals("audio/L16", ignoreCase = true) ||
        rate !in 8_000..48_000 || bytes.size < 2 || bytes.size % 2 != 0
    ) throw AxisException("Gemini TTS вернул неподдерживаемое аудио.")
    return SpeechPcm(bytes, rate)
}
