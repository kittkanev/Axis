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

internal class OpenAiProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
) {
    suspend fun complete(
        baseUrl: HttpUrl,
        model: String,
        key: String,
        messages: List<Message>,
    ): String = suspendCancellableCoroutine { continuation ->
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
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(networkError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    continuation.resume(response.use(::readReply))
                } catch (e: AxisException) {
                    continuation.resumeWithException(e)
                } catch (e: IOException) {
                    continuation.resumeWithException(networkError(e))
                }
            }
        })
    }

    private fun readReply(response: Response): String {
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
        if (source.request(512 * 1024L + 1)) throw AxisException("Ответ провайдера слишком большой.")
        val message = try {
            JSONObject(source.readUtf8()).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.opt("content") as? String
        } catch (_: JSONException) {
            throw AxisException("Провайдер вернул ответ в неподдерживаемом формате.")
        }
        val text = message?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw AxisException("Модель не вернула текстовый ответ.")
        if (text.length > 48_000) throw AxisException("Ответ модели слишком длинный.")
        return text
    }

    private fun networkError(e: IOException): AxisException = AxisException(
        when (e) {
            is SocketTimeoutException, is InterruptedIOException -> "Истекло время ожидания ответа."
            is SSLException -> "Не удалось подтвердить защищённое соединение с провайдером."
            else -> "Не удалось подключиться. Проверьте интернет и адрес провайдера."
        },
    )
}
