package io.github.kittkanev.axis

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val WORKSPACE_FILE_LIMIT = 8 * 1024 * 1024
internal const val WORKSPACE_SYNC_LIMIT = 1024 * 1024

internal object WorkspaceCipher {
    private val format = byteArrayOf(0x41, 0x58, 0x57, 0x53, 1) // AXWS v1
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val HEADER_SIZE = 5 + SALT_SIZE + NONCE_SIZE
    private val random = SecureRandom()

    fun seal(bytes: ByteArray, passphrase: String, limit: Int = WORKSPACE_FILE_LIMIT): ByteArray {
        if (bytes.size > limit - HEADER_SIZE - TAG_SIZE) throw AxisException("Workspace exceeds the size limit.")
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val header = format + salt + nonce
        return header + crypt(Cipher.ENCRYPT_MODE, bytes, passphrase, salt, nonce, header)
    }

    fun open(bytes: ByteArray, passphrase: String, limit: Int = WORKSPACE_FILE_LIMIT): ByteArray {
        if (bytes.size !in (HEADER_SIZE + TAG_SIZE)..limit || !bytes.copyOfRange(0, format.size).contentEquals(format)) {
            throw AxisException("Unrecognized encrypted workspace format.")
        }
        val salt = bytes.copyOfRange(format.size, format.size + SALT_SIZE)
        val nonce = bytes.copyOfRange(format.size + SALT_SIZE, HEADER_SIZE)
        return crypt(Cipher.DECRYPT_MODE, bytes.copyOfRange(HEADER_SIZE, bytes.size), passphrase, salt, nonce, bytes.copyOfRange(0, HEADER_SIZE))
    }

    private fun crypt(mode: Int, bytes: ByteArray, passphrase: String, salt: ByteArray, nonce: ByteArray, header: ByteArray): ByteArray {
        if (passphrase.length !in 12..256) throw AxisException("Use a workspace passphrase of 12 to 256 characters.")
        val secret = passphrase.toCharArray()
        val key = ByteArray(32)
        try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(32 * 1024)
                .withIterations(3).withParallelism(1).withSalt(salt).build()
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(secret, key)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            return cipher.doFinal(bytes)
        } catch (_: GeneralSecurityException) {
            throw AxisException("The passphrase is incorrect or the workspace was changed.")
        } finally {
            secret.fill('\u0000')
            key.fill(0)
        }
    }
}
