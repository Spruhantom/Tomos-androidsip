package uk.co.tomos.sip

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Exercises the real Android Keystore on an emulator or physical Android device. */
@RunWith(AndroidJUnit4::class)
class SecurePremiumLicenceStoreInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("tomos_premium_licence", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tomos_premium_installation", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("tomos_premium_licence", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tomos_premium_installation", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun generatorFixture(): JSONObject {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open("offline-licence-fixture.json").bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun fixtureVerifier(): PremiumLicenceVerifier {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val publicKey = assets.open("offline-licence-public.pem").bufferedReader().use { it.readText() }
        return PremiumLicenceVerifier(publicKey)
    }

    @Test
    fun pythonGeneratedOfflineLicenceIsAcceptedAndDeviceBoundByAndroid() {
        val licence = generatorFixture()
        val verifier = fixtureVerifier()
        assertTrue(verifier.verifyOffline(licence, "123e4567-e89b-12d3-a456-426614174001"))
        assertFalse(verifier.verifyOffline(licence, "123e4567-e89b-12d3-a456-426614174099"))

        val tampered = JSONObject(licence.toString())
            .put("signature", Base64.getEncoder().encodeToString(ByteArray(64)))
        assertFalse(verifier.verifyOffline(tampered, "123e4567-e89b-12d3-a456-426614174001"))
    }

    @Test
    fun pythonGeneratedOfflineLicenceCanBeImportedAndRestoredFromAndroidKeystore() {
        val licence = generatorFixture()
        val verifier = fixtureVerifier()
        val deviceId = "123e4567-e89b-12d3-a456-426614174001"
        SecurePremiumLicenceStore(context).importOffline(licence, deviceId, verifier)

        val restored = SecurePremiumLicenceStore(context).loadVerifiedOffline(deviceId, verifier)
        assertNotNull(restored)
        assertEquals(licence.getString("signature"), restored?.getString("signature"))
    }

    @Test
    fun encryptedLicenceCanBeReadByNewStoreInstanceAfterRestart() {
        val licence = """{"signed_payload":"fixture-payload","signature":"fixture-signature"}"""
        SecurePremiumLicenceStore(context).save(licence)
        assertEquals(licence, SecurePremiumLicenceStore(context).load())
    }

    @Test
    fun preferencesDoNotContainThePlaintextLicence() {
        val licence = """{"signed_payload":"sensitive-fixture","signature":"fixture-signature"}"""
        SecurePremiumLicenceStore(context).save(licence)
        val encrypted = context.getSharedPreferences("tomos_premium_licence", Context.MODE_PRIVATE).getString("licence", null)
        assertNotNull(encrypted)
        assertNotEquals(licence, encrypted)
        assertNotEquals(licence, String(Base64.getDecoder().decode(encrypted)))
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        SecurePremiumLicenceStore(context).save("""{"licence":"original"}""")
        val prefs = context.getSharedPreferences("tomos_premium_licence", Context.MODE_PRIVATE)
        val packed = Base64.getDecoder().decode(prefs.getString("licence", null))
        packed[packed.lastIndex] = (packed.last().toInt() xor 0x01).toByte()
        prefs.edit().putString("licence", Base64.getEncoder().encodeToString(packed)).commit()
        assertNull(SecurePremiumLicenceStore(context).load())
    }

    @Test
    fun installationIdIsStableUntilItsAppPreferenceIsCleared() {
        val first = PremiumInstallationId.get(context)
        assertEquals(first, PremiumInstallationId.get(context))
        // Clearing app-private identity data simulates the identity reset after app-data removal.
        context.getSharedPreferences("tomos_premium_installation", Context.MODE_PRIVATE).edit().clear().commit()
        assertNotEquals(first, PremiumInstallationId.get(context))
    }
}
