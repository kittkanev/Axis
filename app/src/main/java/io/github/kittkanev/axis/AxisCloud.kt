package io.github.kittkanev.axis

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal const val CLOUD_MODEL = "deepseek-v4-1-flash-1017"

internal data class CloudState(val baseUrl: String = "", val email: String = "", val ca: String = "", val signedIn: Boolean = false)
internal data class CloudBlob(val revision: Long, val bytes: ByteArray?)
internal data class CloudUpdate(val code: Long, val version: String, val packageName: String, val url: String, val sha256: String, val changelog: String)
internal data class CloudConnection(val base: HttpUrl, val http: OkHttpClient, val token: String) {
    fun endpoint(path: String): HttpUrl = base.newBuilder().addPathSegments(path.trimStart('/')).build()
    fun authorize(request: Request.Builder): Request.Builder = if (token.isEmpty()) request else request.header("Authorization", "Bearer $token")
}

internal class AxisCloud(context: Context) {
    private val prefs = context.getSharedPreferences("axis-backend", Context.MODE_PRIVATE)
    private val vault = KeyVault("axis-backend-session-v1")
    private val defaultHttp = axisHttpClient()
    private var trusted: Pair<String, OkHttpClient>? = null
    private val renewal = Mutex()
    private val current = MutableStateFlow(load())
    val state = current.asStateFlow()

    private fun load() = CloudState(prefs.getString("url", "").orEmpty(), prefs.getString("email", "").orEmpty(),
        prefs.getString("ca", "").orEmpty(), prefs.contains("session"))

