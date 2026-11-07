package uk.co.tomos.sip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists non-secret account metadata separately from SipCredentialStore.
 * Passwords are never serialized into the account profile document.
 */
class SipAccountProfileStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("tomos_sip_account_profiles", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<SipAccountProfile> {
        val encoded = preferences.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val json = JSONArray(encoded)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                val transport = runCatching {
                    SipTransport.valueOf(item.optString("transport", SipTransport.UDP.name))
                }.getOrDefault(SipTransport.UDP)
                val profile = SipAccountProfile(
                    id = item.optString("id"),
                    label = item.optString("label"),
                    username = item.optString("username"),
                    authUsername = item.optString("authUsername"),
                    domain = item.optString("domain"),
                    transport = transport,
                    port = item.optInt("port", 5060),
                    displayName = item.optString("displayName"),
                    expirySeconds = item.optInt("expirySeconds", 3600),
                    enabled = item.optBoolean("enabled", true)
                )
                if (profile.id.isBlank() || validateSipAccount(profile) != null) null else profile
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(profiles: List<SipAccountProfile>) {
        require(profiles.map { it.id }.distinct().size == profiles.size) {
            "SIP account IDs must be unique."
        }
        profiles.forEach { profile ->
            require(validateSipAccount(profile) == null) {
                validateSipAccount(profile) ?: "Invalid SIP account."
            }
        }
        val json = JSONArray()
        profiles.forEach { profile ->
            json.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("label", profile.label)
                    .put("username", profile.username)
                    .put("authUsername", profile.authUsername)
                    .put("domain", profile.domain)
                    .put("transport", profile.transport.name)
                    .put("port", profile.port)
                    .put("displayName", profile.displayName)
                    .put("expirySeconds", profile.expirySeconds)
                    .put("enabled", profile.enabled)
            )
        }
        preferences.edit().putString(KEY_PROFILES, json.toString()).apply()
    }

    fun selectedAccountId(): String? = preferences.getString(KEY_SELECTED_ACCOUNT, null)

    fun selectAccount(accountId: String?) {
        preferences.edit().putString(KEY_SELECTED_ACCOUNT, accountId).apply()
    }

    companion object {
        private const val KEY_PROFILES = "profiles_v1"
        private const val KEY_SELECTED_ACCOUNT = "selected_account_id"
    }
}
