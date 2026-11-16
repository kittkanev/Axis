package io.github.kittkanev.axis

import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

internal fun readEventReply(source: BufferedSource, tools: Set<AxisTool>, onPartial: (String) -> Unit): ModelReply {
    val text = StringBuilder()
    val name = StringBuilder()
    val arguments = StringBuilder()
    val payload = StringBuilder()
    var finish = ""
    var done = false
    var toolType = ""
    var inputSize = 0

    fun handleEvent() {
        val data = payload.toString().trim()
        payload.clear()
        if (data.isEmpty()) return
        if (data == "[DONE]") {
            done = true
            return
        }
        val event = JSONObject(data)
        if (event.has("error")) throw AxisException("Провайдер прервал поток ответа.")
        val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: return
        val delta = choice.optJSONObject("delta")
        val content = delta?.opt("content") as? String
        if (!content.isNullOrEmpty()) {
            text.append(content)
            if (text.length > 48_000) throw AxisException("Ответ модели слишком длинный.")
            onPartial(text.toString())
        }
        val functionCalls = delta?.optJSONArray("tool_calls")
        if (functionCalls != null && functionCalls.length() > 0) {
            val call = functionCalls.optJSONObject(0)
            if (functionCalls.length() > 1 || call == null || call.optInt("index", 0) != 0) {
                throw AxisException("Поток содержит больше одной команды.")
            }
            (call.opt("type") as? String)?.let { toolType = it }
            val function = call.optJSONObject("function")
            (function?.opt("name") as? String)?.let(name::append)
            (function?.opt("arguments") as? String)?.let(arguments::append)
            if (name.length > 160 || arguments.length > 12_000) throw AxisException("Команда модели слишком длинная.")
        }
        (choice.opt("finish_reason") as? String)?.let { finish = it }
    }

    while (!done && !source.exhausted()) {
        val line = source.readUtf8LineStrict(256 * 1024L)
        inputSize += line.length + 1
        if (inputSize > 2 * 1024 * 1024) throw AxisException("Поток ответа слишком большой.")
        if (line.isEmpty()) handleEvent()
        else if (line.startsWith("data:")) payload.appendLine(line.substring(5).removePrefix(" "))
    }
    handleEvent()
    if (!done && finish.isEmpty()) throw AxisException("Поток ответа завершился преждевременно.")
    val message = JSONObject().put("content", text.toString())
    if (name.isNotEmpty() || arguments.isNotEmpty() || toolType.isNotEmpty()) {
        val function = JSONObject().put("name", name.toString()).put("arguments", arguments.toString())
        message.put("tool_calls", JSONArray().put(JSONObject().put("type", toolType.ifEmpty { "function" }).put("function", function)))
    }
    return parseReply(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", finish))), tools)
}
