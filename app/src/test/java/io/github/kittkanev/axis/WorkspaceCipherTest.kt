package io.github.kittkanev.axis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceCipherTest {
    @Test
    fun roundTripUsesUniqueSaltAndNonceAndAuthenticatesHeaderAndContent() {
        val data = "Переносимый диалог Axis".toByteArray()
        val phrase = "axis test passphrase for encryption"
        val first = WorkspaceCipher.seal(data, phrase)
        val second = WorkspaceCipher.seal(data, phrase)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(data, WorkspaceCipher.open(first, phrase))
        assertThrows(AxisException::class.java) { WorkspaceCipher.open(first, "another test passphrase") }
        val modified = first.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(AxisException::class.java) { WorkspaceCipher.open(modified, phrase) }
        val modifiedHeader = first.copyOf().apply { this[7] = (this[7].toInt() xor 1).toByte() }
        assertThrows(AxisException::class.java) { WorkspaceCipher.open(modifiedHeader, phrase) }
    }
}
