package io.github.kittkanev.axis

import org.json.JSONArray
import java.util.Locale

internal enum class DeviceStepKind(val wire: String, val title: String) {
    CLICK("click", "Нажать"),
    TYPE("type", "Ввести"),
    READ("read", "Прочитать экран"),
    UP("up", "Прокрутить вверх"),
    DOWN("down", "Прокрутить вниз"),
    BACK("back", "Назад"),
}

internal data class DeviceStep(val kind: DeviceStepKind, val value: String = "") {
    val description: String get() = if (value.isEmpty()) kind.title else "${kind.title}: $value"
}

internal data class DeviceApp(val label: String, val packageName: String)

internal fun parseDevicePlan(text: String): List<DeviceStep> {
    val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.size !in 1..10) throw AxisException("Добавьте от 1 до 10 шагов.")
    return lines.mapIndexed { index, line ->
        val command = line.substringBefore(' ').lowercase(Locale.ROOT)
        val argument = line.substringAfter(' ', "").trim()
        val kind = DeviceStepKind.entries.find { it.wire == command }
            ?: throw AxisException("Шаг ${index + 1}: неизвестная команда.")
        val withValue = kind == DeviceStepKind.CLICK || kind == DeviceStepKind.TYPE
        if ((withValue && (argument.isBlank() || argument.length > 500)) ||
            (!withValue && argument.isNotEmpty()) || argument.any(Char::isISOControl)
        ) throw AxisException("Шаг ${index + 1}: проверьте текст команды (до 500 символов).")
        DeviceStep(kind, argument)
    }
}

internal fun modelDevicePlan(raw: String): String {
    val result = try {
        val steps = JSONArray(raw.trim())
        if (steps.length() !in 1..10) throw AxisException("Модель должна предложить от 1 до 10 шагов.")
        (0 until steps.length()).joinToString("\n") { index ->
            val step = steps.getJSONObject(index)
            val action = step.getString("action")
            val value = if (step.has("value")) step.getString("value") else ""
            if (action.any(Char::isWhitespace) || value.any(Char::isISOControl)) {
                throw AxisException("Модель вернула недопустимые символы в плане.")
            }
            if (value.isEmpty()) action else "$action $value"
        }
    } catch (_: org.json.JSONException) {
        throw AxisException("Модель не вернула план в формате JSON.")
    }
    parseDevicePlan(result)
    return result
}
