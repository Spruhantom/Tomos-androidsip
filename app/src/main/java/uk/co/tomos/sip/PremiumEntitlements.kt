package uk.co.tomos.sip

/**
 * Features that may be included in a TomOS SIP Premium lifetime licence.
 *
 * Keep this catalogue independent of UI and payment-provider code so that
 * access decisions can be tested and enforced by the feature's domain layer.
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
    EXPIRED,
    VERIFICATION_ERROR
}

/**
 * A verified entitlement snapshot. A null expiry represents a lifetime licence.
 *
 * The app must only construct PREMIUM_ACTIVE from a licence verified by the
 * trusted licensing layer; a locally stored boolean is not proof of purchase.
 */
data class EntitlementSnapshot(
    val status: EntitlementStatus,
    val expiresAtEpochMillis: Long? = null
) {
    fun grants(feature: PremiumFeature, nowEpochMillis: Long): Boolean {
        // All features in the initial catalogue are Premium-only.
        if (feature !in PremiumFeature.entries) return false
        if (status != EntitlementStatus.PREMIUM_ACTIVE) return false
        return expiresAtEpochMillis == null || nowEpochMillis < expiresAtEpochMillis
    }

    companion object {
        val FREE = EntitlementSnapshot(EntitlementStatus.FREE)
        val UNKNOWN = EntitlementSnapshot(EntitlementStatus.UNKNOWN)
    }
}

/** Injectable boundary for the future activation-code/licence service. */
interface EntitlementRepository {
    fun current(): EntitlementSnapshot
}

/**
 * Safe default until a server-verified activation flow is implemented.
 * It intentionally grants no Premium access and does not modify user data.
 */
class FreeEntitlementRepository : EntitlementRepository {
    override fun current(): EntitlementSnapshot = EntitlementSnapshot.FREE
}
