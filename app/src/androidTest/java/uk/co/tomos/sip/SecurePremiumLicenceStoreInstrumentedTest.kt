package uk.co.tomos.sip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurePremiumLicenceStoreInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val deviceId = "123e4567-e89b-12d3-a456-426614174000"
    private lateinit var verifier: PremiumLicenceVerifier
    private lateinit var document: JSONObject

    @Before
    fun resetPersistentLicenceState() {
        context.getSharedPreferences("tomos_premium_licence", Context.MODE_PRIVATE)
            .edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias("tomos_premium_licence_aes")) deleteEntry("tomos_premium_licence_aes")
        }

        val canonical = """{"activation_code_sha256":"0000000000000000000000000000000000000000000000000000000000000000","customer_reference":"instrumented-test","device_id":"$deviceId","features":["multiple_sip_accounts","advanced_codec_controls","encrypted_backup"],"issued_at":"2026-11-08T00:00:00Z","licence_id":"00000000-0000-0000-0000-000000000002","licence_type":"lifetime","product":"tomos-sip","schema":1}"""
        val payloadBytes = canonical.toByteArray(Charsets.UTF_8)
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val signer = Ed25519Signer().apply {
            init(true, privateKey)
            update(payloadBytes, 0, payloadBytes.size)
        }
        val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        val publicPem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getEncoder().encodeToString(prefix + privateKey.generatePublicKey().encoded) +
            "\n-----END PUBLIC KEY-----"
        verifier = PremiumLicenceVerifier(publicPem)
        document = JSONObject()
            .put("signature_algorithm", "Ed25519")
            .put("signed_payload", Base64.getEncoder().encodeToString(payloadBytes))
            .put("signature", Base64.getEncoder().encodeToString(signer.generateSignature()))
            .put("payload", JSONObject(canonical))
    }

    @Test
    fun verifiedLicenceSurvivesStoreRecreationAndIsBoundToTheSameInstallation() {
        SecurePremiumLicenceStore(context).importOffline(document, deviceId, verifier)

        // A fresh store instance models app/process recreation: only SharedPreferences
        // and the Android Keystore key survive, not the original in-memory object.
        val restored = SecurePremiumLicenceStore(context).loadVerifiedOffline(deviceId, verifier)
        assertNotNull("A correctly signed saved licence should restore after recreation", restored)
        assertNull(
            "The restored licence must not unlock a different installation",
            SecurePremiumLicenceStore(context).loadVerifiedOffline(
                "123e4567-e89b-12d3-a456-426614174001",
                verifier
            )
        )
    }

    @Test
    fun corruptStoredCiphertextFailsClosed() {
        SecurePremiumLicenceStore(context).importOffline(document, deviceId, verifier)
        context.getSharedPreferences("tomos_premium_licence", Context.MODE_PRIVATE)
            .edit().putString("licence", "not-valid-base64").commit()

        assertNull(SecurePremiumLicenceStore(context).loadVerifiedOffline(deviceId, verifier))
    }
}
