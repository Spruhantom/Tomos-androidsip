package uk.co.tomos.sip

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Stores SIP passwords as AES-256-GCM ciphertext using non-exportable Android
 * Keystore keys. Only ciphertext and the random GCM IV are written to preferences.
 */
class SipCredentialStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("tomos_sip_credentials", Context.MODE_PRIVATE)

    @Synchronized
    fun save(accountId: String, password: String) {
        require(accountId.isNotBlank()) { "Account ID cannot be blank." }
        require(password.isNotEmpty()) { "SIP password cannot be empty." }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(aliasFor(accountId)))
            val encrypted = cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8))
            val payload = ByteBuffer.allocate(IV_SIZE + encrypted.size)
                .put(cipher.iv)
                .put(encrypted)
                .array()
            preferences.edit()
                .putString(storageKey(accountId), Base64.encodeToString(payload, Base64.NO_WRAP))
                .apply()
        } catch (error: Exception) {
            throw IllegalStateException("Unable to protect SIP credentials with Android Keystore.", error)
        }
    }

    @Synchronized
    fun read(accountId: String): String? {
        require(accountId.isNotBlank()) { "Account ID cannot be blank." }
        val encoded = preferences.getString(storageKey(accountId), null) ?: return null
        try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_SIZE) { "Stored SIP credential is invalid." }
            val buffer = ByteBuffer.wrap(payload)
            val iv = ByteArray(IV_SIZE).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(aliasFor(accountId)), GCMParameterSpec(TAG_BITS, iv))
            return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException(
                "Unable to decrypt SIP credentials. The account may need its password entered again.",
                error
            )
        }
    }

    @Synchronized
    fun delete(accountId: String) {
        require(accountId.isNotBlank()) { "Account ID cannot be blank." }
        preferences.edit().remove(storageKey(accountId)).apply()
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(aliasFor(accountId))
        } catch (error: Exception) {
            throw IllegalStateException("Unable to remove the stored SIP credential key.", error)
        }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun aliasFor(accountId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(accountId.toByteArray(StandardCharsets.UTF_8))
        val suffix = digest.take(16).joinToString("") { "%02x".format(it) }
        return "tomos_sip_account_$suffix"
    }

    private fun storageKey(accountId: String) = "credential_$accountId"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
