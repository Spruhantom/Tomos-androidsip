package uk.co.tomos.sip

import org.junit.Assert.assertEquals
import org.junit.Test

class SipRegistrationRetryPolicyTest {
    @Test
    fun backoffGrowsExponentiallyForEarlyRetries() {
        assertEquals(5_000L, SipRegistrationRetryPolicy.delayMillis(0))
        assertEquals(10_000L, SipRegistrationRetryPolicy.delayMillis(1))
        assertEquals(20_000L, SipRegistrationRetryPolicy.delayMillis(2))
        assertEquals(40_000L, SipRegistrationRetryPolicy.delayMillis(3))
        assertEquals(80_000L, SipRegistrationRetryPolicy.delayMillis(4))
        assertEquals(160_000L, SipRegistrationRetryPolicy.delayMillis(5))
    }

    @Test
    fun backoffIsCappedAtFiveMinutes() {
        assertEquals(300_000L, SipRegistrationRetryPolicy.delayMillis(6))
        assertEquals(300_000L, SipRegistrationRetryPolicy.delayMillis(20))
    }

    @Test
    fun retryAttemptSaturatesAtCappedBackoffStep() {
        assertEquals(1, SipRegistrationRetryPolicy.nextAttempt(0))
        assertEquals(6, SipRegistrationRetryPolicy.nextAttempt(5))
        assertEquals(6, SipRegistrationRetryPolicy.nextAttempt(6))
        assertEquals(6, SipRegistrationRetryPolicy.nextAttempt(20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeRetryAttemptIsRejected() {
        SipRegistrationRetryPolicy.delayMillis(-1)
    }
}
