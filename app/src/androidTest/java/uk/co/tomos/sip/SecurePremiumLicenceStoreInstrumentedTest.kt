package uk.co.tomos.sip

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
