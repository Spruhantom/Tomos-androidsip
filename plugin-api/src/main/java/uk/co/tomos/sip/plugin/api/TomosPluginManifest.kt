package uk.co.tomos.sip.plugin.api

/** Stable plugin IPC contract identifiers. Increment only for incompatible changes. */
object TomosPluginApi {
    const val CURRENT_VERSION = 1
    const val BIND_ACTION = "uk.co.tomos.sip.action.BIND_PLUGIN"
    const val MANIFEST_METADATA_KEY = "uk.co.tomos.sip.plugin.MANIFEST"
    const val MAX_IPC_JSON_BYTES = 32 * 1024
}

enum class PluginCapability(
    val id: String,
    val requiresPremium: Boolean
) {
    CALL_EVENTS_READ_BASIC("call.events.read.basic", false),
    NOTIFICATIONS_POST("notifications.post", false),
    CONTACTS_READ("contacts.read", false),
    CONTACTS_WRITE("contacts.write", false),
    SETTINGS_READ_BASIC("settings.read.basic", false),
    CALL_ACTIONS_ADVANCED("call.actions.advanced", true),
    CALL_ROUTING_ADVANCED("call.routing.advanced", true),
    AUTOMATION_RULES("automation.rules", true),
    BACKUP_EXPORT("backup.export", true),
    BACKUP_IMPORT("backup.import", true),
    CODEC_PROFILES_MANAGE("codec.profiles.manage", true),
    CRM_INTEGRATIONS("crm.integrations", true);

    companion object {
        fun fromId(id: String): PluginCapability? = entries.firstOrNull { it.id == id }
    }
}

/** Validated metadata; it does not itself grant any requested capability. */
data class TomosPluginManifest(
    val pluginId: String,
    val displayName: String,
    val publisherId: String,
    val version: String,
    val apiMin: Int,
    val apiMax: Int,
    val minHostVersion: Int,
    val serviceComponent: String,
    val capabilities: Set<PluginCapability>,
    val artifactSha256: String,
    val signingCertificateSha256: String,
    val privacyPolicyUrl: String?,
    val sourceUrl: String?
) {
    val requestsPremiumCapabilities: Boolean
        get() = capabilities.any(PluginCapability::requiresPremium)
}

/**
 * Strict parser for a directory-signed plugin manifest.
 *
 * This validates structure and compatibility only. The host must separately
 * verify the directory signature, installed APK digest and package signer.
 */
object TomosPluginManifestParser {
    private val pluginIdPattern = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_-]*){2,}$")
    private val semanticVersionPattern = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:[-+][0-9A-Za-z.-]+)?$")
    private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")

    fun parse(
        json: String,
        hostApiVersion: Int = TomosPluginApi.CURRENT_VERSION,
        hostVersionCode: Int = 1
    ): TomosPluginManifest {
        require(json.toByteArray(Charsets.UTF_8).size <= TomosPluginApi.MAX_IPC_JSON_BYTES) {
            "Plugin manifest exceeds the size limit."
        }
        require(hostApiVersion > 0 && hostVersionCode > 0)
        val root = org.json.JSONObject(json)
        val pluginId = root.getString("plugin_id")
        val displayName = root.getString("display_name")
        val publisherId = root.getString("publisher_id")
        val version = root.getString("version")
        val apiMin = root.getInt("api_min")
        val apiMax = root.getInt("api_max")
        val minHostVersion = root.getInt("min_host_version")
        val serviceComponent = root.getString("service_component")
        val artifactDigest = root.getString("artifact_sha256")
        val signingDigest = root.getString("signing_certificate_sha256")

        require(pluginIdPattern.matches(pluginId)) { "Invalid reverse-DNS plugin ID." }
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(publisherId.matches(Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{2,63}$")))
        require(semanticVersionPattern.matches(version)) { "Plugin version must use semantic versioning." }
        require(apiMin > 0 && apiMax >= apiMin)
        require(hostApiVersion in apiMin..apiMax) { "Plugin does not support this host API version." }
        require(minHostVersion > 0 && minHostVersion <= hostVersionCode) {
            "Plugin requires a newer TomOS SIP host version."
        }
        require(serviceComponent.matches(Regex("^[A-Za-z_][A-Za-z0-9_.]*$")))

        require(sha256Pattern.matches(artifactDigest))
        require(sha256Pattern.matches(signingDigest))

        val requested = root.getJSONArray("capabilities")
        require(requested.length() in 1..32)
        val capabilities = (0 until requested.length()).map { index ->
            val id = requested.getString(index)
            PluginCapability.fromId(id)
                ?: throw IllegalArgumentException("Plugin requests an unknown capability: $id")
        }
        require(capabilities.toSet().size == capabilities.size) {
            "Plugin manifest contains duplicate capabilities."
        }

        val privacyUrl = root.optString("privacy_policy_url")
            .takeIf { it.isNotBlank() && it != "null" }
        val sourceUrl = root.optString("source_url")
            .takeIf { it.isNotBlank() && it != "null" }
        require(privacyUrl == null || privacyUrl.startsWith("https://"))
        require(sourceUrl == null || sourceUrl.startsWith("https://"))

        return TomosPluginManifest(
            pluginId = pluginId,
            displayName = displayName,
            publisherId = publisherId,
            version = version,
            apiMin = apiMin,
            apiMax = apiMax,
            minHostVersion = minHostVersion,
            serviceComponent = serviceComponent,
            capabilities = capabilities.toSet(),
            artifactSha256 = artifactDigest.lowercase(),
            signingCertificateSha256 = signingDigest.lowercase(),
            privacyPolicyUrl = privacyUrl,
            sourceUrl = sourceUrl
        )
    }
}
