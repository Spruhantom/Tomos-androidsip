package uk.co.tomos.sip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumActivationClientTest {
    private data class Fixture(
        val code: String,
        val document: JSONObject,
        val publicKeyPem: String
    )

    private class StubConnection(
        url: URL,
        private val status: Int,
        private val response: String
    ) : HttpURLConnection(url) {
        private val request = ByteArrayOutputStream()

        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getOutputStream(): OutputStream = request
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream {
            if (status !in 200..299) throw IOException("HTTP $status")
            return ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        }
        override fun getErrorStream(): InputStream =
            ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))

        fun requestText(): String = request.toString(Charsets.UTF_8)
    }

    private fun fixture(): Fixture {
        val code = "TOMOS-PREM-ABCD-EFGH-IJKL-MNOP"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        val canonical = """{"activation_code_sha256":"$digest","customer_reference":"android-client-test","features":["multiple_sip_accounts","advanced_codec_controls","encrypted_backup"],"issued_at":"2026-11-08T00:00:00Z","licence_id":"00000000-0000-0000-0000-000000000001","licence_type":"lifetime","product":"tomos-sip","schema":1}"""
        val payloadBytes = canonical.toByteArray(Charsets.UTF_8)
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val signer = Ed25519Signer().apply {
            init(true, privateKey)
            update(payloadBytes, 0, payloadBytes.size)
        }
        val spkiPrefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
        )
        val publicPem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getEncoder().encodeToString(spkiPrefix + privateKey.generatePublicKey().encoded) +
            "\n-----END PUBLIC KEY-----"
        val document = JSONObject()
            .put("payload", JSONObject(canonical))
            .put("signature_algorithm", "Ed25519")
            .put("signed_payload", Base64.getEncoder().encodeToString(payloadBytes))
            .put("signature", Base64.getEncoder().encodeToString(signer.generateSignature()))
        return Fixture(code, document, publicPem)
    }

    @Test fun successfulRedemptionSendsCodeAndInstallationIdThenPersistsVerifiedLicence() {
        val fixture = fixture()
        val response = JSONObject().put("licence", fixture.document).toString()
        lateinit var connection: StubConnection
        var saved: JSONObject? = null
        val client = PremiumActivationClient(
            endpoint = "https://licensing.example.test",
            verifier = PremiumLicenceVerifier(fixture.publicKeyPem),
            installationId = { "123e4567-e89b-12d3-a456-426614174000" },
            saveLicence = { saved = JSONObject(it.toString()) },
            openConnection = { url ->
                connection = StubConnection(url, 200, response)
                connection
            }
        )

        val result = kotlinx.coroutines.runBlocking {
            client.redeem("  ${fixture.code.lowercase()}  ")
        }

        assertTrue(result.isSuccess)
        assertEquals(fixture.document.toString(), result.getOrThrow().toString())
        assertEquals(fixture.document.toString(), saved?.toString())
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        val request = JSONObject(connection.requestText())
        assertEquals(fixture.code, request.getString("activation_code"))
        assertEquals("123e4567-e89b-12d3-a456-426614174000", request.getString("device_id"))
        assertTrue(connection.url.toString().endsWith("/v1/redeem"))
    }

    @Test fun invalidServerSignatureIsRejectedAndNeverStored() {
        val fixture = fixture()
        val tampered = JSONObject(fixture.document.toString())
            .put("signature", Base64.getEncoder().encodeToString(ByteArray(64)))
        var saved = false
        val client = PremiumActivationClient(
            endpoint = "https://licensing.example.test",
            verifier = PremiumLicenceVerifier(fixture.publicKeyPem),
            installationId = { "123e4567-e89b-12d3-a456-426614174000" },
            saveLicence = { saved = true },
            openConnection = { url -> StubConnection(
                url, 200, JSONObject().put("licence", tampered).toString()
            ) }
        )

        val result = kotlinx.coroutines.runBlocking { client.redeem(fixture.code) }

        assertTrue(result.isFailure)
        assertFalse(saved)
    }

    @Test fun serverConflictIsReturnedToTheUserAndLicenceIsNotStored() {
        val fixture = fixture()
        var saved = false
        val client = PremiumActivationClient(
            endpoint = "https://licensing.example.test",
            verifier = PremiumLicenceVerifier(fixture.publicKeyPem),
            installationId = { "123e4567-e89b-12d3-a456-426614174000" },
            saveLicence = { saved = true },
            openConnection = { url -> StubConnection(
                url, 409, """{"detail":"This activation code has already been redeemed on another device."}"""
            ) }
        )

        val result = kotlinx.coroutines.runBlocking { client.redeem(fixture.code) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("another device"))
        assertFalse(saved)
    }

    @Test fun nonHttpsEndpointIsRejectedBeforeOpeningConnection() {
        val fixture = fixture()
        var opened = false
        val client = PremiumActivationClient(
            endpoint = "http://licensing.example.test",
            verifier = PremiumLicenceVerifier(fixture.publicKeyPem),
            installationId = { "123e4567-e89b-12d3-a456-426614174000" },
            saveLicence = {},
            openConnection = { opened = true; StubConnection(it, 200, "{}") }
        )

        val result = kotlinx.coroutines.runBlocking { client.redeem(fixture.code) }

        assertTrue(result.isFailure)
        assertFalse(opened)
    }
}
