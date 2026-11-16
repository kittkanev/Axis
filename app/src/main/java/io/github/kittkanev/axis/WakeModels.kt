package io.github.kittkanev.axis

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal object WakeModels {
    private data class Source(val name: String, val sha256: String)

    private val models = mapOf(
        "ru-RU" to Source("vosk-model-small-ru-0.22", "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"),
        "en-US" to Source("vosk-model-small-en-us-0.15", "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"),
    )
    private const val MAX_DOWNLOAD = 48 * 1024 * 1024L
    private const val MAX_EXTRACTED = 96 * 1024 * 1024L
    private val http = OkHttpClient.Builder()
        .followSslRedirects(false)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    fun installed(context: Context, language: String): File? {
        val source = models[language] ?: return null
        val directory = File(context.filesDir, "wake-models/${source.name}")
        return directory.takeIf { File(it, "am/final.mdl").isFile && File(it, "conf/model.conf").isFile }
    }

    suspend fun install(context: Context, language: String) = withContext(Dispatchers.IO) {
        if (installed(context, language) != null) return@withContext
        val source = models[language] ?: throw IOException("Unsupported wake model")
        val root = File(context.filesDir, "wake-models")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Could not create wake model directory")
        val stage = Files.createTempDirectory(root.toPath(), ".install-").toFile()
        try {
            val archive = File(stage, "model.zip")
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            val request = Request.Builder().url("https://alphacephei.com/vosk/models/${source.name}.zip").build()
            http.newCall(request).execute().use { reply ->
                val body = reply.body ?: throw IOException("Empty wake model response")
                if (!reply.isSuccessful || body.contentLength() > MAX_DOWNLOAD) throw IOException("Invalid wake model download")
                var downloaded = 0L
                body.byteStream().use { input ->
                    archive.outputStream().use { output ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val size = input.read(buffer)
                            if (size == -1) break
                            downloaded += size
                            if (downloaded > MAX_DOWNLOAD) throw IOException("Wake model download is too large")
                            hash.update(buffer, 0, size)
                            output.write(buffer, 0, size)
                        }
                    }
                }
            }
            if (hash.digest().joinToString("") { "%02x".format(it) } != source.sha256) {
                throw IOException("Wake model checksum is invalid")
            }
            val model = File(stage, source.name)
            val prefix = "${model.canonicalPath}${File.separator}"
            var extracted = 0L
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    val file = File(stage, entry.name).canonicalFile
                    if (entry.isDirectory && file == model.canonicalFile) continue
                    if (!file.path.startsWith(prefix)) throw IOException("Invalid wake model archive entry")
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val size = zip.read(buffer)
                                if (size == -1) break
                                extracted += size
                                if (extracted > MAX_EXTRACTED) throw IOException("Wake model archive is too large")
                                output.write(buffer, 0, size)
                            }
                        }
                    }
                }
            }
            coroutineContext.ensureActive()
            if (!model.renameTo(File(root, source.name)) && installed(context, language) == null) {
                throw IOException("Could not install wake model")
            }
        } finally {
            stage.deleteRecursively()
        }
    }
}
