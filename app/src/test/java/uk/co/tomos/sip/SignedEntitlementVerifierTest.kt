package uk.co.tomos.sip

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedEntitlementVerifierTest {
    private val privateKey = Ed25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
    private val publicKey = privateKey.generatePublicKey()
    private val publicPem = "-----BEGIN PUBLIC KEY-----\n" +
        Base64.getEncoder().encodeToString(
            byteArrayOf(0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00) +
                publicKey.encoded
        ) + "\n-----END PUBLIC KEY-----"
    private val verifier = SignedEntitlementVerifier(publicPem, "entitlements-test-1")
    private val now = Instant.parse("2026-11-09T14:00:00Z").toEpochMilli()

    private fun signedEnvelope(
        accountId: String = "account-123",
        status: String = "active",
        plan: String = "monthly",
        issuedAt: String = "2026-11-09T13:00:00Z",
        expiresAt: String = "2026-11-09T15:00:00Z",
        validFrom: String = "2026-11-01T00:00:00Z",
        validUntil: String = "2026-12-01T00:00:00Z",
        graceUntil: String? = null
    ): JSONObject {
        val payload = JSONObject()
            .put("schema_version", 1)
            .put("product_id", "tomos-sip-premium")
            .put("account_id", accountId)
            .put("status", status)
            .put("plan", plan)
            .put("source", "google_play")
            .put("valid_from", validFrom)
            .put("valid_until", validUntil)
            .put("grace_until", graceUntil)
            .put("issued_at", issuedAt)
            .put("expires_at", expiresAt)
            .put("features", org.json.JSONArray(listOf(
                "multiple_sip_accounts",
                "advanced_codec_controls",
                "encrypted_backup",
                "premium_plugin_capabilities"
            )))
        val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(bytes, 0, bytes.size)
        return JSONObject()
            .put("signature_algorithm", "Ed25519")
            .put("key_id", "entitlements-test-1")
            .put("signed_payload", Base64.getEncoder().encodeToString(bytes))
            .put("signature", Base64.getEncoder().encodeToString(signer.generateSignature()))
    }

    @Test
    fun verifiesMonthlySubscriptionForMatchingAccount() {
        val snapshot = verifier.verify(signedEnvelope(), "account-123", now)
        assertNotNull(snapshot)
        assertTrue(snapshot!!.grants(PremiumFeature.MULTIPLE_SIP_ACCOUNTS, now))
        assertEquals(SubscriptionPlan.MONTHLY, snapshot.plan)
    }

    @Test
    fun rejectsAnotherAccountsEntitlement() {
        assertNull(verifier.verify(signedEnvelope(accountId = "other-account"), "account-123", now))
    }

    @Test
    fun rejectsTamperedSignedPayload() {
        val envelope = signedEnvelope()
        val changed = JSONObject(String(Base64.getDecoder().decode(envelope.getString("signed_payload"))))
            .put("status", "refunded")
        envelope.put("signed_payload", Base64.getEncoder().encodeToString(
            changed.toString().toByteArray(StandardCharsets.UTF_8)
        ))
        assertNull(verifier.verify(envelope, "account-123", now))
    }

    @Test
    fun rejectsWrongKeyIdAndExpiredSnapshot() {
        val wrongKey = signedEnvelope().put("key_id", "unknown-key")
        assertNull(verifier.verify(wrongKey, "account-123", now))
        assertNull(verifier.verify(
            signedEnvelope(expiresAt = "2026-11-09T13:59:00Z"),
            "account-123",
            now
        ))
    }

    @Test
    fun rejectsMissingRequiredFeaturesAndUnknownPlan() {
        val incomplete = signedEnvelope()
        val payload = JSONObject(String(Base64.getDecoder().decode(incomplete.getString("signed_payload"))))
            .put("features", org.json.JSONArray(listOf("multiple_sip_accounts")))
        val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        val signer = Ed25519Signer().apply {
            init(true, privateKey)
            update(bytes, 0, bytes.size)
        }
        incomplete.put("signed_payload", Base64.getEncoder().encodeToString(bytes))
        incomplete.put("signature", Base64.getEncoder().encodeToString(signer.generateSignature()))
        assertNull(verifier.verify(incomplete, "account-123", now))
    }

    @Test
    fun gracePeriodOnlyGrantsAfterPaidThroughUntilGraceEnd() {
        val snapshot = verifier.verify(
            signedEnvelope(
                status = "grace_period",
                expiresAt = "2026-11-09T15:00:00Z",
                validUntil = "2026-11-09T13:30:00Z",
                graceUntil = "2026-11-09T16:00:00Z"
            ),
            "account-123",
            now
        )
        assertNotNull(snapshot)
        assertFalse(snapshot!!.grants(PremiumFeature.ENCRYPTED_BACKUP, now))
        assertTrue(snapshot.grants(
            PremiumFeature.ENCRYPTED_BACKUP,
            Instant.parse("2026-11-09T14:30:00Z").toEpochMilli()
        ))
        assertFalse(snapshot.grants(
            PremiumFeature.ENCRYPTED_BACKUP,
            Instant.parse("2026-11-09T16:00:00Z").toEpochMilli()
        ))
    }
}
