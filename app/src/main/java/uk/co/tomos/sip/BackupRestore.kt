package uk.co.tomos.sip

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** Versioned, password-free backup containing only user contacts and call history. */
data class TomosBackupDocument(
    val schemaVersion: Int,
    val createdAt: Long,
    val contacts: List<SipContact>?,
    val callHistory: List<SipCallRecord>?
)

internal object TomosBackupCodec {
    const val CURRENT_SCHEMA_VERSION = 1
    const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
    private const val MAX_CONTACTS = 5_000
    private const val MAX_CALLS = 10_000
    private val gson = Gson()

    fun encode(contacts: List<SipContact>, history: List<SipCallRecord>, createdAt: Long = System.currentTimeMillis()): String {
        require(createdAt >= 0L) { "Backup timestamp is invalid." }
        require(contacts.size <= MAX_CONTACTS) { "Too many contacts to back up." }
        require(history.size <= MAX_CALLS) { "Too many call records to back up." }
        val document = TomosBackupDocument(CURRENT_SCHEMA_VERSION, createdAt, contacts.toList(), history.toList())
        validate(document)
        return gson.toJson(document)
    }

    fun decode(json: String): TomosBackupDocument {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "Backup file is larger than 5 MB." }
        val root = try { JsonParser.parseString(json) } catch (_: JsonParseException) {
            throw IllegalArgumentException("This is not a valid TomOS backup file.")
        }
        require(root.isJsonObject) { "This is not a valid TomOS backup file." }
        val document = try { gson.fromJson(root, TomosBackupDocument::class.java) } catch (_: Exception) {
            throw IllegalArgumentException("This is not a valid TomOS backup file.")
        } ?: throw IllegalArgumentException("This is not a valid TomOS backup file.")
        validate(document)
        return document
    }

    private fun validate(document: TomosBackupDocument) {
        require(document.schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported backup version: ${document.schemaVersion}."
        }
        require(document.createdAt >= 0L) { "Backup timestamp is invalid." }
        val contacts = document.contacts ?: throw IllegalArgumentException("Backup is missing its contacts list.")
        val history = document.callHistory ?: throw IllegalArgumentException("Backup is missing its call history.")
        require(contacts.size <= MAX_CONTACTS) { "Backup contains too many contacts." }
        require(history.size <= MAX_CALLS) { "Backup contains too many call records." }
        require(contacts.all { it.id.isNotBlank() && it.name.isNotBlank() && it.number.isNotBlank() }) {
            "Backup contains an invalid contact."
        }
        require(contacts.map { it.id }.distinct().size == contacts.size) { "Backup contains duplicate contact IDs." }
        require(history.all {
            it.id.isNotBlank() && it.peer.isNotBlank() && it.address.isNotBlank() &&
                it.direction.isNotBlank() && it.result.isNotBlank() &&
                it.startedAt >= 0L && it.durationSeconds >= 0L
        }) { "Backup contains an invalid call-history record." }
        require(history.map { it.id }.distinct().size == history.size) { "Backup contains duplicate call-history IDs." }
    }
}
