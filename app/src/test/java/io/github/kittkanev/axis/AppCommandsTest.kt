package io.github.kittkanev.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCommandsTest {
    @Test
    fun appLauncherUnderstandsCommandsAndRejectsQuestions() {
        assertEquals("Настройки", directAppQuery("Axis, открой Настройки, пожалуйста."))
        assertEquals("Settings", directAppQuery("Open Settings"))
        assertNull(directAppQuery("Как открыть Настройки?"))
        assertTrue(permitsAppTool("Можешь открыть мне настройки телефона?"))
        assertFalse(permitsAppTool("Объясни, как открыть настройки"))
        assertFalse(permitsAppTool("Не открывай настройки"))
    }

    @Test
    fun launchMatchesAliasesAndNeverGuessesBetweenEqualNames() {
        val settings = DeviceApp("Settings", "com.android.settings")
        assertEquals(settings, resolveApp("настройки", listOf(settings)))
        assertNull(resolveApp("Settings", listOf(settings, DeviceApp("Settings", "org.example.settings"))))
        assertNull(resolveApp("Set", listOf(settings)))
    }

    @Test
    fun presetAndSystemPromptRequestCompactSpeech() {
        val default = ProviderPreset.GOOGLE.profileInput()
        assertEquals("gemini-3.1-flash-lite", default.model)
        val profile = Profile("id", default.name, default.baseUrl, default.model, "unused",
            systemPrompt = "Пиши по-русски.", concise = false)
        assertEquals("Пиши по-русски.", profile.systemMessage(false).text)
        assertTrue(profile.systemMessage(true).text.contains("1–2"))
    }

    @Test
    fun speechMakesAPlayableWaveHeader() {
        val bytes = SpeechPcm(byteArrayOf(8, 0), 24_000).wave()
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(46, bytes.size)
        assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
    }
}
