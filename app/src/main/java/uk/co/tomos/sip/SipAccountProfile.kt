package uk.co.tomos.sip

import java.util.UUID

/**
 * A locally configured SIP identity. Passwords are deliberately not part of this
 * metadata model; credentials must be held by the platform-backed credential
 * store before accounts are persisted across process restarts.
 */
data class SipAccountProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val username: String,
    val authUsername: String,
    val domain: String,
    val transport: SipTransport = SipTransport.UDP,
    val port: Int = 5060,
    val displayName: String = username,
    val expirySeconds: Int = 3600,
    val enabled: Boolean = true
)

enum class SipTransport { UDP, TCP, TLS }

data class SipAccountRegistration(
    val accountId: String,
    val state: String,
    val message: String = ""
)

internal fun validateSipAccount(profile: SipAccountProfile): String? {
    if (profile.label.isBlank()) return "Enter an account label."
    if (profile.username.isBlank()) return "Enter the SIP extension or identity."
    if (profile.authUsername.isBlank()) return "Enter the authentication username."
    if (profile.domain.isBlank()) return "Enter the SIP server."
    if (profile.port !in 1..65535) return "SIP port must be between 1 and 65535."
    if (profile.expirySeconds !in 60..86400) return "Registration expiry must be between 60 and 86400 seconds."
    if (profile.domain.any { it.isWhitespace() || it == '/' || it == '@' }) return "Enter a valid SIP server hostname."
    return null
}
