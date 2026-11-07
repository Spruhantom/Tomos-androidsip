package uk.co.tomos.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHistoryLogicTest {
    @Test fun incomingUnansweredCallIsMissed() {
        assertEquals("Missed", CallHistoryLogic.result("Incoming", connected = false, failed = false))
    }

    @Test fun answeredIncomingCallIsCompleted() {
        assertEquals("Completed", CallHistoryLogic.result("Incoming", connected = true, failed = false))
    }

    @Test fun unansweredOutgoingCallIsCancelled() {
        assertEquals("Cancelled", CallHistoryLogic.result("Outgoing", connected = false, failed = false))
    }

    @Test fun connectedOutgoingCallIsCompleted() {
        assertEquals("Completed", CallHistoryLogic.result("Outgoing", connected = true, failed = false))
    }

    @Test fun failedCallTakesPrecedenceOverOtherOutcomes() {
        assertEquals("Failed", CallHistoryLogic.result("Incoming", connected = true, failed = true))
    }

    @Test fun durationUsesConnectedTimeAndWholeSeconds() {
        assertEquals(42L, CallHistoryLogic.durationSeconds(true, 10_000L, 52_999L))
    }

    @Test fun unconnectedCallHasZeroDuration() {
        assertEquals(0L, CallHistoryLogic.durationSeconds(false, 0L, 60_000L))
    }

    @Test fun negativeElapsedDurationIsClampedToZero() {
        assertEquals(0L, CallHistoryLogic.durationSeconds(true, 20_000L, 10_000L))
    }

    @Test fun legacyHistoryIsMigratedAndBlankLinesAreIgnored() {
        val migrated = CallHistoryLogic.migrateLegacy(listOf("1001 • End", "", "Alice • Missed"))
        assertEquals(2, migrated.size)
        assertEquals("legacy-0", migrated[0].id)
        assertEquals("1001", migrated[0].peer)
        assertEquals("End", migrated[0].result)
        assertEquals("Unknown", migrated[0].direction)
        assertEquals("Alice", migrated[1].peer)
        assertEquals("Missed", migrated[1].result)
    }

    @Test fun deletingOneRecordLeavesOtherRecordsUntouched() {
        val first = SipCallRecord("1", "Alice", "sip:1001@example.test", "Incoming", "Missed", 1L, 0L)
        val second = SipCallRecord("2", "Bob", "sip:1002@example.test", "Outgoing", "Completed", 2L, 30L)
        val remaining = CallHistoryLogic.delete(listOf(first, second), "1")
        assertEquals(listOf(second), remaining)
        assertFalse(remaining.any { it.id == "1" })
    }

    @Test fun deletingUnknownRecordDoesNotChangeHistory() {
        val record = SipCallRecord("1", "Alice", "sip:1001@example.test", "Incoming", "Missed", 1L, 0L)
        val remaining = CallHistoryLogic.delete(listOf(record), "missing")
        assertEquals(listOf(record), remaining)
        assertTrue(remaining.isNotEmpty())
    }
}
