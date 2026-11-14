package io.github.kittkanev.axis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelVoiceTest {
    @Test
    fun loadsExistingProfilesWithDefaultVoice() {
        assertEquals(ModelVoice(), null.toModelVoice())
    }

    @Test
    fun profileVoiceRoundTripsAndRejectsInvalidSettings() {
        val voice = ModelVoice("de-DE", "custom", 1.2f, 0.8f)
        assertEquals(voice, voice.toJson().toModelVoice())
        val invalid = JSONObject().put("language", "invalid").put("rate", 2).put("pitch", 0)
        assertEquals(ModelVoice(rate = 1.5f, pitch = 0.7f), invalid.toModelVoice())
    }
}
