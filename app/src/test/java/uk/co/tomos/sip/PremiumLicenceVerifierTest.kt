package uk.co.tomos.sip

import java.util.Base64
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
            Base64.getEncoder().encodeToString(spkiPrefix + privateKey.generatePublicKey().encoded) +
            "\n-----END PUBLIC KEY-----"
        val document = JSONObject()
            .put("signature_algorithm", "Ed25519")
            .put("signed_payload", Base64.getEncoder().encodeToString(payloadBytes))
            .put("signature", Base64.getEncoder().encodeToString(signature))
            .put("payload", JSONObject(canonical))
        val verifier = PremiumLicenceVerifier(publicPem)
        assertTrue(verifier.verify(document, code))
        // A persisted licence can be re-verified after restart without retaining the activation code.
        assertTrue(verifier.verifyStored(document))

        val alteredMirror = JSONObject(document.toString()).apply {
            put("payload", JSONObject(canonical).put("customer_reference", "tampered"))
        }
        assertFalse(verifier.verify(alteredMirror, code))
        assertFalse(verifier.verifyStored(alteredMirror))
        assertFalse(verifier.verify(document, code + "!"))
        assertFalse(verifier.verify(document, code + "é"))
        assertFalse(verifier.verify(document, code.replace("-", "")))
    }
}
