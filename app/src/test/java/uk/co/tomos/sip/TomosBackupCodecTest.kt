package uk.co.tomos.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TomosBackupCodecTest {
    private val contact = SipContact("contact-1", "Alice", "sip:1001@example.test")
    private val call = SipCallRecord("call-1", "Alice", "sip:1001@example.test", "Outgoing", "Completed", 1_700_000_000L, 42L)

    @Test fun roundTripPreservesContactsAndCallHistory() {
        val decoded = TomosBackupCodec.decode(TomosBackupCodec.encode(listOf(contact), listOf(call), 123L))
        assertEquals(1, decoded.schemaVersion)
        assertEquals(123L, decoded.createdAt)
        assertEquals(listOf(contact), decoded.contacts)
        assertEquals(listOf(call), decoded.callHistory)
    }

    @Test fun backupDoesNotContainSipCredentials() {
        val json = TomosBackupCodec.encode(listOf(contact), listOf(call))
        assertFalse(json.contains("password", ignoreCase = true))
        assertFalse(json.contains("authUsername", ignoreCase = true))
    }

    @Test fun rejectsUnsupportedSchemaVersion() {
        val json = """{"schemaVersion":99,"createdAt":1,"contacts":[],"callHistory":[]}"""
        assertThrows(IllegalArgumentException::class.java) { TomosBackupCodec.decode(json) }
    }

    @Test fun rejectsMissingContactsOrHistory() {
        assertThrows(IllegalArgumentException::class.java) {
            TomosBackupCodec.decode("""{"schemaVersion":1,"createdAt":1,"contacts":[]}""")
        }
    }

    @Test fun rejectsDuplicateContactIds() {
        val json = TomosBackupCodec.encode(listOf(contact, contact), emptyList())
        assertThrows(IllegalArgumentException::class.java) { TomosBackupCodec.decode(json) }
    }

    @Test fun rejectsInvalidContactData() {
        val json = """{"schemaVersion":1,"createdAt":1,"contacts":[{"id":"","name":"Alice","number":"1001"}],"callHistory":[]}"""
        assertThrows(IllegalArgumentException::class.java) { TomosBackupCodec.decode(json) }
    }

    @Test fun rejectsNegativeCallDuration() {
        val json = """{"schemaVersion":1,"createdAt":1,"contacts":[],"callHistory":[{"id":"1","peer":"Alice","address":"1001","direction":"Incoming","result":"Completed","startedAt":1,"durationSeconds":-1}]}"""
        assertThrows(IllegalArgumentException::class.java) { TomosBackupCodec.decode(json) }
    }

    @Test fun rejectsOversizedBackup() {
        val json = " ".repeat(TomosBackupCodec.MAX_BACKUP_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { TomosBackupCodec.decode(json) }
    }

    @Test fun acceptsEmptyBackup() {
        val decoded = TomosBackupCodec.decode(TomosBackupCodec.encode(emptyList(), emptyList()))
        assertTrue(decoded.contacts!!.isEmpty())
        assertTrue(decoded.callHistory!!.isEmpty())
    }
}
