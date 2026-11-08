package uk.co.tomos.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TomosFullBackupCodecTest {
    private val contact = SipContact("contact-1", "Alice", "sip:1001@example.test")
    private val call = SipCallRecord("call-1", "Alice", "sip:1001@example.test", "Outgoing", "Completed", 1_700_000_000L, 42L)
    private val account = SipAccountProfile(
        id = "account-1", label = "Office", username = "1001",
        authUsername = "1001", domain = "pbx.example.test"
    )

    @Test fun schemaTwoRoundTripPreservesAllPortableState() {
        val json = TomosFullBackupCodec.encode(
            listOf(contact), listOf(call), listOf(account), mapOf(account.id to "secret"),
            account.id, setOf("opus", "pcmu"), true, 123L
        )
        val restored = TomosFullBackupCodec.decode(json)
        assertEquals(2, restored.schemaVersion)
        assertEquals(123L, restored.createdAt)
        assertEquals(listOf(contact), restored.contacts)
        assertEquals(listOf(call), restored.callHistory)
        assertEquals(listOf(account), restored.sipAccounts)
        assertEquals(mapOf(account.id to "secret"), restored.sipCredentials)
        assertEquals(account.id, restored.selectedSipAccountId)
        assertEquals(listOf("opus", "pcmu"), restored.enabledAudioCodecs)
        assertEquals(true, restored.backupRemindersEnabled)
        assertFalse(json.contains("premium", ignoreCase = true))
    }

    @Test fun migratesSchemaOneWithoutInventingAccountOrCredentialData() {
        val legacy = TomosBackupCodec.encode(listOf(contact), listOf(call), 123L)
        val restored = TomosFullBackupCodec.decode(legacy)
        assertEquals(2, restored.schemaVersion)
        assertEquals(listOf(contact), restored.contacts)
        assertEquals(listOf(call), restored.callHistory)
        assertEquals(emptyList<SipAccountProfile>(), restored.sipAccounts)
        assertEquals(emptyMap<String, String>(), restored.sipCredentials)
        assertNull(restored.selectedSipAccountId)
    }

    @Test fun rejectsCredentialsForUnknownAccounts() {
        val json = TomosFullBackupCodec.encode(
            listOf(contact), listOf(call), listOf(account), mapOf("other-account" to "secret"),
            account.id, emptySet(), false
        )
        assertThrows(IllegalArgumentException::class.java) { TomosFullBackupCodec.decode(json) }
    }

    @Test fun rejectsUnknownSelectedAccount() {
        val json = TomosFullBackupCodec.encode(
            listOf(contact), listOf(call), listOf(account), emptyMap(),
            "missing-account", emptySet(), false
        )
        assertThrows(IllegalArgumentException::class.java) { TomosFullBackupCodec.decode(json) }
    }

    @Test fun rejectsDuplicateAccountIds() {
        val json = TomosFullBackupCodec.encode(
            listOf(contact), listOf(call), listOf(account, account), emptyMap(),
            account.id, emptySet(), false
        )
        assertThrows(IllegalArgumentException::class.java) { TomosFullBackupCodec.decode(json) }
    }

    @Test fun rejectsUnsupportedSchemaVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            TomosFullBackupCodec.decode("""{"schemaVersion":99}""")
        }
    }
}
