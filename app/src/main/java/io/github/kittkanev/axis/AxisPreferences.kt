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

internal enum class OverlayPosition(val label: String) {
    LEFT("Слева"), CENTER("По центру"), RIGHT("Справа"),
}

internal data class OverlayAnchor(val x: Float, val y: Float)

internal data class AxisPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.CORAL,
    val dim: Float = 0.62f,
    val orbSize: Int = 82,
    val motion: Boolean = true,
    val openingSound: Boolean = true,
    val accessibilityOverlay: Boolean = false,
    val overlayPosition: OverlayPosition = OverlayPosition.RIGHT,
    val overlayOpacity: Float = .95f,
    val overlayOffset: Int = 32,
    val overlayAnchor: OverlayAnchor? = null,
    val compactSize: Int = 48,
    val customColor: Long? = null,

    val autoListen: Boolean = false,
    val wakeWord: Boolean = false,
    val wakeLanguage: String = "ru-RU",
    val autoControl: Boolean = false,
    val autoSpeak: Boolean = true,
    val autoSend: Boolean = true,
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
            openingSound = disk.getBoolean("openingSound", true),
            accessibilityOverlay = disk.getBoolean("accessibilityOverlay", isXiaomiDevice),
            overlayPosition = OverlayPosition.entries.firstOrNull { it.name == disk.getString("overlayPosition", null) } ?: OverlayPosition.RIGHT,
            overlayOpacity = disk.getFloat("overlayOpacity", .95f).coerceIn(.35f, 1f),
            overlayOffset = disk.getInt("overlayOffset", 32).coerceIn(0, 96),
            overlayAnchor = if (disk.contains("overlayX") && disk.contains("overlayY")) {
                OverlayAnchor(disk.getFloat("overlayX", 1f).coerceIn(0f, 1f), disk.getFloat("overlayY", 1f).coerceIn(0f, 1f))
            } else null,
            compactSize = disk.getInt("compactSize", 48).coerceIn(38, 64),
            customColor = disk.getLong("customColor", -1L).takeIf { it in 0xFF000000L..0xFFFFFFFFL },

            autoListen = disk.getBoolean("autoListen", false),
            wakeWord = disk.getBoolean("wakeWord", false),
            wakeLanguage = if (disk.getString("wakeLanguage", null) == "en-US") "en-US" else "ru-RU",
            autoControl = disk.getBoolean("autoControl", false),
            autoSpeak = disk.getBoolean("autoSpeak", true),
            autoSend = disk.getBoolean("autoSend", true),
            recognitionLanguage = disk.getString("recognitionLanguage", "ru-RU")
                ?.takeIf { tag -> voiceLanguages.any { it.first == tag } } ?: "ru-RU",
        ),
    )
    val state = current.asStateFlow()

    fun update(value: AxisPreferences) {
        current.value = value
        disk.edit {
            val anchor = value.overlayAnchor
            if (anchor == null) remove("overlayX").remove("overlayY")
            else putFloat("overlayX", anchor.x).putFloat("overlayY", anchor.y)
            putString("theme", value.theme.name).putString("accent", value.accent.name)
                .putFloat("dim", value.dim).putInt("orbSize", value.orbSize).putBoolean("motion", value.motion)
                .putBoolean("openingSound", value.openingSound).putBoolean("accessibilityOverlay", value.accessibilityOverlay)
                .putString("overlayPosition", value.overlayPosition.name)
                .putFloat("overlayOpacity", value.overlayOpacity).putInt("overlayOffset", value.overlayOffset)
                .putInt("compactSize", value.compactSize).putLong("customColor", value.customColor ?: -1L)
                .putBoolean("autoListen", value.autoListen).putBoolean("autoSpeak", value.autoSpeak)
                .putBoolean("autoSend", value.autoSend).putBoolean("autoControl", value.autoControl)
                .putBoolean("wakeWord", value.wakeWord).putString("wakeLanguage", value.wakeLanguage)
                .putString("recognitionLanguage", value.recognitionLanguage)
        }
    }
}

class AxisApplication : Application() {
    internal val cloud by lazy { AxisCloud(this) }
    private val workspaceModels = ViewModelStore()
    internal var requestMicrophone = false
    internal val preferences by lazy { AxisPreferenceStore(this) }
    internal val workspace by lazy {
        ViewModelProvider(workspaceModels, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[AxisViewModel::class.java]
    }
}

internal val AxisPreferences.tint: Long get() = customColor ?: accent.color

internal val voiceLanguages = listOf(
    "ru-RU" to "Русский", "en-US" to "English", "de-DE" to "Deutsch",
    "es-ES" to "Español", "fr-FR" to "Français",
)

internal data class ModelVoice(
    val language: String = "ru-RU",
    val name: String? = null,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val neuralVoice: String? = null,
    val speechStyle: SpeechStyle = SpeechStyle.NATURAL,
    val piper: Boolean = false,
)


internal val isXiaomiDevice: Boolean
    get() = android.os.Build.MANUFACTURER.lowercase(java.util.Locale.ROOT) in setOf("xiaomi", "poco", "redmi") ||
        android.os.Build.BRAND.lowercase(java.util.Locale.ROOT) in setOf("xiaomi", "poco", "redmi")
