package io.github.kittkanev.axis

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ThemeMode(val label: String) {
    SYSTEM("Как в системе"), DARK("Тёмная"), LIGHT("Светлая"),
}

internal enum class Accent(val label: String, val color: Long, val darkInk: Long) {
    CORAL("Коралл", 0xFFFFAA98, 0xFF9C392A),
    LILAC("Сирень", 0xFFC6B7FF, 0xFF5639AC),
    MINT("Мята", 0xFF8DE4C3, 0xFF19644E),
    SKY("Лёд", 0xFF9CD4FF, 0xFF215D89),
}

internal data class AxisPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.CORAL,
    val dim: Float = 0.62f,
    val orbSize: Int = 82,
    val motion: Boolean = true,
    val autoListen: Boolean = false,
    val autoSpeak: Boolean = true,
    val recognitionLanguage: String = "ru-RU",
)

internal class AxisPreferenceStore(application: Application) {
    private val disk = application.getSharedPreferences("axis-appearance", 0)
    private val current = MutableStateFlow(
        AxisPreferences(
            theme = ThemeMode.entries.firstOrNull { it.name == disk.getString("theme", null) } ?: ThemeMode.SYSTEM,
            accent = Accent.entries.firstOrNull { it.name == disk.getString("accent", null) } ?: Accent.CORAL,
            dim = disk.getFloat("dim", 0.62f).coerceIn(0.2f, 0.9f),
            orbSize = disk.getInt("orbSize", 82).coerceIn(64, 112),
            motion = disk.getBoolean("motion", true),
            autoListen = disk.getBoolean("autoListen", false),
            autoSpeak = disk.getBoolean("autoSpeak", true),
            recognitionLanguage = disk.getString("recognitionLanguage", "ru-RU")
                ?.takeIf { tag -> voiceLanguages.any { it.first == tag } } ?: "ru-RU",
        ),
    )
    val state = current.asStateFlow()

    fun update(value: AxisPreferences) {
        current.value = value
        disk.edit {
            putString("theme", value.theme.name).putString("accent", value.accent.name)
                .putFloat("dim", value.dim).putInt("orbSize", value.orbSize).putBoolean("motion", value.motion)
                .putBoolean("autoListen", value.autoListen).putBoolean("autoSpeak", value.autoSpeak)
                .putString("recognitionLanguage", value.recognitionLanguage)
        }
    }
}

class AxisApplication : Application() {
    private val workspaceModels = ViewModelStore()
    internal var requestMicrophone = false
    internal val preferences by lazy { AxisPreferenceStore(this) }
    internal val workspace by lazy {
        ViewModelProvider(workspaceModels, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[AxisViewModel::class.java]
    }
}

internal val voiceLanguages = listOf(
    "ru-RU" to "Русский", "en-US" to "English", "de-DE" to "Deutsch",
    "es-ES" to "Español", "fr-FR" to "Français",
)

internal data class ModelVoice(
    val language: String = "ru-RU",
    val name: String? = null,
    val rate: Float = 1f,
    val pitch: Float = 1f,
)
