package uk.co.tomos.sip

/**
 * Backoff policy for SIP registration recovery.
 *
 * Retries continue until registration succeeds or connectivity is restored.
 * The cap prevents a prolonged PBX outage from causing rapid retry loops.
 */
internal object SipRegistrationRetryPolicy {
    private const val INITIAL_DELAY_MILLIS = 5_000L
    private const val MAX_DELAY_MILLIS = 300_000L
    private const val MAX_BACKOFF_STEP = 6

    fun delayMillis(attempt: Int): Long {
        require(attempt >= 0) { "Retry attempt cannot be negative" }
        val multiplier = 1L shl attempt.coerceAtMost(MAX_BACKOFF_STEP)
        return (INITIAL_DELAY_MILLIS * multiplier).coerceAtMost(MAX_DELAY_MILLIS)
    }

    fun nextAttempt(attempt: Int): Int {
        require(attempt >= 0) { "Retry attempt cannot be negative" }
        return (attempt + 1).coerceAtMost(MAX_BACKOFF_STEP)
    }
}
