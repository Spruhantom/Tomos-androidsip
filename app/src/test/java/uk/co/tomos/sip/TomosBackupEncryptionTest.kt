package uk.co.tomos.sip

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TomosBackupEncryptionTest {
    @Test fun encryptedBackupRoundTripsAndDoesNotExposeJson() {
        val plaintext = """{"schemaVersion":1,"createdAt":1,"contacts":[],"callHistory":[]}""".toByteArray()
        val encrypted = TomosBackupEncryption.encrypt(plaintext, "correct horse battery".toCharArray())
        assertTrue(TomosBackupEncryption.isEncrypted(encrypted))
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("schemaVersion"))
        assertArrayEquals(plaintext, TomosBackupEncryption.decrypt(encrypted, "correct horse battery".toCharArray()))
    }

    @Test fun rejectsWrongPassword() {
        val encrypted = TomosBackupEncryption.encrypt(byteArrayOf(1, 2, 3), "correct horse battery".toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            TomosBackupEncryption.decrypt(encrypted, "incorrect password".toCharArray())
        }
    }

    @Test fun rejectsTamperedCiphertext() {
        val encrypted = TomosBackupEncryption.encrypt(byteArrayOf(1, 2, 3), "correct horse battery".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            TomosBackupEncryption.decrypt(encrypted, "correct horse battery".toCharArray())
        }
    }

    @Test fun rejectsUnsupportedVersion() {
        val encrypted = TomosBackupEncryption.encrypt(byteArrayOf(1), "correct horse battery".toCharArray())
        encrypted[8] = 99
        assertThrows(IllegalArgumentException::class.java) {
            TomosBackupEncryption.decrypt(encrypted, "correct horse battery".toCharArray())
        }
    }

    @Test fun rejectsShortPasswords() {
        assertThrows(IllegalArgumentException::class.java) {
            TomosBackupEncryption.encrypt(byteArrayOf(1), "short".toCharArray())
        }
    }

    @Test fun detectsOnlyEncryptedHeader() {
        assertTrue(TomosBackupEncryption.isEncrypted(TomosBackupEncryption.encrypt(byteArrayOf(1), "correct horse battery".toCharArray())))
        assertFalse(TomosBackupEncryption.isEncrypted("not encrypted".toByteArray()))
    }
}
