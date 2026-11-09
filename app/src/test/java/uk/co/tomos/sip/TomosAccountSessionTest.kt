package uk.co.tomos.sip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TomosAccountSessionTest {
    @Test
    fun accessTokenRequiresSafetyWindowBeforeExpiry() {
        val session = TomosAccountSession(
            accountId = "account-1",
            accessToken = "access",
            refreshToken = "refresh",
            accessTokenExpiresAtEpochMillis = 120_000L
        )
        assertTrue(session.isAccessTokenUsable(nowEpochMillis = 50_000L))
        assertFalse(session.isAccessTokenUsable(nowEpochMillis = 60_000L))
        assertFalse(session.isAccessTokenUsable(nowEpochMillis = 120_000L))
    }

    @Test
    fun blankCredentialsAreRejected() {
        val invalid = runCatching {
            TomosAccountSession("account-1", "", "refresh", 120_000L)
        }
        assertTrue(invalid.isFailure)
    }
}
