package uk.co.tomos.sip

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class PremiumLicenceVerifierTest {
    @Test fun missingPublicKeyRejectsLicence() {
        assertFalse(PremiumLicenceVerifier("").verify(JSONObject(), "TOMOS-PREM-AAAA-BBBB-CCCC-DDDD"))
    }

    @Test fun malformedLicenceIsRejected() {
        val verifier = PremiumLicenceVerifier("-----BEGIN PUBLIC KEY-----\nAA==\n-----END PUBLIC KEY-----")
        assertFalse(verifier.verify(JSONObject(), "TOMOS-PREM-AAAA-BBBB-CCCC-DDDD"))
    }

    @Test fun validSignedLifetimeLicenceIsAccepted() {
        val code = "TOMOS-PREM-ABCD-EFGH-IJKL-MNOP"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
        val canonical = """{"activation_code_sha256":"$hash","customer_reference":"test","features":["multiple_sip_accounts","advanced_codec_controls","encrypted_backup"],"issued_at":"2026-11-07T00:00:00Z","licence_id":"00000000-0000-0000-0000-000000000001","licence_type":"lifetime","product":"tomos-sip","schema":1}"""
        val payloadBytes = canonical.toByteArray(Charsets.UTF_8)
        val privateKey = Ed25519PrivateKeyParameters(java.security.SecureRandom())
        val signer = Ed25519Signer().apply {
            init(true, privateKey)
            update(payloadBytes, 0, payloadBytes.size)
        }
        val signature = signer.generateSignature()
        val spkiPrefix = byteArrayOf(0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00)
        val publicPem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.encodeToString(spkiPrefix + privateKey.generatePublicKey().encoded, Base64.NO_WRAP) +
            "\n-----END PUBLIC KEY-----"
        val document = JSONObject()
            .put("signature_algorithm", "Ed25519")
            .put("signed_payload", Base64.encodeToString(payloadBytes, Base64.NO_WRAP))
            .put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
            .put("payload", JSONObject(canonical))
        assertTrue(PremiumLicenceVerifier(publicPem).verify(document, code))
    }
}
