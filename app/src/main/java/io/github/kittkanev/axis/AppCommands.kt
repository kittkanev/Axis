package io.github.kittkanev.axis

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.util.Locale

private val directLaunch = Regex(
    """^(?:(?:axis|аксис)[ ,]+)?(?:пожалуйста[ ,]+)?(?:открой(?:те)?|запусти(?:те)?|включи|open|launch|start)\s+(?:(?:приложение|app)\s+)?(.+?)\s*[.!?]*$""",
    RegexOption.IGNORE_CASE,
)
private val possibleLaunch = Regex(
    """^(?:(?:axis|аксис)[ ,]+)?(?:пожалуйста[ ,]+)?(?:(?:можешь|можете|мог бы|можно|can you|could you|please)\s+)?(?:открой\p{L}*|открыть|запусти\p{L}*|запустить|перейди|перейти|зайди|зайти|open|launch|start)\s+\S""",
    RegexOption.IGNORE_CASE,
)
private val screenVerb = Regex(
    """^(?:(?:axis|аксис)[ ,]+)?(?:(?:пожалуйста|please|можешь|можете|can you|could you)[ ,]+)*(?:нажми(?:те)?|нажать|выбери(?:те)?|выбрать|прокрути(?:те)?|пролистай(?:те)?|введи(?:те)?|напечатай(?:те)?|вернись|click|tap|select|scroll|type|go back)(?:\s|$)""",
    RegexOption.IGNORE_CASE,
)
private val sectionConnector = Regex("""[\s,]+(?:и|затем|потом|and|then)\s+""", RegexOption.IGNORE_CASE)
private val aboutPhone = Regex(
    """^(?:(?:axis|аксис)[ ,]+)?(?:(?:пожалуйста|please|можешь|можете|can you|could you)[ ,]+)*(?:открой(?:те)?|открыть|перейди(?:те)?\s+в|зайди(?:те)?\s+в|open|go\s+to)\s+(?:(?:настройки|settings)[\s,]+(?:и(?:\s+затем)?|затем|потом|and(?:\s+then)?|then)\s+)?(?:(?:открой(?:те)?|выбери(?:те)?|нажми(?:те)?\s+на|зайди(?:те)?\s+в|перейди(?:те)?\s+в|open|select|tap|go\s+to)\s+)?(?:(?:пункт|раздел|the|page)\s+)*[«"']?(?:о\s+(?:телефоне|устройстве)|about\s+(?:phone|device))[»"']?\s*[.!?]*$""",
    RegexOption.IGNORE_CASE,
)

internal fun isAboutPhoneCommand(text: String): Boolean = aboutPhone.matches(text.trim())

internal fun openAboutPhone(context: Context): Boolean {
    if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
    return try {
        context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

internal fun isDeviceCommand(text: String): Boolean =
    permitsAppTool(text) || screenVerb.containsMatchIn(text.trim())

private val appAliases = mapOf(
    "настройки" to listOf("com.android.settings"),
    "настройках" to listOf("com.android.settings"),
    "settings" to listOf("com.android.settings"),
    "телеграм" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
    "ютуб" to listOf("com.google.android.youtube"),
    "хром" to listOf("com.android.chrome"),
    "ватсап" to listOf("com.whatsapp"),
    "камера" to listOf("camera", "камера"),
    "калькулятор" to listOf("calculator", "калькулятор"),
)

internal fun directAppQuery(text: String): String? =
    directLaunch.matchEntire(text.trim())?.groupValues?.get(1)
        ?.replace(Regex("""[, ]+пожалуйста$""", RegexOption.IGNORE_CASE), "")
        ?.trim()?.takeIf { it.length in 1..160 && !it.contains(sectionConnector) }

internal fun permitsAppTool(text: String): Boolean {
    return possibleLaunch.containsMatchIn(text.trim())
}

internal fun resolveApp(query: String, apps: List<DeviceApp>): DeviceApp? {
    fun normalized(value: String) = value.trim().trim('"', '«', '»').lowercase(Locale.ROOT).replace('ё', 'е')
    val name = normalized(query)
    if (name.isBlank()) return null
    val exact = apps.filter { normalized(it.label) == name || normalized(it.packageName) == name }
    if (exact.isNotEmpty()) return exact.singleOrNull()
    val aliases = appAliases[name] ?: return null
    return apps.filter { normalized(it.packageName) in aliases || normalized(it.label) in aliases }.singleOrNull()
}

internal fun requestedApp(context: Context, query: String): DeviceApp =
    resolveApp(query, installedApps(context))
        ?: throw AxisException("Не нашёл «$query». Назовите приложение так, как оно показано в Android.")

internal fun launchRequestedApp(context: Context, query: String): String {
    val app = requestedApp(context, query)
    if (!openApp(context, app)) throw AxisException("Не удалось открыть «${app.label}».")
    return app.label
}
