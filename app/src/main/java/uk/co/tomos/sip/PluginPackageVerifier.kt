package uk.co.tomos.sip

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import uk.co.tomos.sip.plugin.api.TomosPluginApi
import uk.co.tomos.sip.plugin.api.TomosPluginManifest
import java.io.FileInputStream
import java.security.MessageDigest

data class VerifiedPluginPackage(
    val packageName: String,
    val uid: Int,
    val serviceComponent: String,
    val signingCertificateSha256: String,
    val artifactSha256: String
)

/**
 * Checks an installed plugin package against its reviewed directory manifest.
 *
 * This does not load plugin code. It verifies package identity, the single APK
 * digest, signing certificate and exported service declaration before the host
 * is allowed to bind to the plugin's isolated Android process.
 */
class PluginPackageVerifier(private val packageManager: PackageManager) {

    fun verifyInstalledPlugin(
        packageName: String,
        manifest: TomosPluginManifest
    ): VerifiedPluginPackage? = runCatching {
        require(packageName == manifest.pluginId) { "Plugin package ID does not match its manifest." }
        val info = getPackageInfo(packageName)
        require(info.splitNames.isNullOrEmpty()) {
            "Split APK plugins are not supported by this initial verifier."
        }

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: throw SecurityException("Plugin signing certificate is unavailable.")
        require(signatures.size == 1) { "Plugin must use exactly one signing certificate." }

        val certificateDigest = sha256(signatures.single().toByteArray())
        require(certificateDigest.equals(manifest.signingCertificateSha256, ignoreCase = true)) {
            "Installed plugin signer does not match the reviewed publisher certificate."
        }

        val appInfo = info.applicationInfo
            ?: throw SecurityException("Installed plugin application info is unavailable.")
        val apkPath = appInfo.sourceDir
        val artifactDigest = FileInputStream(apkPath).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().toHex()
        }
        require(artifactDigest.equals(manifest.artifactSha256, ignoreCase = true)) {
            "Installed plugin APK does not match the reviewed artifact digest."
        }

        val intent = Intent(TomosPluginApi.BIND_ACTION).setPackage(packageName)
        val services = queryPluginServices(intent)
        val service = services.mapNotNull { it.serviceInfo }.singleOrNull {
            it.packageName == packageName && it.name == manifest.serviceComponent
        } ?: throw SecurityException("The declared plugin service was not found.")
        require(service.exported) { "Plugin service must be exported for host IPC." }
        require(service.permission.isNullOrBlank()) {
            "Plugin service must not require an undeclared custom binding permission."
        }
        require(service.metaData?.containsKey(TomosPluginApi.MANIFEST_METADATA_KEY) == true) {
            "Plugin service is missing its manifest metadata declaration."
        }
        val serviceAppInfo = service.applicationInfo
            ?: throw SecurityException("Plugin service application info is unavailable.")
        require(serviceAppInfo.uid == appInfo.uid)

        VerifiedPluginPackage(
            packageName = packageName,
            uid = appInfo.uid,
            serviceComponent = service.name,
            signingCertificateSha256 = certificateDigest,
            artifactSha256 = artifactDigest
        )
    }.getOrNull()

    private fun getPackageInfo(packageName: String): PackageInfo {
        val signatureFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val flags = signatureFlag or PackageManager.GET_META_DATA
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
    }

    private fun queryPluginServices(intent: Intent): List<android.content.pm.ResolveInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
