package uk.co.tomos.sip

/**
 * Build-time rollout gate for the Premium test track.
 *
 * This flag only controls whether Premium functionality is exposed in the
 * dedicated testing build. It is never an entitlement and cannot grant access:
 * a verified, currently valid Premium entitlement is always required.
 */
object PremiumFeatureGate {
    fun isEnabled(
        testingBuildEnabled: Boolean,
        entitlement: EntitlementSnapshot,
        feature: PremiumFeature,
        nowEpochMillis: Long
    ): Boolean {
        if (!testingBuildEnabled) return false
        return entitlement.grants(feature, nowEpochMillis)
    }
}
