package io.github.kittkanev.axis

import org.json.JSONArray
import org.json.JSONObject

internal enum class AxisTool(
    val wire: String,
    val label: String,
    private val description: String,
    val parameter: String? = null,
    val kind: DeviceStepKind? = null,
) {
    OPEN("open_app", "Запуск приложений", "Open an Android app explicitly requested by the user.", "name"),
    CLICK("click_element", "Нажатие на элементы", "Click one visible element by its exact numeric reference from the current screen. The parameter is a string such as \"3\".", "target", DeviceStepKind.CLICK),
    TYPE("type_text", "Ввод текста", "Replace text in the focused input when explicitly requested. Never enter passwords, codes, or payment details.", "text", DeviceStepKind.TYPE),
    DOWN("scroll_down", "Прокрутка вниз", "Scroll the current app down.", kind = DeviceStepKind.DOWN),
    UP("scroll_up", "Прокрутка вверх", "Scroll the current app up.", kind = DeviceStepKind.UP),
    BACK("go_back", "Кнопка «Назад»", "Perform Android Back in the current app.", kind = DeviceStepKind.BACK);

    fun declaration(): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        parameter?.let {
            properties.put(it, JSONObject().put("type", "string"))
            required.put(it)
        }
        return JSONObject().put("type", "function").put("function",
            JSONObject().put("name", wire).put("description", description).put("parameters",
                JSONObject().put("type", "object").put("properties", properties).put("required", required)))
    }
}

internal val defaultAxisTools = setOf(AxisTool.OPEN, AxisTool.CLICK, AxisTool.TYPE, AxisTool.DOWN, AxisTool.UP, AxisTool.BACK)

internal data class AxisToolCall(val tool: AxisTool, val value: String)

internal fun parseAxisTool(function: JSONObject, enabled: Set<AxisTool>): AxisToolCall {
    val tool = enabled.firstOrNull { it.wire == function.optString("name") }
        ?: throw AxisException("Модель предложила отключённую или неизвестную команду.")
    val arguments = JSONObject(function.getString("arguments"))
    val parameter = tool.parameter
    val value = if (parameter == null) "" else (arguments.opt(parameter) as? String)?.trim()
    val valid = arguments.length() == if (parameter == null) 0 else 1
    if (!valid || value == null || value.any(Char::isISOControl) || when (tool) {
            AxisTool.CLICK -> value.toIntOrNull() !in 1..40
            AxisTool.TYPE -> value.length !in 1..500
            AxisTool.OPEN -> value.length !in 1..160
            else -> false
        }
    ) throw AxisException("Модель вернула неверные параметры команды.")
    return AxisToolCall(tool, value)
}
