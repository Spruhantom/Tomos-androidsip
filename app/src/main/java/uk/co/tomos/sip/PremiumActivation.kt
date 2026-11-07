package uk.co.tomos.sip

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
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
private val PREMIUM_FEATURES = listOf(
    "multiple_sip_accounts",
    "advanced_codec_controls",
    "encrypted_backup"
)

/** Compare JSON values recursively without relying on platform-specific JSONObject helpers. */
private fun jsonEquivalent(left: Any?, right: Any?): Boolean {
    if (left is JSONObject && right is JSONObject) {
        val leftKeys = left.keys().asSequence().toSet()
        val rightKeys = right.keys().asSequence().toSet()
        return leftKeys == rightKeys && leftKeys.all { key ->
            jsonEquivalent(left.opt(key), right.opt(key))
        }
    }
    if (left is JSONArray && right is JSONArray) {
        if (left.length() != right.length()) return false
        return (0 until left.length()).all { index ->
            jsonEquivalent(left.opt(index), right.opt(index))
        }
    }
    if (left is Number && right is Number) return left.toString() == right.toString()
    return left == right
}

/** Verifies the exact canonical payload bytes signed by the offline generator. */
class PremiumLicenceVerifier(private val publicKeyPem: String) {
    fun verify(document: JSONObject, activationCode: String): Boolean = runCatching {
        if (publicKeyPem.isBlank()) return false
        if (document.optString("signature_algorithm") != "Ed25519") return false
        val signedPayload = Base64.getDecoder().decode(document.getString("signed_payload"))
        val payload = JSONObject(String(signedPayload, StandardCharsets.UTF_8))
        if (payload.toString().isBlank()) return false
        val mirroredPayload = document.getJSONObject("payload")
        if (!jsonEquivalent(mirroredPayload, payload)) return false
        if (payload.getInt("schema") != 1 || payload.getString("product") != PREMIUM_PRODUCT) return false
        if (payload.getString("licence_type") != "lifetime") return false
        UUID.fromString(payload.getString("licence_id"))
        require(payload.getString("activation_code_sha256").matches(Regex("[0-9a-f]{64}")))
        require(payload.getString("issued_at").endsWith("Z"))
        val features = payload.getJSONArray("features")
        val received = (0 until features.length()).map { features.getString(it) }
        if (received != PREMIUM_FEATURES) return false
        val normalizedCode = activationCode.filterNot { it.isWhitespace() }.uppercase()
        require(normalizedCode.matches(Regex("TOMOS-PREM-(?:[A-Z2-7]{4}-){3}[A-Z2-7]{4}")))
        val codeDigest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedCode.toByteArray(StandardCharsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        if (codeDigest != payload.getString("activation_code_sha256")) return false

        val pemBody = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot { it.isWhitespace() }
        val encoded = Base64.getDecoder().decode(pemBody)
        // Require the exact Ed25519 SubjectPublicKeyInfo encoding, not merely
        // an arbitrary DER value whose final 32 bytes happen to look like a key.
        val spkiPrefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        if (encoded.size != spkiPrefix.size + 32) return false
        if (!encoded.copyOfRange(0, spkiPrefix.size).contentEquals(spkiPrefix)) return false
        val rawKey = encoded.copyOfRange(spkiPrefix.size, encoded.size)
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(rawKey, 0))
        signer.update(signedPayload, 0, signedPayload.size)
        signer.verifySignature(Base64.getDecoder().decode(document.getString("signature")))
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
        check(prefs.edit().putString("licence", Base64.getEncoder().encodeToString(packed)).commit()) {
            "Unable to save the Premium licence securely."
        }
    }

    fun load(): String? = runCatching {
        val packed = Base64.getDecoder().decode(prefs.getString("licence", null) ?: return null)
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
