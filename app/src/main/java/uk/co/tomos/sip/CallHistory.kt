package uk.co.tomos.sip

data class SipCallRecord(
    val id: String,
    val peer: String,
    val address: String,
    val direction: String,
    val result: String,
    val startedAt: Long,
    val durationSeconds: Long
)

/** Pure call-history rules so call outcomes can be tested without a Linphone Core. */
internal object CallHistoryLogic {
    fun result(direction: String, connected: Boolean, failed: Boolean): String = when {
        failed -> "Failed"
        direction == "Incoming" && !connected -> "Missed"
        connected -> "Completed"
        else -> "Cancelled"
    }

    fun durationSeconds(connected: Boolean, connectedAtElapsedMillis: Long, endedAtElapsedMillis: Long): Long {
        if (!connected || connectedAtElapsedMillis <= 0L) return 0L
        return ((endedAtElapsedMillis - connectedAtElapsedMillis) / 1000L).coerceAtLeast(0L)
    }

    fun delete(records: List<SipCallRecord>, id: String): List<SipCallRecord> =
        records.filterNot { it.id == id }

    fun migrateLegacy(lines: List<String>): List<SipCallRecord> =
        lines.filter { it.isNotBlank() }.mapIndexed { index, line ->
            val peer = line.substringBefore(" • ")
            SipCallRecord(
                id = "legacy-$index",
                peer = peer,
                address = peer,
                direction = "Unknown",
                result = line.substringAfter(" • ", "Previous call"),
                startedAt = 0L,
                durationSeconds = 0L
            )
        }
}
