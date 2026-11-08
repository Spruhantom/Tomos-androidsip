package uk.co.tomos.sip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumFeatureGateTest {
    private val now = 10_000L

    @Test fun testingGateOffDeniesEvenAValidPremiumEntitlement() {
        val premium = EntitlementSnapshot(EntitlementStatus.PREMIUM_ACTIVE)
        PremiumFeature.entries.forEach { feature ->
            assertFalse(PremiumFeatureGate.isEnabled(false, premium, feature, now))
        }
    }

    @Test fun testingGateOnStillRequiresPremiumEntitlement() {
        val nonPremiumStatuses = listOf(
            EntitlementStatus.UNKNOWN,
            EntitlementStatus.FREE,
            EntitlementStatus.EXPIRED,
            EntitlementStatus.VERIFICATION_ERROR
        )
        nonPremiumStatuses.forEach { status ->
            val snapshot = EntitlementSnapshot(status)
            PremiumFeature.entries.forEach { feature ->
                assertFalse(
                    "$status must not unlock $feature",
                    PremiumFeatureGate.isEnabled(true, snapshot, feature, now)
                )
            }
        }
    }

    @Test fun testingGateOnAllowsFeaturesForAnActiveLifetimeLicence() {
        val premium = EntitlementSnapshot(EntitlementStatus.PREMIUM_ACTIVE)
        PremiumFeature.entries.forEach { feature ->
            assertTrue(PremiumFeatureGate.isEnabled(true, premium, feature, now))
        }
    }

    @Test fun testingGateOnStillHonoursLicenceExpiry() {
        val expired = EntitlementSnapshot(
            EntitlementStatus.PREMIUM_ACTIVE,
            expiresAtEpochMillis = now
        )
        assertFalse(
            PremiumFeatureGate.isEnabled(true, expired, PremiumFeature.ENCRYPTED_BACKUP, now)
        )
    }
}
