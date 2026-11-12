package uk.co.tomos.sip

/**
 * Premium-only capabilities. Free features and plugin capabilities are catalogued
 * separately so a subscription cannot accidentally gate core SIP functionality.
 */
enum class PremiumFeature {
    MULTIPLE_SIP_ACCOUNTS,
    ADVANCED_CODEC_CONTROLS,
    ENCRYPTED_BACKUP
}

enum class EntitlementStatus {
    UNKNOWN,
    FREE,
    PREMIUM_ACTIVE,
    GRACE_PERIOD,
    EXPIRED,
    REFUNDED,
    REVOKED,
    VERIFICATION_UNAVAILABLE,
    VERIFICATION_ERROR
}

enum class SubscriptionPlan {
    MONTHLY,
    YEARLY
}

enum class EntitlementSource {
    GOOGLE_PLAY,
    GITHUB_DIRECT
}

/**
 * A subscription snapshot accepted only after signature/provider verification.
 *
 * Premium access is time-bounded: a missing expiry is never interpreted as a
 * lifetime subscription. The backend remains the source of truth; this model
 * only represents the verified snapshot cached by the application.
 */
data class EntitlementSnapshot(
    val status: EntitlementStatus,
    val plan: SubscriptionPlan? = null,
    val source: EntitlementSource? = null,
    val accountId: String? = null,
    val validFromEpochMillis: Long? = null,
    val expiresAtEpochMillis: Long? = null,
    val graceUntilEpochMillis: Long? = null,
    val signatureVerified: Boolean = false
) {
    fun grants(feature: PremiumFeature, nowEpochMillis: Long): Boolean {
        // Every item in this catalogue is subscription-gated Premium functionality.
        val isPremiumFeature = when (feature) {
            PremiumFeature.MULTIPLE_SIP_ACCOUNTS,
            PremiumFeature.ADVANCED_CODEC_CONTROLS,
            PremiumFeature.ENCRYPTED_BACKUP -> true
        }
        if (!isPremiumFeature || !signatureVerified || accountId.isNullOrBlank() || source == null) {
            return false
        }

        val startsAt = validFromEpochMillis ?: return false
        val expiresAt = expiresAtEpochMillis ?: return false
        if (plan == null || nowEpochMillis < startsAt) return false

        return when (status) {
            EntitlementStatus.PREMIUM_ACTIVE -> nowEpochMillis < expiresAt
            EntitlementStatus.GRACE_PERIOD -> {
                val graceUntil = graceUntilEpochMillis ?: return false
                nowEpochMillis >= expiresAt && nowEpochMillis < graceUntil
            }
            else -> false
        }
    }

    companion object {
        val FREE = EntitlementSnapshot(status = EntitlementStatus.FREE)
        val UNKNOWN = EntitlementSnapshot(status = EntitlementStatus.UNKNOWN)
    }
}

/** Read-only boundary for the currently verified/cached account entitlement. */
interface EntitlementRepository {
    fun current(): EntitlementSnapshot
}

/** Safe default before account sign-in and server verification are implemented. */
class FreeEntitlementRepository : EntitlementRepository {
    override fun current(): EntitlementSnapshot = EntitlementSnapshot.FREE
}
