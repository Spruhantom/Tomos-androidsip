package uk.co.tomos.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumEntitlementsTest {
    private fun active(plan: SubscriptionPlan, expiresAt: Long = 20_000L) =
        EntitlementSnapshot(
            status = EntitlementStatus.PREMIUM_ACTIVE,
            plan = plan,
            source = EntitlementSource.GOOGLE_PLAY,
            accountId = "test-account",
            validFromEpochMillis = 1_000L,
            expiresAtEpochMillis = expiresAt,
            signatureVerified = true
        )

    @Test
    fun freeEntitlementDeniesEveryPremiumFeature() {
        PremiumFeature.entries.forEach { feature ->
            assertFalse(EntitlementSnapshot.FREE.grants(feature, nowEpochMillis = 1_000L))
        }
    }

    @Test
    fun verifiedMonthlySubscriptionGrantsFeaturesDuringPaidPeriod() {
        PremiumFeature.entries.forEach { feature ->
            assertTrue(active(SubscriptionPlan.MONTHLY).grants(feature, 10_000L))
        }
    }

    @Test
    fun verifiedYearlySubscriptionGrantsFeaturesDuringPaidPeriod() {
        PremiumFeature.entries.forEach { feature ->
            assertTrue(active(SubscriptionPlan.YEARLY).grants(feature, 10_000L))
        }
    }

    @Test
    fun subscriptionIsNotValidBeforeItsStartOrAtExpiryBoundary() {
        val snapshot = active(SubscriptionPlan.MONTHLY, expiresAt = 5_000L)
        assertFalse(snapshot.grants(PremiumFeature.ENCRYPTED_BACKUP, 999L))
        assertTrue(snapshot.grants(PremiumFeature.ENCRYPTED_BACKUP, 4_999L))
        assertFalse(snapshot.grants(PremiumFeature.ENCRYPTED_BACKUP, 5_000L))
    }

    @Test
    fun gracePeriodRequiresVerifiedSnapshotAndEndsAtGraceBoundary() {
        val grace = active(SubscriptionPlan.MONTHLY, expiresAt = 5_000L).copy(
            status = EntitlementStatus.GRACE_PERIOD,
            graceUntilEpochMillis = 7_000L
        )
        assertFalse(grace.grants(PremiumFeature.MULTIPLE_SIP_ACCOUNTS, 4_999L))
        assertTrue(grace.grants(PremiumFeature.MULTIPLE_SIP_ACCOUNTS, 5_000L))
        assertTrue(grace.grants(PremiumFeature.MULTIPLE_SIP_ACCOUNTS, 6_999L))
        assertFalse(grace.grants(PremiumFeature.MULTIPLE_SIP_ACCOUNTS, 7_000L))
    }

    @Test
    fun missingExpiryOrPlanNeverCreatesLifetimePremium() {
        val noExpiry = active(SubscriptionPlan.MONTHLY).copy(expiresAtEpochMillis = null)
        val noPlan = active(SubscriptionPlan.MONTHLY).copy(plan = null)
        PremiumFeature.entries.forEach { feature ->
            assertFalse(noExpiry.grants(feature, 10_000L))
            assertFalse(noPlan.grants(feature, 10_000L))
        }
    }

    @Test
    fun unverifiedOrTerminalStatesNeverGrantPremium() {
        val states = listOf(
            EntitlementStatus.UNKNOWN,
            EntitlementStatus.FREE,
            EntitlementStatus.EXPIRED,
            EntitlementStatus.REFUNDED,
            EntitlementStatus.REVOKED,
            EntitlementStatus.VERIFICATION_UNAVAILABLE,
            EntitlementStatus.VERIFICATION_ERROR
        )
        states.forEach { status ->
            val snapshot = active(SubscriptionPlan.YEARLY).copy(status = status)
            PremiumFeature.entries.forEach { feature ->
                assertFalse("$status must deny $feature", snapshot.grants(feature, 10_000L))
            }
        }
    }

    @Test
    fun invalidSignatureNeverGrantsPremium() {
        val forged = active(SubscriptionPlan.YEARLY).copy(signatureVerified = false)
        PremiumFeature.entries.forEach { feature ->
            assertFalse(forged.grants(feature, 10_000L))
        }
    }

    @Test
    fun defaultRepositoryIsFreeUntilAccountEntitlementIsVerified() {
        val repository: EntitlementRepository = FreeEntitlementRepository()
        assertEquals(EntitlementSnapshot.FREE, repository.current())
    }
}
