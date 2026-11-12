package uk.co.tomos.sip

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * Portable full-state backup payload. The JSON produced by this codec contains
 * SIP passwords in memory and MUST only be passed directly to
 * TomosBackupEncryption.encrypt(); never write it to disk, log it, or share it
 * as plaintext. Android Keystore keys and account authentication tokens are
 * deliberately not exported; credentials are included only inside the encrypted
 * backup payload.
 */
data class TomosFullBackupDocument(
    val schemaVersion: Int,
    val createdAt: Long,
    val contacts: List<SipContact>?,
    val callHistory: List<SipCallRecord>?,
    val sipAccounts: List<SipAccountProfile>?,
    val sipCredentials: Map<String, String>?,
    val selectedSipAccountId: String?,
    val enabledAudioCodecs: List<String>?,
    val backupRemindersEnabled: Boolean?
)

internal object TomosFullBackupCodec {
    const val CURRENT_SCHEMA_VERSION = 2
    private const val MAX_CONTACTS = 5_000
    private const val MAX_CALLS = 10_000
    private const val MAX_ACCOUNTS = 100
    private const val MAX_CREDENTIAL_LENGTH = 4_096
    private val gson = Gson()

    /** Encode a complete snapshot for immediate password encryption. */
    fun encode(
        contacts: List<SipContact>,
        history: List<SipCallRecord>,
        accounts: List<SipAccountProfile>,
        credentials: Map<String, String>,
        selectedAccountId: String?,
        enabledAudioCodecs: Set<String>,
        backupRemindersEnabled: Boolean,
        createdAt: Long = System.currentTimeMillis()
    ): String {
        val document = TomosFullBackupDocument(
            CURRENT_SCHEMA_VERSION, createdAt, contacts.toList(), history.toList(),
            accounts.toList(), credentials.toMap(), selectedAccountId,
            enabledAudioCodecs.toList().sorted(), backupRemindersEnabled
        )
        validate(document)
        return gson.toJson(document)
    }

    /**
     * Decode schema 2, or migrate a schema 1 contacts/history backup into a
     * full-backup document with empty account/preferences sections.
     */
    fun decode(json: String): TomosFullBackupDocument {
        require(json.toByteArray(Charsets.UTF_8).size <= TomosBackupCodec.MAX_BACKUP_BYTES) {
            "Backup file is larger than 5 MB."
        }
        val root = try { JsonParser.parseString(json) } catch (_: JsonParseException) {
            throw IllegalArgumentException("This is not a valid TomOS backup file.")
        }
        require(root.isJsonObject) { "This is not a valid TomOS backup file." }
        val version = root.asJsonObject.get("schemaVersion")?.asInt
        val document = try {
            when (version) {
                1 -> {
                    val legacy = TomosBackupCodec.decode(json)
                    TomosFullBackupDocument(
                        CURRENT_SCHEMA_VERSION, legacy.createdAt, legacy.contacts, legacy.callHistory,
                        emptyList(), emptyMap(), null, emptyList(), false
                    )
                }
                CURRENT_SCHEMA_VERSION -> gson.fromJson(root, TomosFullBackupDocument::class.java)
                else -> throw IllegalArgumentException("Unsupported backup version: $version.")
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("This is not a valid TomOS backup file.")
        } ?: throw IllegalArgumentException("This is not a valid TomOS backup file.")
        validate(document)
        return document
    }

    private fun validate(document: TomosFullBackupDocument) {
        require(document.schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported backup version: ${document.schemaVersion}." }
        require(document.createdAt >= 0L) { "Backup timestamp is invalid." }
        val contacts = requireNotNull(document.contacts) { "Backup is missing its contacts list." }
        val history = requireNotNull(document.callHistory) { "Backup is missing its call history." }
        val accounts = requireNotNull(document.sipAccounts) { "Backup is missing its SIP accounts." }
        val credentials = requireNotNull(document.sipCredentials) { "Backup is missing its SIP credentials section." }
        require(contacts.size <= MAX_CONTACTS) { "Backup contains too many contacts." }
        require(history.size <= MAX_CALLS) { "Backup contains too many call records." }
        require(accounts.size <= MAX_ACCOUNTS) { "Backup contains too many SIP accounts." }
        require(contacts.all { it.id.isNotBlank() && it.name.isNotBlank() && it.number.isNotBlank() })
        require(contacts.map { it.id }.distinct().size == contacts.size) { "Backup contains duplicate contact IDs." }
        require(history.all { it.id.isNotBlank() && it.peer.isNotBlank() && it.address.isNotBlank() &&
            it.direction.isNotBlank() && it.result.isNotBlank() && it.startedAt >= 0L && it.durationSeconds >= 0L })
        require(history.map { it.id }.distinct().size == history.size) { "Backup contains duplicate call-history IDs." }
        require(accounts.map { it.id }.distinct().size == accounts.size) { "Backup contains duplicate SIP account IDs." }
        accounts.forEach { profile ->
            require(profile.id.isNotBlank() && validateSipAccount(profile) == null) {
                "Backup contains an invalid SIP account."
            }
        }
        val accountIds = accounts.map { it.id }.toSet()
        require(credentials.keys.all { it in accountIds }) { "Backup contains credentials for an unknown SIP account." }
        require(credentials.values.all { it.isNotEmpty() && it.length <= MAX_CREDENTIAL_LENGTH }) {
            "Backup contains an invalid SIP credential."
        }
        require(document.selectedSipAccountId == null || document.selectedSipAccountId in accountIds) {
            "Backup selects an unknown SIP account."
        }
        val codecs = requireNotNull(document.enabledAudioCodecs) { "Backup is missing codec preferences." }
        require(codecs.size <= 100 && codecs.all { it.isNotBlank() && it.length <= 128 }) {
            "Backup contains invalid codec preferences."
        }
        require(codecs.distinct().size == codecs.size) { "Backup contains duplicate codec preferences." }
        require(document.backupRemindersEnabled != null) { "Backup is missing reminder preferences." }
    }
}
