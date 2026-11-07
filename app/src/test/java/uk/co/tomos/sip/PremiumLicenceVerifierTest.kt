package uk.co.tomos.sip

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Test

class PremiumLicenceVerifierTest {
    @Test fun missingPublicKeyRejectsLicence() {
        val verifier = PremiumLicenceVerifier("")
        assertFalse(verifier.verify(JSONObject(), "TOMOS-PREM-AAAA-BBBB-CCCC-DDDD"))
    }

    @Test fun malformedLicenceIsRejected() {
        val verifier = PremiumLicenceVerifier("-----BEGIN PUBLIC KEY-----\nAA==\n-----END PUBLIC KEY-----")
        assertFalse(verifier.verify(JSONObject(), "TOMOS-PREM-AAAA-BBBB-CCCC-DDDD"))
    }
}
