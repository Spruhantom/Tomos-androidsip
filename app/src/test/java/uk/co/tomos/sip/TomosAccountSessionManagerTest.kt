package uk.co.tomos.sip

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TomosAccountSessionManagerTest {
    private val now = 2_000_000L
    private val activeSnapshot = EntitlementSnapshot(
        status = EntitlementStatus.PREMIUM_ACTIVE,
        plan = SubscriptionPlan.MONTHLY,
        source = EntitlementSource.GITHUB_DIRECT,
        accountId = "account-1",
        validFromEpochMillis = 1_000_000L,
        expiresAtEpochMillis = 9_000_000L,
        signatureVerified = true
    )

    private class MemoryStore : TomosAccountStore {
        var session: TomosAccountSession? = null
        var envelope: SignedEntitlementEnvelope? = null
        var cleared = false
        override fun saveSession(session: TomosAccountSession) { this.session = session }
        override fun loadSession(): TomosAccountSession? = session
        override fun saveEntitlementEnvelope(envelope: SignedEntitlementEnvelope) { this.envelope = envelope }
        override fun loadEntitlementEnvelope(): SignedEntitlementEnvelope? = envelope
        override fun clearSession() {
            session = null
            envelope = null
            cleared = true
        }
    }

    private class FakeApi : TomosAccountApi {
        var signedInSession = accountTestSession("account-1", 9_000_000L)
        var refreshedSession = accountTestSession("account-1", 9_000_000L)
        var entitlement = SignedEntitlementEnvelope("""{"signed":"test"}""")
        var fetchCount = 0
        var refreshCount = 0
        var revokeCount = 0
        var restoreCount = 0
        var failRefresh = false
        var failRevoke = false

        override fun signIn(email: String, password: CharArray): TomosAccountSession {
            password.fill('\u0000')
            return signedInSession
        }
        override fun fetchEntitlement(session: TomosAccountSession): SignedEntitlementEnvelope {
            fetchCount++
            return entitlement
        }
        override fun refreshSession(refreshToken: String): TomosAccountSession {
            refreshCount++
            if (failRefresh) error("network unavailable")
            return refreshedSession
        }
        override fun restorePurchases(session: TomosAccountSession) { restoreCount++ }
        override fun revokeSession(refreshToken: String) {
            revokeCount++
            if (failRevoke) error("network unavailable")
        }
    }

    private class FakeVerifier(private val snapshot: EntitlementSnapshot?) : EntitlementEnvelopeVerifier {
        override fun verify(
            envelope: JSONObject,
            expectedAccountId: String,
            nowEpochMillis: Long
        ): EntitlementSnapshot? {
            if (envelope.optString("signed") != "test") return null
            return snapshot?.takeIf { it.accountId == expectedAccountId }
        }
    }

    private fun manager(
        api: FakeApi,
        store: MemoryStore,
        snapshot: EntitlementSnapshot? = activeSnapshot
    ) = TomosAccountSessionManager(api, store, FakeVerifier(snapshot)) { now }

    @Test
    fun signInStoresSessionAndFetchesVerifiedEntitlement() {
        val api = FakeApi()
        val store = MemoryStore()
        val result = manager(api, store).signIn("tom@example.com", charArrayOf('p', 'w'))
        assertEquals("account-1", store.session?.accountId)
        assertEquals(1, api.fetchCount)
        assertEquals(EntitlementStatus.PREMIUM_ACTIVE, result.status)
    }

    @Test
    fun restoreRefreshesExpiredAccessTokenBeforeFetchingEntitlement() {
        val api = FakeApi()
        val store = MemoryStore().apply {
            session = accountTestSession("account-1", 1_000_000L)
        }
        val result = manager(api, store).restorePurchases()
        assertEquals(1, api.refreshCount)
        assertEquals(1, api.fetchCount)
        assertEquals(1, api.restoreCount)
        assertEquals(9_000_000L, store.session?.accessTokenExpiresAtEpochMillis)
        assertEquals(EntitlementStatus.PREMIUM_ACTIVE, result.status)
    }

    @Test
    fun failedNetworkRefreshUsesOnlyStillValidSignedCache() {
        val api = FakeApi().apply { failRefresh = true }
        val store = MemoryStore().apply {
            session = session("account-1", accessExpiry = 1_000_000L)
            envelope = SignedEntitlementEnvelope("""{"signed":"test"}""")
        }
        val result = manager(api, store).refreshEntitlement()
        assertEquals(EntitlementStatus.PREMIUM_ACTIVE, result.status)
        assertEquals(0, api.fetchCount)
    }

    @Test
    fun accountMismatchInCachedEnvelopeFallsBackToFree() {
        val api = FakeApi()
        val store = MemoryStore().apply {
            session = accountTestSession("different-account", 1_000_000L)
            envelope = SignedEntitlementEnvelope("""{"signed":"test"}""")
        }
        val result = manager(api, store, activeSnapshot).current()
        assertEquals(EntitlementStatus.FREE, result.status)
    }

    @Test
    fun signOutClearsLocalCredentialsEvenIfRemoteRevocationFails() {
        val api = FakeApi().apply { failRevoke = true }
        val store = MemoryStore().apply {
            session = session("account-1", 9_000_000L)
            envelope = SignedEntitlementEnvelope("""{"signed":"test"}""")
        }
        val result = runCatching { manager(api, store).signOut() }
        assertTrue(result.isFailure)
        assertEquals(1, api.revokeCount)
        assertTrue(store.cleared)
        assertFalse(store.session != null)
        assertFalse(store.envelope != null)
    }

}

private fun accountTestSession(account: String, accessExpiry: Long) = TomosAccountSession(
    accountId = account,
    accessToken = "access-token",
    refreshToken = "refresh-token",
    accessTokenExpiresAtEpochMillis = accessExpiry
)
