package uk.co.tomos.sip

/**
 * Coordinates account sign-in, token refresh and entitlement restoration.
 * UI code should call these methods from a background dispatcher.
 */
class TomosAccountSessionManager(
    private val api: TomosAccountApi,
    private val store: SecureTomosAccountStore,
    private val entitlementVerifier: SignedEntitlementVerifier,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) : EntitlementRepository {

    @Synchronized
    fun signIn(email: String, password: CharArray): EntitlementSnapshot {
        val session = api.signIn(email, password)
        store.saveSession(session)
        return refreshEntitlement()
    }

    @Synchronized
    fun refreshEntitlement(): EntitlementSnapshot {
        val existing = store.loadSession() ?: return EntitlementSnapshot.FREE
        val session = try {
            if (existing.isAccessTokenUsable(nowEpochMillis())) {
                existing
            } else {
                api.refreshSession(existing.refreshToken).also(store::saveSession)
            }
        } catch (_: Exception) {
            return cachedOrFree(existing.accountId)
        }

        return try {
            val envelope = api.fetchEntitlement(session)
            val verified = entitlementVerifier.verify(
                org.json.JSONObject(envelope.json),
                expectedAccountId = session.accountId,
                nowEpochMillis = nowEpochMillis()
            ) ?: return cachedOrFree(session.accountId)
            store.saveEntitlementEnvelope(envelope)
            verified
        } catch (_: Exception) {
            cachedOrFree(session.accountId)
        }
    }

    /** Restores access on a second device after the user signs into the same account. */
    @Synchronized
    fun restorePurchases(): EntitlementSnapshot = refreshEntitlement()

    @Synchronized
    fun signOut() {
        val session = store.loadSession()
        try {
            session?.refreshToken?.let(api::revokeSession)
        } finally {
            // Always remove local credentials, even when the server is unavailable.
            store.clearSession()
        }
    }

    override fun current(): EntitlementSnapshot {
        val session = store.loadSession() ?: return EntitlementSnapshot.FREE
        return cachedOrFree(session.accountId)
    }

    private fun cachedOrFree(accountId: String): EntitlementSnapshot {
        val cached = store.loadEntitlementEnvelope() ?: return EntitlementSnapshot.FREE
        return entitlementVerifier.verify(
            org.json.JSONObject(cached.json),
            expectedAccountId = accountId,
            nowEpochMillis = nowEpochMillis()
        ) ?: EntitlementSnapshot.FREE
    }
}
