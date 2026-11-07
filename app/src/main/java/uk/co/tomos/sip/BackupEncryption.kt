package uk.co.tomos.sip

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable password-based backup encryption. The salt and nonce are stored in
 * the file; the password is never stored. AES-GCM authenticates the header and
 * ciphertext, so a wrong password or modified file is rejected.
 */
internal object TomosBackupEncryption {
    private val MAGIC = byteArrayOf(0x54, 0x4f, 0x4d, 0x4f, 0x53, 0x45, 0x4e, 0x43) // TOMOSENC
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 210_000
    private const val HEADER_BYTES = 8 + 1 + SALT_BYTES + NONCE_BYTES
    const val MAX_ENCRYPTED_BYTES = TomosBackupCodec.MAX_BACKUP_BYTES + 1024

    fun isEncrypted(data: ByteArray): Boolean =
        data.size >= MAGIC.size && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 8) { "Use a backup password with at least 8 characters." }
        require(plaintext.size <= TomosBackupCodec.MAX_BACKUP_BYTES) { "Backup file is larger than 5 MB." }
        val salt = ByteArray(SALT_BYTES)
        val nonce = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(salt)
        SecureRandom().nextBytes(nonce)
        val header = MAGIC + byteArrayOf(VERSION) + salt + nonce
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        val encrypted = cipher.doFinal(plaintext)
        return header + encrypted
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        require(data.size in (HEADER_BYTES + 16)..MAX_ENCRYPTED_BYTES) { "Encrypted backup is incomplete or too large." }
        require(isEncrypted(data)) { "This is not an encrypted TomOS backup." }
        require(data[8] == VERSION) { "This encrypted backup version is not supported." }
        val header = data.copyOfRange(0, HEADER_BYTES)
        val salt = data.copyOfRange(9, 9 + SALT_BYTES)
        val nonce = data.copyOfRange(9 + SALT_BYTES, HEADER_BYTES)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            return cipher.doFinal(data.copyOfRange(HEADER_BYTES, data.size)).also {
                require(it.size <= TomosBackupCodec.MAX_BACKUP_BYTES) { "Decrypted backup is larger than 5 MB." }
            }
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Incorrect password or encrypted backup has been changed.")
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