    private fun url(text: String): HttpUrl = text.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    } ?: throw AxisException("Enter a valid HTTPS server URL.")

    @Synchronized
    private fun http(ca: String): OkHttpClient {
        if (ca.isEmpty()) return defaultHttp
        trusted?.takeIf { it.first == ca }?.let { return it.second }
        try {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("axis-server", readCa(ca))
            }
            val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            return defaultHttp.newBuilder().sslSocketFactory(tls.socketFactory, manager).build().also { trusted = ca to it }
        } catch (_: Exception) {
            throw AxisException("The server CA certificate could not be loaded.")
        }
    }

    private fun readCa(ca: String): X509Certificate {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ca.byteInputStream()) as X509Certificate
        require(certificate.basicConstraints >= 0)
        certificate.checkValidity()
        return certificate
    }

    suspend fun configure(address: String, ca: String) = withContext(Dispatchers.IO) {
        val base = url(address).toString().trimEnd('/')
        http(ca)
        val previous = current.value
        if (base != previous.baseUrl || ca != previous.ca) {
            prefs.edit().putString("url", base).putString("ca", ca).remove("session").remove("email").remove("revision").remove("consent").apply()
            current.value = load()
        }
    }

    suspend fun login(address: String, ca: String, email: String, password: String, register: Boolean) {
        val base = url(address)
        val mail = email.trim().lowercase(java.util.Locale.ROOT)
        val transport = withContext(Dispatchers.IO) { http(ca) }
        val endpoint = CloudConnection(base, transport, "").endpoint(if (register) "auth/register" else "auth/login")
        val request = jsonRequest(endpoint, JSONObject().put("email", mail).put("password", password)).build()
        val token = transport.readJson(request) { it.getString("access_token") }
        withContext(Dispatchers.IO) {
            val addressKey = base.toString().trimEnd('/')
            val encrypted = vault.encrypt("$addressKey\n$mail", token)
            val editor = prefs.edit().putString("url", addressKey).putString("ca", ca).putString("email", mail).putString("session", encrypted)
            if (current.value.email != mail || current.value.baseUrl != addressKey || current.value.ca != ca) editor.remove("revision").remove("consent")
            editor.apply()
            current.value = load()
        }
    }

    fun logout() {
        prefs.edit().remove("session").remove("email").remove("revision").remove("consent").apply()
        current.value = load()
    }

    suspend fun connection(authenticated: Boolean = true): CloudConnection = withContext(Dispatchers.IO) {
        val snapshot = current.value
        val base = url(snapshot.baseUrl)
        val transport = http(snapshot.ca)
        val token = if (authenticated) {
            renewal.withLock {
                val encrypted = prefs.getString("session", null) ?: throw AxisException("Sign into Axis to continue.")
                val scope = "${snapshot.baseUrl}\n${snapshot.email}"
                val old = runCatching { vault.decrypt(scope, encrypted) }.getOrElse {
                    throw AxisException("Axis session is unavailable. Sign in again.")
                }
                val expires = runCatching {
                    JSONObject(Base64.getUrlDecoder().decode(old.split('.')[1]).decodeToString()).getLong("exp")
                }.getOrDefault(Long.MAX_VALUE)
                val now = System.currentTimeMillis() / 1000
                if (expires - now in 1..5400) {
                    val conn = CloudConnection(base, transport, old)
                    try {
                        val request = conn.authorize(jsonRequest(conn.endpoint("auth/renew"), JSONObject())).build()
                        val updated = transport.readJson(request) { it.getString("access_token") }
                        if (current.value != snapshot || prefs.getString("session", null) != encrypted) {
                            throw AxisException("Axis account changed during renewal.")
                        }
                        prefs.edit().putString("session", vault.encrypt(scope, updated)).apply()
                        updated
                    } catch (error: AxisException) {
                        if (error.httpStatus == 401) throw error
                        old
                    }
                } else old
            }
        } else ""
        CloudConnection(base, transport, token)
    }

    suspend fun get(path: String, authenticated: Boolean = true): JSONObject {
        val conn = connection(authenticated)
        val request = conn.authorize(Request.Builder().url(conn.endpoint(path))).build()
        return conn.http.readJson(request, maxBytes = 2 * WORKSPACE_SYNC_LIMIT.toLong()) { it }
    }

    suspend fun post(path: String, body: JSONObject): JSONObject {
        val conn = connection()
        return conn.http.readJson(conn.authorize(jsonRequest(conn.endpoint(path), body)).build()) { it }
    }

    suspend fun restoreBlob(): CloudBlob {
        val result = get("sync/blob")
        return CloudBlob(result.getLong("revision"), result.optString("blob").takeUnless { result.isNull("blob") }
            ?.let { Base64.getDecoder().decode(it) })
    }

    suspend fun uploadBlob(bytes: ByteArray): Long {
        val conn = connection()
        val request = conn.authorize(jsonRequest(conn.endpoint("sync/blob"), JSONObject()
            .put("revision", prefs.getLong("revision", 0)).put("blob", Base64.getEncoder().encodeToString(bytes)))).build()
        val revision = conn.http.readJson(request.newBuilder().put(requireNotNull(request.body)).build()) { it.getLong("revision") }
        markRevision(revision)
        return revision
    }

    fun markRevision(value: Long) { prefs.edit().putLong("revision", value).apply() }

    fun allowCoarena() { prefs.edit().putBoolean("consent", true).apply() }

    suspend fun managedConnection(): CloudConnection {
        if (!prefs.getBoolean("consent", false)) throw AxisException("Enable DeepSeek in Axis Cloud first.")
        return connection()
    }

    suspend fun version(): CloudUpdate = get("version.json", false).let {
        CloudUpdate(it.getLong("versionCode"), it.getString("versionName"), it.getString("packageName"),
            it.getString("url"), it.getString("sha256"), it.optString("changelog"))
    }

    suspend fun download(update: CloudUpdate, target: File) {
        val conn = connection(false)
        val endpoint = conn.base.resolve(update.url)?.takeIf { it.isHttps && it.host == conn.base.host && it.port == conn.base.port }
            ?: throw AxisException("APK URL must use the configured HTTPS server.")
        conn.http.readResponse(Request.Builder().url(endpoint).build()) { response ->
            val hash = MessageDigest.getInstance("SHA-256")
            val content = requireBody(response)
            target.outputStream().use { out ->
                var size = 0L
                val chunk = ByteArray(65536)
                content.byteStream().use { stream ->
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        size += count
                        if (size > 96L * 1024 * 1024) throw AxisException("APK is too large.")
                        hash.update(chunk, 0, count)
                        out.write(chunk, 0, count)
                    }
                }
            }
            if (!hash.digest().joinToString("") { "%02x".format(it) }.equals(update.sha256, true)) {
                throw AxisException("APK checksum mismatch.")
            }
        }
    }

    suspend fun synthesize(text: String): ByteArray {
        val conn = connection()
        val request = conn.authorize(jsonRequest(conn.endpoint("voice/tts"), JSONObject().put("text", text.take(600)))).build()
        return conn.http.readResponse(request) {
            val source = requireBody(it).source()
            if (source.request(2L * 1024 * 1024 + 1)) throw AxisException("Audio is too large.")
            source.readByteArray()
        }
    }

    suspend fun listen(onEvent: (JSONObject) -> Unit) {
        val conn = connection()
        val streamHttp = conn.http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
        streamHttp.readResponse(conn.authorize(Request.Builder().url(conn.endpoint("push/events"))).build()) {
            val source = requireBody(it).source()
            while (!source.exhausted()) {
                val line = source.readUtf8LineStrict(8192)
                if (line.isNotBlank()) onEvent(JSONObject(line))
            }
        }
    }
}

internal fun InputStream.readBounded(limit: Int): ByteArray = readNBytesCompat(limit + 1).also {
    if (it.size > limit) throw AxisException("Selected file is too large.")
}

private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (count < 0) break
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
