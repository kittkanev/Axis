package io.github.kittkanev.axis

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class WorkspaceStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "axis-workspace.json"))
    private val vault = KeyVault()

    fun load(): Workspace {
        val encoded = try {
            file.readFully().toString(Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return Workspace()
            throw e
        }
        return decodeWorkspace(encoded)
    }

    fun save(workspace: Workspace) {
        val bytes = encodeWorkspace(workspace, portable = false)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    fun portable(workspace: Workspace): ByteArray = encodeWorkspace(workspace, portable = true)

    fun fromPortable(bytes: ByteArray, previous: Workspace): Workspace {
        val imported = decodeWorkspace(bytes.toString(Charsets.UTF_8))
        return imported.copy(profiles = imported.profiles.map { profile ->
            val existing = previous.profiles.firstOrNull { it.id == profile.id && it.baseUrl == profile.baseUrl }
            profile.copy(encryptedKey = existing?.encryptedKey.orEmpty())
        })
    }

    private fun decodeWorkspace(encoded: String): Workspace = try {
            val data = JSONObject(encoded)
            val profiles = data.getJSONArray("profiles").objects().map {
                Profile(
                    it.getString("id"), it.getString("name"), it.getString("baseUrl"),
                    it.getString("model"), it.optString("encryptedKey"), it.optJSONObject("voice").toModelVoice(),
                    it.optString("systemPrompt", DEFAULT_SYSTEM_PROMPT), it.optBoolean("concise", true),
                    it.optJSONArray("tools")?.let { saved ->
                        AxisTool.entries.filter { tool -> (0 until saved.length()).any { saved.optString(it) == tool.wire } }.toSet()
                    } ?: defaultAxisTools,
                    it.optBoolean("managed"),
                )
            }
            require(profiles.size <= MAX_PROFILES && profiles.map { it.id }.distinct().size == profiles.size)
            profiles.forEach { validateConnection(it.baseUrl, "validate", null) }
            val chats = data.optJSONObject("chats")
            val conversations = data.optJSONObject("conversations")
            val selections = data.optJSONObject("activeConversations")
            Workspace(
                profiles = profiles,
                activeId = data.optString("activeId").takeIf { id -> profiles.any { it.id == id } }
                    ?: profiles.firstOrNull()?.id,
                chats = profiles.associate { it.id to readMessages(chats?.optJSONArray(it.id)) },
                conversations = profiles.mapNotNull { profile ->
                    conversations?.optJSONArray(profile.id)?.let { items ->
                        require(items.length() <= MAX_CONVERSATIONS)
                        val threads = items.objects().map { item ->
                            Conversation(item.getString("id"), item.optString("title").take(80), readMessages(item.optJSONArray("messages")))
                        }
                        require(threads.all { it.id.isNotBlank() && it.id.length <= 64 } && threads.map { it.id }.distinct().size == threads.size)
                        profile.id to threads
                    }
                }.toMap(),
                activeConversations = profiles.mapNotNull { profile -> selections?.optString(profile.id)
                    ?.takeIf(String::isNotBlank)?.let { profile.id to it } }.toMap(),
            )
        } catch (_: JSONException) {
            throw AxisException("Не удалось прочитать локальные данные Axis.")
        } catch (_: IllegalArgumentException) {
            throw AxisException("Не удалось прочитать локальные данные Axis.")
        }
    private fun readMessages(values: JSONArray?): List<Message> = retainChat(values?.objects().orEmpty().map { message ->
        Message(Role.valueOf(message.getString("role")), message.getString("text"))
    })

    private fun writeMessages(messages: List<Message>) = JSONArray().apply {
        messages.forEach { put(JSONObject().put("role", it.role.name).put("text", it.text)) }
    }

    private fun encodeWorkspace(workspace: Workspace, portable: Boolean): ByteArray {
        val profiles = JSONArray()
        workspace.profiles.forEach { profile ->
            profiles.put(
                JSONObject().put("id", profile.id).put("name", profile.name)
                    .put("baseUrl", profile.baseUrl).put("model", profile.model)
                    .put("encryptedKey", if (portable) "" else profile.encryptedKey).put("voice", profile.voice.toJson())
                    .put("systemPrompt", profile.systemPrompt).put("concise", profile.concise)
                    .put("tools", JSONArray(profile.tools.map { it.wire })).put("managed", profile.managed),
            )
        }
        val threads = JSONObject()
        workspace.profiles.forEach { profile ->
            threads.put(profile.id, JSONArray().apply {
                workspace.conversationsFor(profile.id).forEach { item ->
                    put(JSONObject().put("id", item.id).put("title", item.title).put("messages", writeMessages(item.messages)))
                }
            })
        }
        return JSONObject().put("activeId", workspace.activeId ?: JSONObject.NULL)
            .put("profiles", profiles).put("conversations", threads)
            .put("activeConversations", JSONObject(workspace.activeConversations)).toString().toByteArray(Charsets.UTF_8)
    }

    fun encryptKey(id: String, baseUrl: String, key: String): String = try {
        vault.encrypt("$id\n$baseUrl", key)
    } catch (_: GeneralSecurityException) {
        throw AxisException("Не удалось защитить ключ в Android Keystore.")
    } catch (_: ProviderException) {
        throw AxisException("Не удалось защитить ключ в Android Keystore.")
    } catch (_: IOException) {
        throw AxisException("Не удалось открыть Android Keystore.")
    }

    fun readKey(profile: Profile): String = try {
        vault.decrypt("${profile.id}\n${profile.baseUrl}", profile.encryptedKey)
    } catch (_: GeneralSecurityException) {
        throw AxisException("Ключ недоступен. Введите его заново в профиле.")
    } catch (_: IllegalArgumentException) {
        throw AxisException("Ключ недоступен. Введите его заново в профиле.")
    } catch (_: ProviderException) {
        throw AxisException("Ключ недоступен. Введите его заново в профиле.")
    } catch (_: IOException) {
        throw AxisException("Не удалось открыть Android Keystore.")
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
}

internal class KeyVault(private val alias: String = "axis-provider-keys-v1") {

    fun encrypt(scope: String, value: String): String {
        val keystore = openStore()
        val key = keystore.getKey(alias, null) as? SecretKey ?: createKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) + "." + encode(ciphertext)
    }

    fun decrypt(scope: String, value: String): String {
        val key = openStore().getKey(alias, null) as? SecretKey
            ?: throw GeneralSecurityException("Missing key")
        val pieces = value.split('.', limit = 2)
        require(pieces.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(pieces[0])))
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(decode(pieces[1])).toString(Charsets.UTF_8)
    }

    private fun openStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun createKey(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
    ).apply {
        init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
    }.generateKey()

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}

internal fun JSONObject?.toModelVoice(): ModelVoice {
    if (this == null) return ModelVoice()
    val language = optString("language").takeIf { tag -> voiceLanguages.any { it.first == tag } } ?: "ru-RU"
    return ModelVoice(
        language = language,
        name = opt("name") as? String,
        rate = optDouble("rate", 1.0).takeIf { it.isFinite() }?.toFloat()?.coerceIn(0.6f, 1.5f) ?: 1f,
        pitch = optDouble("pitch", 1.0).takeIf { it.isFinite() }?.toFloat()?.coerceIn(0.7f, 1.4f) ?: 1f,
        neuralVoice = optString("neuralVoice").takeIf { it in geminiVoices },
        speechStyle = SpeechStyle.entries.firstOrNull { it.name == optString("speechStyle") } ?: SpeechStyle.NATURAL,
        piper = optBoolean("piper"),
    )
}

internal fun ModelVoice.toJson(): JSONObject = JSONObject().put("language", language)
    .put("name", name ?: JSONObject.NULL).put("rate", rate.toDouble()).put("pitch", pitch.toDouble())
    .put("neuralVoice", neuralVoice ?: JSONObject.NULL).put("speechStyle", speechStyle.name).put("piper", piper)
