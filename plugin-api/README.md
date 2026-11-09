# TomOS SIP Plugin API

This Android library is the initial, versioned SDK contract for third-party TomOS SIP plugins.

## Runtime model

Plugins are separate Android applications installed by the user. They expose the AIDL service in this library and communicate with TomOS SIP through Android Binder IPC. The host never loads plugin DEX/JAR files into the SIP process.

The current API version is `1`.

## Implementing a plugin

Add the SDK as a dependency (the module path below is for this repository's development build; a versioned Maven artifact will be published after the API stabilises):

```kotlin
dependencies {
    implementation(project(":plugin-api"))
}
```

Implement the generated `ITomosSipPlugin.Stub` in an exported Android service. The service must advertise `TomosPluginApi.BIND_ACTION` and include the manifest metadata key `TomosPluginApi.MANIFEST_METADATA_KEY`. The metadata value must reference the plugin's reviewed manifest resource.

A plugin manifest must declare:

- Reverse-DNS package/plugin ID and publisher ID.
- Semantic version and supported plugin API range.
- Minimum TomOS SIP host version.
- Fully qualified exported service component.
- Requested capabilities from the SDK catalogue.
- SHA-256 digest of the submitted APK and signing certificate.
- HTTPS privacy policy URL and optional HTTPS source URL.

Example manifest JSON:

```json
{
  "plugin_id": "uk.co.tomos.plugin.callnotifier",
  "display_name": "Call Notifier",
  "publisher_id": "tomos-community",
  "version": "1.0.0",
  "api_min": 1,
  "api_max": 1,
  "min_host_version": 1,
  "service_component": "uk.co.tomos.plugin.callnotifier.CallNotifierService",
  "capabilities": [
    "call.events.read.basic",
    "notifications.post"
  ],
  "artifact_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "signing_certificate_sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "privacy_policy_url": "https://example.org/privacy",
  "source_url": "https://github.com/example/call-notifier"
}
```

The digests above are placeholders and must be replaced with the actual build artifact and signer certificate hashes.

## Security requirements

- A manifest is a request, not a permission grant.
- The host checks each capability against the user's grant and current Premium entitlement.
- The host verifies the official directory signature, installed package ID, APK digest, signing certificate, exported service and manifest metadata before binding.
- The host validates Binder caller UID on every callback; it must not trust a package name supplied in JSON.
- Plugins receive no SIP passwords, account credentials, raw Linphone objects, call audio or unrestricted filesystem access.
- Call actions, contact writes and backup operations must be mediated by host APIs.
- IPC JSON is size-limited and must be parsed defensively.
- A plugin failure must not block the SIP engine or main UI.

## Current scope

The SDK includes AIDL contracts, a stable capability catalogue, strict manifest parsing and compatibility tests. The host-side package verifier checks the installed single-APK digest and signer before binding.

The following are not yet implemented: directory signature/key rotation, complete plugin discovery and lifecycle manager, Binder callback UID enforcement in the running host, permission-grant UI, sample plugin APK and public Maven publication. Do not describe the plugin platform as production-ready until those pieces and real-device isolation tests are complete.
