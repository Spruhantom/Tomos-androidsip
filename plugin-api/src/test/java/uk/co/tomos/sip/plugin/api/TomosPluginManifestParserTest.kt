package uk.co.tomos.sip.plugin.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TomosPluginManifestParserTest {
    private fun manifest(
        apiMin: Int = 1,
        apiMax: Int = 1,
        capabilities: List<String> = listOf("call.events.read.basic"),
        pluginId: String = "uk.co.tomos.plugin.sample",
        artifactSha: String = "a".repeat(64),
        signerSha: String = "b".repeat(64),
        minHostVersion: Int = 1
    ) = JSONObject()
        .put("plugin_id", pluginId)
        .put("display_name", "Sample plugin")
        .put("publisher_id", "sample-publisher")
        .put("version", "1.2.3")
        .put("api_min", apiMin)
        .put("api_max", apiMax)
        .put("min_host_version", minHostVersion)
        .put("service_component", "uk.co.tomos.sample.SamplePluginService")
        .put("capabilities", JSONArray(capabilities))
        .put("artifact_sha256", artifactSha)
        .put("signing_certificate_sha256", signerSha)
        .put("privacy_policy_url", "https://example.org/privacy")
        .toString()

    @Test
    fun parsesCompatibleFreePluginManifest() {
        val parsed = TomosPluginManifestParser.parse(manifest())
        assertEquals("uk.co.tomos.plugin.sample", parsed.pluginId)
        assertFalse(parsed.requestsPremiumCapabilities)
        assertEquals(setOf(PluginCapability.CALL_EVENTS_READ_BASIC), parsed.capabilities)
    }

    @Test
    fun identifiesPremiumCapabilities() {
        val parsed = TomosPluginManifestParser.parse(
            manifest(capabilities = listOf("call.events.read.basic", "call.routing.advanced"))
        )
        assertTrue(parsed.requestsPremiumCapabilities)
    }

    @Test
    fun rejectsIncompatibleApiRangeAndNewerHostRequirement() {
        assertTrue(runCatching {
            TomosPluginManifestParser.parse(manifest(apiMin = 2, apiMax = 3))
        }.isFailure)
        assertTrue(runCatching {
            TomosPluginManifestParser.parse(manifest(minHostVersion = 2), hostVersionCode = 1)
        }.isFailure)
    }

    @Test
    fun rejectsUnknownAndDuplicateCapabilities() {
        assertTrue(runCatching {
            TomosPluginManifestParser.parse(manifest(capabilities = listOf("arbitrary.exec")))
        }.isFailure)
        assertTrue(runCatching {
            TomosPluginManifestParser.parse(manifest(
                capabilities = listOf("call.events.read.basic", "call.events.read.basic")
            ))
        }.isFailure)
    }

    @Test
    fun rejectsMalformedPluginIdentityAndDigests() {
        assertTrue(runCatching {
            TomosPluginManifestParser.parse(manifest(pluginId = "sample"))
        }.isFailure)
        assertTrue(runCatching {
            TomosPluginManifestParser.parse(manifest(artifactSha = "not-a-digest"))
        }.isFailure)
        assertTrue(runCatching {
            TomosPluginManifestParser.parse(manifest(signerSha = "bad"))
        }.isFailure)
    }

    @Test
    fun rejectsManifestOverSizeLimit() {
        val huge = manifest() + " ".repeat(TomosPluginApi.MAX_IPC_JSON_BYTES)
        assertTrue(runCatching { TomosPluginManifestParser.parse(huge) }.isFailure)
    }
}
