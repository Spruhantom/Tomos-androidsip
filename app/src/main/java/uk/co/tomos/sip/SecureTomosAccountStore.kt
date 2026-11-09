package uk.co.tomos.sip

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Short-lived TomOS account credentials. Never log or expose token values. */
data class TomosAccountSession(
    val accountId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtEpochMillis: Long
) {
    init {
        require(accountId.isNotBlank())
        require(accessToken.isNotBlank())
        require(refreshToken.isNotBlank())
        require(accessTokenExpiresAtEpochMillis > 0)
    }

    fun isAccessTokenUsable(nowEpochMillis: Long, safetyWindowMillis: Long = 60_000L): Boolean =
        nowEpochMillis + safetyWindowMillis < accessTokenExpiresAtEpochMillis
}

/** Storage boundary for account credentials and the signed entitlement cache. */
interface TomosAccountStore {
    fun saveSession(session: TomosAccountSession)
    fun loadSession(): TomosAccountSession?
    fun saveEntitlementEnvelope(envelope: SignedEntitlementEnvelope)
    fun loadEntitlementEnvelope(): SignedEntitlementEnvelope?
    fun clearSession()
}

/**
 * Android Keystore-backed encrypted storage for the account session and signed
 * entitlement envelope. AES-GCM authentication failures return no cached data.
 */
class SecureTomosAccountStore(context: Context) : TomosAccountStore {
    private val preferences = context.getSharedPreferences("tomos_account_secure_store", Context.MODE_PRIVATE)
    private val keyAlias = "tomos_account_store_aes_gcm"

    @Synchronized
    override fun saveSession(session: TomosAccountSession) {
        val json = JSONObject()
            .put("account_id", session.accountId)
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("access_token_expires_at", session.accessTokenExpiresAtEpochMillis)
        saveEncrypted(KEY_SESSION, json.toString())
    }

    @Synchronized
    override fun loadSession(): TomosAccountSession? = runCatching {
        val json = JSONObject(loadEncrypted(KEY_SESSION) ?: return null)
        TomosAccountSession(
            accountId = json.getString("account_id"),
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            accessTokenExpiresAtEpochMillis = json.getLong("access_token_expires_at")
        )
    }.getOrNull()

    @Synchronized
    override fun saveEntitlementEnvelope(envelope: SignedEntitlementEnvelope) {
        require(envelope.json.toByteArray(StandardCharsets.UTF_8).size <= MAX_ENVELOPE_BYTES)
        saveEncrypted(KEY_ENTITLEMENT, envelope.json)
    }

    @Synchronized
    override fun loadEntitlementEnvelope(): SignedEntitlementEnvelope? =
        loadEncrypted(KEY_ENTITLEMENT)?.let(::SignedEntitlementEnvelope)

    @Synchronized
    override fun clearSession() {
        check(preferences.edit().remove(KEY_SESSION).remove(KEY_ENTITLEMENT).commit()) {
            "Unable to clear TomOS account data."
        }
    }

    private fun saveEncrypted(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val packed = byteArrayOf(iv.size.toByte()) + iv + encrypted
        check(preferences.edit().putString(key, Base64.getEncoder().encodeToString(packed)).commit()) {
            "Unable to securely save TomOS account data."
        }
    }

    private fun loadEncrypted(key: String): String? = runCatching {
        val stored = preferences.getString(key, null) ?: return null
        val packed = Base64.getDecoder().decode(stored)
        require(packed.size > 13)
        val ivSize = packed[0].toInt() and 0xff
        require(ivSize == GCM_IV_BYTES && packed.size > 1 + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, packed.copyOfRange(1, 1 + ivSize))
        )
        String(cipher.doFinal(packed.copyOfRange(1 + ivSize, packed.size)), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): java.security.Key {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.getKey(keyAlias, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_SESSION = "account_session"
        const val KEY_ENTITLEMENT = "signed_entitlement"
        const val MAX_ENVELOPE_BYTES = 64 * 1024
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
