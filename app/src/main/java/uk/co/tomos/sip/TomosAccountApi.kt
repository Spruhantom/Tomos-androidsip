package uk.co.tomos.sip

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** Narrow API boundary so tests and future account providers can be injected. */
interface TomosAccountApi {
    fun fetchEntitlement(session: TomosAccountSession): SignedEntitlementEnvelope
    fun refreshSession(refreshToken: String): TomosAccountSession
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
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                // Do not include response bodies: providers may echo sensitive identifiers.
                throw IOException("TomOS account service returned HTTP $status.")
            }
            require(response.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE_BYTES)
            return response
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
