package io.github.kittkanev.axis

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ModelReply(val text: String = "", val tool: AxisToolCall? = null)

internal fun axisHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

internal class OpenAiProvider(private val client: OkHttpClient = axisHttpClient()) {
    suspend fun complete(
        baseUrl: HttpUrl,
        model: String,
        key: String,
        messages: List<Message>,
    ): String = respond(baseUrl, model, key, messages).text

    suspend fun respond(
        baseUrl: HttpUrl,
        model: String,
        key: String,
        messages: List<Message>,
        tools: Set<AxisTool> = emptySet(),
    ): ModelReply {
        require(baseUrl.isHttps)
        val path = baseUrl.encodedPath.trimEnd('/')
        val url = baseUrl.newBuilder().encodedPath("$path/chat/completions").build()
        val payload = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("messages", JSONArray().apply {
                messages.forEach { message ->
                    put(JSONObject().put("role", message.role.apiName).put("content", message.text))
                }
            })
        if (tools.isNotEmpty()) payload.put("tools", JSONArray(tools.map(AxisTool::declaration)))
        val request = jsonRequest(url, payload).header("Authorization", "Bearer $key").build()
        return client.readJson(request) { parseReply(it, tools) }
    }

    suspend fun models(baseUrl: HttpUrl, key: String): List<String> {
        require(baseUrl.isHttps)
        val path = baseUrl.encodedPath.trimEnd('/')
        val url = baseUrl.newBuilder().encodedPath("$path/models").build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $key").header("Accept", "application/json").build()
        return client.readJson(request, maxBytes = 8 * 1024 * 1024L) { json ->
            val data = json.getJSONArray("data")
            (0 until data.length()).mapNotNull { index ->
                val entry = data.optJSONObject(index) ?: return@mapNotNull null
                val id = entry.opt("id") as? String ?: return@mapNotNull null
                val output = entry.optJSONObject("architecture")?.optJSONArray("output_modalities")
                val endpoints = entry.optJSONArray("supported_endpoints")
                id.takeIf {
                    it.length in 1..200 && it.none(Char::isISOControl) &&
                        (output == null || (0 until output.length()).any { output.optString(it) == "text" }) &&
                        (endpoints == null || (0 until endpoints.length()).any { endpoints.optString(it) == "/v1/chat/completions" })
                }
            }.distinct().sorted()
        }
    }
}

internal fun jsonRequest(url: HttpUrl, payload: JSONObject): Request.Builder = Request.Builder()
    .url(url).header("Accept", "application/json")
    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

internal suspend fun <T> OkHttpClient.readJson(
    request: Request,
    maxBytes: Long = 512 * 1024L,
    parse: (JSONObject) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(networkError(e))
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                continuation.resume(response.use { parse(readJsonBody(it, maxBytes)) })
            } catch (e: AxisException) {
                continuation.resumeWithException(e)
            } catch (e: IOException) {
                continuation.resumeWithException(networkError(e))
            } catch (_: JSONException) {
                continuation.resumeWithException(AxisException("Провайдер вернул ответ в неподдерживаемом формате."))
            }
        }
    })
}

private fun readJsonBody(response: Response, maxBytes: Long): JSONObject {
    if (!response.isSuccessful) {
        val error = when (response.code) {
            in 300..399 -> "API перенаправляет запрос. Укажите конечный HTTPS-адрес."
            401, 403 -> "Провайдер отклонил ключ или доступ к модели."
            402 -> "Проверьте баланс у провайдера."
            404 -> "Проверьте базовый адрес API и ID модели."
            408 -> "Провайдер сообщил об истечении времени ожидания."
            429 -> "Провайдер ограничил запросы. Проверьте лимиты и баланс."
            in 500..599 -> "Провайдер временно недоступен (HTTP ${response.code})."
            else -> "Провайдер отклонил запрос (HTTP ${response.code}). Проверьте модель и адрес API."
        }
        throw AxisException(error)
    }
    val body = response.body ?: throw AxisException("Провайдер вернул пустой ответ.")
    val source = body.source()
    if (source.request(maxBytes + 1)) throw AxisException("Ответ провайдера слишком большой.")
    return JSONObject(source.readUtf8())
}

private fun parseReply(json: JSONObject, tools: Set<AxisTool>): ModelReply {
    val choice = json.optJSONArray("choices")?.optJSONObject(0)
    val message = choice?.optJSONObject("message")
    val calls = message?.optJSONArray("tool_calls")
    if (calls != null && calls.length() != 0) {
        if (calls.length() != 1 || choice.optString("finish_reason") == "length") {
            throw AxisException("Модель должна предложить одно завершённое действие за запрос.")
        }
        val call = calls.getJSONObject(0)
        if (call.optString("type") != "function") throw AxisException("Тип команды модели не поддерживается.")
        return ModelReply(tool = parseAxisTool(call.getJSONObject("function"), tools))
    }
    val text = (message?.opt("content") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw AxisException("Модель не вернула текстовый ответ.")
    if (text.length > 48_000) throw AxisException("Ответ модели слишком длинный.")
    return ModelReply(text = text)
}

private fun networkError(e: IOException): AxisException = AxisException(
        when (e) {
            is SocketTimeoutException, is InterruptedIOException -> "Истекло время ожидания ответа."
            is SSLException -> "Не удалось подтвердить защищённое соединение с провайдером."
            else -> "Не удалось подключиться. Проверьте интернет и адрес провайдера."
        },
    )
