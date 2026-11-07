package uk.co.tomos.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SipAccountProfileTest {
    private fun validProfile() = SipAccountProfile(
        label = "Office",
        username = "1001",
        authUsername = "1001",
        domain = "pbx.example.com"
    )

    @Test fun acceptsValidUdpProfile() {
        assertNull(validateSipAccount(validProfile()))
    }

    @Test fun rejectsMissingLabel() {
        assertEquals("Enter an account label.", validateSipAccount(validProfile().copy(label = " ")))
    }

    @Test fun rejectsMissingIdentity() {
        assertEquals("Enter the SIP extension or identity.", validateSipAccount(validProfile().copy(username = "")))
    }

    @Test fun rejectsInvalidPort() {
        assertEquals("SIP port must be between 1 and 65535.", validateSipAccount(validProfile().copy(port = 70000)))
    }

    @Test fun rejectsExpiryOutsideSupportedRange() {
        assertEquals(
            "Registration expiry must be between 60 and 86400 seconds.",
            validateSipAccount(validProfile().copy(expirySeconds = 30))
        )
    }

    @Test fun rejectsMalformedDomain() {
        assertEquals("Enter a valid SIP server hostname.", validateSipAccount(validProfile().copy(domain = "pbx.example.com/path")))
    }

    @Test fun profilesHaveIndependentStableIds() {
        val first = validProfile()
        val second = validProfile().copy(label = "Home")
        assertEquals(false, first.id == second.id)
    }
}
