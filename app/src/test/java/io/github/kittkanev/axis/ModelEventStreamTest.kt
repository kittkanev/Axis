package io.github.kittkanev.axis

import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelEventStreamTest {
    private fun frame(delta: JSONObject, finish: String? = null): String = "data: " + JSONObject().put("choices",
        JSONArray().put(JSONObject().put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL))) + "\r\n\r\n"

    @Test
    fun rendersChunksBeforeCompletionAndRejectsTruncation() {
        val one = frame(JSONObject().put("content", "Hello"))
        val two = frame(JSONObject().put("content", " world"), "stop")
        val partial = mutableListOf<String>()
        val reply = readEventReply(Buffer().writeUtf8(": ping\r\n\r\n$one$two" + "data: [DONE]\r\n\r\n"), emptySet(), partial::add)
        assertEquals(listOf("Hello", "Hello world"), partial)
        assertEquals(ModelReply("Hello world"), reply)
        assertThrows(AxisException::class.java) { readEventReply(Buffer().writeUtf8(one), emptySet()) {} }
    }

    @Test
    fun combinesToolArgumentsSplitAcrossEvents() {
        val first = JSONObject().put("type", "function").put("index", 0).put("function", JSONObject()
            .put("name", "open_app").put("arguments", "{\"name\":"))
        val second = JSONObject().put("index", 0).put("function", JSONObject().put("arguments", "\"Settings\"}"))
        val stream = Buffer().writeUtf8(frame(JSONObject().put("tool_calls", JSONArray().put(first))) +
            frame(JSONObject().put("tool_calls", JSONArray().put(second)), "tool_calls"))
        assertEquals(AxisToolCall(AxisTool.OPEN, "Settings"), readEventReply(stream, setOf(AxisTool.OPEN)) {}.tool)
    }
}
