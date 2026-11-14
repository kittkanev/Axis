package io.github.kittkanev.axis

import okhttp3.HttpUrl.Companion.toHttpUrl
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
)

internal data class SpeechPcm(val data: ByteArray, val sampleRate: Int) {
    fun wave(): ByteArray = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data.size)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
        putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)).putInt(data.size).put(data)
    }.array()
}

internal class GeminiVoice {
    private val client = axisHttpClient()

    suspend fun synthesize(key: String, text: String, voice: String): SpeechPcm {
        val url = "https://$GOOGLE_API_HOST/v1beta/models/gemini-2.5-flash-preview-tts:generateContent".toHttpUrl()
        val message = JSONObject().put("text", "Read the text below verbatim in its original language, in a natural conversational tone.\n\n${text.trim().take(1200)}")
        val config = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
        val payload = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(message))))
            .put("generationConfig", config)
        val request = jsonRequest(url, payload).header("x-goog-api-key", key).build()
        return client.readJson(request, 6 * 1024 * 1024) { response ->
            val parts = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val audio = (0 until (parts?.length() ?: 0)).firstNotNullOfOrNull { index ->
                parts?.optJSONObject(index)?.optJSONObject("inlineData")
            } ?: throw AxisException("Gemini TTS не вернул звук.")
            val mime = audio.getString("mimeType")
            val rate = Regex("""(?:^|;)\s*rate=(\d+)""").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
            val bytes = try {
                java.util.Base64.getDecoder().decode(audio.getString("data"))
            } catch (_: IllegalArgumentException) {
                throw AxisException("Gemini TTS вернул повреждённое аудио.")
            }
            if (!mime.startsWith("audio/L16", ignoreCase = true) || rate !in 8_000..48_000 ||
                bytes.size < 2 || bytes.size % 2 != 0
            ) throw AxisException("Неподдерживаемый формат звука Gemini TTS.")
            SpeechPcm(bytes, rate)
        }
    }
}
