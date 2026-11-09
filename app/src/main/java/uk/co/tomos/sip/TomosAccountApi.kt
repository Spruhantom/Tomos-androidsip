package uk.co.tomos.sip

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** Narrow API boundary so tests and future account providers can be injected. */
interface TomosAccountApi {
    fun signIn(email: String, password: CharArray): TomosAccountSession
    fun fetchEntitlement(session: TomosAccountSession): SignedEntitlementEnvelope
    fun refreshSession(refreshToken: String): TomosAccountSession
    fun revokeSession(refreshToken: String)
}

/**
 * HTTPS REST client for the TomOS account service.
 *
 * This client deliberately has no hard-coded production host. The deployment
 * base URL must be supplied by the app's trusted build configuration.
 */
class HttpTomosAccountApi(
    private val baseUrl: String,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000
) : TomosAccountApi {
    init {
        require(baseUrl.startsWith("https://")) { "TomOS account API must use HTTPS." }
        require(baseUrl.endsWith("/"))
        require(connectTimeoutMillis in 1_000..60_000)
        require(readTimeoutMillis in 1_000..60_000)
    }

    override fun signIn(email: String, password: CharArray): TomosAccountSession {
        require(email.isNotBlank() && password.isNotEmpty())
        val passwordValue = String(password)
        return try {
            val response = JSONObject(
                request(
                    path = "v1/auth/login",
                    method = "POST",
                    body = JSONObject().put("email", email.trim()).put("password", passwordValue).toString()
                )
            )
            TomosAccountSession(
                accountId = response.getString("account_id"),
                accessToken = response.getString("access_token"),
                refreshToken = response.getString("refresh_token"),
                accessTokenExpiresAtEpochMillis = response.getLong("access_token_expires_at")
            )
        } finally {
            password.fill('\u0000')
        }
    }

    override fun revokeSession(refreshToken: String) {
        require(refreshToken.isNotBlank())
        request(
            path = "v1/auth/logout",
            method = "POST",
            body = JSONObject().put("refresh_token", refreshToken).toString()
        )
    }

    override fun fetchEntitlement(session: TomosAccountSession): SignedEntitlementEnvelope {
        val response = request(
            path = "v1/me/entitlements",
            method = "GET",
            bearerToken = session.accessToken
        )
        return SignedEntitlementEnvelope(response)
    }

    override fun refreshSession(refreshToken: String): TomosAccountSession {
        require(refreshToken.isNotBlank())
        val response = JSONObject(
            request(
                path = "v1/auth/refresh",
                method = "POST",
                body = JSONObject().put("refresh_token", refreshToken).toString()
            )
        )
        return TomosAccountSession(
            accountId = response.getString("account_id"),
            accessToken = response.getString("access_token"),
            refreshToken = response.getString("refresh_token"),
            accessTokenExpiresAtEpochMillis = response.getLong("access_token_expires_at")
        )
    }

    private fun request(
        path: String,
        method: String,
        bearerToken: String? = null,
        body: String? = null
    ): String {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "TomOS-SIP-Android")
            if (bearerToken != null) connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            if (status !in 200..299) {
                // Do not include response bodies: providers may echo sensitive identifiers.
                throw IOException("TomOS account service returned HTTP $status.")
            }
            val input = stream ?: throw IOException("TomOS account service returned an empty response.")
            val output = java.io.ByteArrayOutputStream()
            input.use {
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw IOException("TomOS account service response exceeded the size limit.")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString("UTF-8")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
