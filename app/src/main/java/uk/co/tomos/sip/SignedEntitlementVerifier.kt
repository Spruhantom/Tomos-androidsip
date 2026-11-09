package uk.co.tomos.sip

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject

/**
 * Verifies the exact payload bytes signed by the TomOS entitlement service.
 *
 * The backend signs UTF-8 JSON bytes and transports those bytes as Base64 in
 * `signed_payload`. The verifier never reserializes JSON before signature
 * verification, avoiding ambiguity in JSON key ordering or whitespace.
 */
class SignedEntitlementVerifier(
    private val publicKeyPem: String,
    private val expectedKeyId: String
) {
    fun verify(
        envelope: JSONObject,
        expectedAccountId: String,
        nowEpochMillis: Long
    ): EntitlementSnapshot? = runCatching {
        require(expectedAccountId.isNotBlank())
        require(envelope.optString("signature_algorithm") == "Ed25519")
        require(envelope.optString("key_id") == expectedKeyId)
        val signedPayload = Base64.getDecoder().decode(envelope.getString("signed_payload"))
        require(signedPayload.isNotEmpty() && signedPayload.size <= MAX_PAYLOAD_BYTES)
        val signature = Base64.getDecoder().decode(envelope.getString("signature"))
        require(signature.size == SIGNATURE_BYTES)
        require(verifySignature(signedPayload, signature))

        val payload = JSONObject(String(signedPayload, StandardCharsets.UTF_8))
        require(payload.getInt("schema_version") == 1)
        require(payload.getString("product_id") == PRODUCT_ID)
        require(payload.getString("account_id") == expectedAccountId)

        val issuedAt = parseUtc(payload.getString("issued_at"))
        val snapshotExpiresAt = parseUtc(payload.getString("expires_at"))
        require(snapshotExpiresAt > issuedAt)
        require(nowEpochMillis >= issuedAt - CLOCK_SKEW_MILLIS)
        require(nowEpochMillis < snapshotExpiresAt)

        val status = when (payload.getString("status")) {
            "free" -> EntitlementStatus.FREE
            "active" -> EntitlementStatus.PREMIUM_ACTIVE
            "grace_period" -> EntitlementStatus.GRACE_PERIOD
            "expired" -> EntitlementStatus.EXPIRED
            "refunded" -> EntitlementStatus.REFUNDED
            "revoked" -> EntitlementStatus.REVOKED
            else -> throw IllegalArgumentException("Unsupported entitlement status.")
        }
        val plan = when (payload.optString("plan").takeIf { it.isNotBlank() && it != "null" }) {
            null -> null
            "monthly" -> SubscriptionPlan.MONTHLY
            "yearly" -> SubscriptionPlan.YEARLY
            else -> throw IllegalArgumentException("Unsupported subscription plan.")
        }
        val source = when (payload.optString("source")) {
            "google_play" -> EntitlementSource.GOOGLE_PLAY
            "github_direct" -> EntitlementSource.GITHUB_DIRECT
            else -> throw IllegalArgumentException("Unsupported purchase source.")
        }

        val features = payload.getJSONArray("features")
        val featureIds = (0 until features.length()).map { features.getString(it) }.toSet()
        val requiredFeatures = setOf(
            "multiple_sip_accounts",
            "advanced_codec_controls",
            "encrypted_backup",
            "premium_plugin_capabilities"
        )
        require(featureIds == requiredFeatures)

        val validFrom = parseUtc(payload.getString("valid_from"))
        val validUntil = payload.optString("valid_until")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let(::parseUtc)
        val graceUntil = payload.optString("grace_until")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let(::parseUtc)

        when (status) {
            EntitlementStatus.PREMIUM_ACTIVE -> {
                require(plan != null && validUntil != null)
                require(validUntil > validFrom)
            }
            EntitlementStatus.GRACE_PERIOD -> {
                require(plan != null && validUntil != null && graceUntil != null)
                require(graceUntil > validUntil)
            }
            else -> Unit
        }

        EntitlementSnapshot(
            status = status,
            plan = plan,
            source = source,
            accountId = expectedAccountId,
            validFromEpochMillis = validFrom,
            expiresAtEpochMillis = validUntil,
            graceUntilEpochMillis = graceUntil,
            signatureVerified = true
        )
    }.getOrNull()

    private fun verifySignature(message: ByteArray, signature: ByteArray): Boolean {
        val pem = publicKeyPem.trim()
        require(pem.startsWith("-----BEGIN PUBLIC KEY-----"))
        require(pem.endsWith("-----END PUBLIC KEY-----"))
        val body = pem.removePrefix("-----BEGIN PUBLIC KEY-----")
            .removeSuffix("-----END PUBLIC KEY-----")
            .filterNot(Char::isWhitespace)
        val encoded = Base64.getDecoder().decode(body)
        require(encoded.size == ED25519_SPKI_PREFIX.size + 32)
        require(encoded.copyOfRange(0, ED25519_SPKI_PREFIX.size)
            .contentEquals(ED25519_SPKI_PREFIX))
        val publicKey = Ed25519PublicKeyParameters(
            encoded.copyOfRange(ED25519_SPKI_PREFIX.size, encoded.size), 0
        )
        return Ed25519Signer().run {
            init(false, publicKey)
            update(message, 0, message.size)
            verifySignature(signature)
        }
    }

    private fun parseUtc(value: String): Long {
        require(value.endsWith("Z")) { "Entitlement timestamps must be UTC." }
        return Instant.parse(value).toEpochMilli()
    }

    private companion object {
        const val PRODUCT_ID = "tomos-sip-premium"
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        const val SIGNATURE_BYTES = 64
        const val CLOCK_SKEW_MILLIS = 60_000L
        val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
        )
    }
}

/** The raw signed envelope is retained so every cached read can be re-verified. */
data class SignedEntitlementEnvelope(val json: String)
