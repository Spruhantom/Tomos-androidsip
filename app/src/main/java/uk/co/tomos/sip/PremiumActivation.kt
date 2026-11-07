package uk.co.tomos.sip

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

private const val PREMIUM_PRODUCT = "tomos-sip"
private val PREMIUM_FEATURES = setOf(
    "multiple_sip_accounts",
    "advanced_codec_controls",
    "encrypted_backup"
)

/** Verifies the exact canonical payload bytes signed by the offline generator. */
class PremiumLicenceVerifier(private val publicKeyPem: String) {
    fun verify(document: JSONObject, activationCode: String): Boolean = runCatching {
        if (publicKeyPem.isBlank()) return false
        if (document.optString("signature_algorithm") != "Ed25519") return false
        val signedPayload = Base64.decode(document.getString("signed_payload"), Base64.DEFAULT)
        val payload = JSONObject(String(signedPayload, StandardCharsets.UTF_8))
        if (payload.toString().isBlank()) return false
        val mirroredPayload = document.getJSONObject("payload")
        if (mirroredPayload.getString("licence_id") != payload.getString("licence_id")) return false
        if (payload.getInt("schema") != 1 || payload.getString("product") != PREMIUM_PRODUCT) return false
        if (payload.getString("licence_type") != "lifetime") return false
        val features = payload.getJSONArray("features")
        val received = (0 until features.length()).map { features.getString(it) }.toSet()
        if (received != PREMIUM_FEATURES) return false
        val normalizedCode = activationCode.trim().uppercase().replace(" ", "")
        val codeDigest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedCode.toByteArray(StandardCharsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        if (codeDigest != payload.getString("activation_code_sha256")) return false

        val pemBody = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot { it.isWhitespace() }
        val encoded = Base64.decode(pemBody, Base64.DEFAULT)
        // SubjectPublicKeyInfo for Ed25519 ends with the 32-byte raw public key.
        if (encoded.size < 32) return false
        val rawKey = encoded.copyOfRange(encoded.size - 32, encoded.size)
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(rawKey, 0))
        signer.update(signedPayload, 0, signedPayload.size)
        signer.verifySignature(Base64.decode(document.getString("signature"), Base64.DEFAULT))
    }.getOrDefault(false)
}

/** Redeems an activation code over HTTPS and verifies the returned signed licence. */
class PremiumActivationClient(
    private val context: Context,
    private val endpoint: String,
    private val verifier: PremiumLicenceVerifier
) {
    suspend fun redeem(code: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            require(endpoint.startsWith("https://")) { "Premium activation requires an HTTPS endpoint." }
            require(code.trim().isNotEmpty()) { "Enter your Premium activation code." }
            val deviceId = installationId()
            val connection = (URL(endpoint.trimEnd('/') + "/v1/redeem").openConnection() as HttpURLConnection)
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                val body = JSONObject()
                    .put("activation_code", code.trim())
                    .put("device_id", deviceId)
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body.toString()) }
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (responseCode !in 200..299) {
                    val message = runCatching { JSONObject(responseText).optString("detail") }.getOrNull()
                    throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Activation failed (HTTP $responseCode).")
                }
                val licence = JSONObject(responseText).getJSONObject("licence")
                if (!verifier.verify(licence, code)) {
                    throw SecurityException("The returned Premium licence could not be verified.")
                }
                SecurePremiumLicenceStore(context).save(licence.toString())
                licence
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun installationId(): String {
        val prefs = context.getSharedPreferences("tomos_premium_installation", Context.MODE_PRIVATE)
        return prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("installation_id", it).apply()
        }
    }
}

/** Stores the signed licence encrypted with an Android Keystore AES-GCM key. */
class SecurePremiumLicenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("tomos_premium_licence", Context.MODE_PRIVATE)
    private val alias = "tomos_premium_licence_aes"

    fun save(value: String) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val packed = cipher.iv + ciphertext
        check(prefs.edit().putString("licence", Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "Unable to save the Premium licence securely."
        }
    }

    fun load(): String? = runCatching {
        val packed = Base64.decode(prefs.getString("licence", null) ?: return null, Base64.DEFAULT)
        require(packed.size > 12)
        val iv = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): java.security.Key {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.getKey(alias, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
