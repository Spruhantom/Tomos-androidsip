package uk.co.tomos.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumEntitlementsTest {
    @Test fun freeEntitlementDeniesEveryPremiumFeature() {
        PremiumFeature.entries.forEach { feature ->
            assertFalse(EntitlementSnapshot.FREE.grants(feature, nowEpochMillis = 1_000L))
        }
    }

    @Test fun lifetimePremiumGrantsInitialFeatures() {
        val lifetime = EntitlementSnapshot(EntitlementStatus.PREMIUM_ACTIVE)
        PremiumFeature.entries.forEach { feature ->
            assertTrue(lifetime.grants(feature, nowEpochMillis = 9_999_999L))
        }
    }

    @Test fun timeLimitedEntitlementExpiresAtBoundary() {
        val trial = EntitlementSnapshot(EntitlementStatus.PREMIUM_ACTIVE, expiresAtEpochMillis = 5_000L)
        assertTrue(trial.grants(PremiumFeature.ENCRYPTED_BACKUP, nowEpochMillis = 4_999L))
        assertFalse(trial.grants(PremiumFeature.ENCRYPTED_BACKUP, nowEpochMillis = 5_000L))
    }

    @Test fun unknownExpiredAndVerificationErrorNeverGrantPremium() {
        listOf(
            EntitlementStatus.UNKNOWN,
            EntitlementStatus.FREE,
            EntitlementStatus.EXPIRED,
            EntitlementStatus.VERIFICATION_ERROR
        ).forEach { status ->
            val snapshot = EntitlementSnapshot(status)
            PremiumFeature.entries.forEach { feature ->
                assertFalse("$status must deny $feature", snapshot.grants(feature, 1_000L))
            }
        }
    }

    @Test fun defaultRepositoryIsFreeUntilActivationIsVerified() {
        val repository: EntitlementRepository = FreeEntitlementRepository()
        assertEquals(EntitlementSnapshot.FREE, repository.current())
    }
}
