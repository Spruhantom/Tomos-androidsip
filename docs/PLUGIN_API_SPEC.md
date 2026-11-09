# TomOS SIP Plugin API — Initial Specification

Status: SDK prototype implemented on the plugin-sdk-prototype branch. The AIDL contracts, capability catalogue, strict manifest parser and installed-package verifier exist; the full host runtime and directory are still under development.

## 1. Goals

Provide a safe, versioned extension system for community developers while keeping SIP registration, calling and user data protected.

Plugins are independent Android applications. TomOS SIP must not download arbitrary executable code and load it into its own process.

## 2. Runtime and IPC

Initial implementation target: an explicit, bound Android service contract using Binder/AIDL. The final choice between generated AIDL and a Kotlin Binder wrapper must be confirmed with a prototype and Android-version tests.

Requirements:

- Explicit component binding; no implicit broadcasts for privileged operations.
- Binder caller UID/package identity checked by the host.
- Package signing certificate checked against the installed package and directory publisher metadata.
- Bounded request sizes, timeouts and defensive parsing.
- Plugin process isolation; a crashed or unresponsive plugin cannot block the SIP engine or main UI.
- No direct references to Linphone SDK objects across the plugin boundary.
- Stable DTOs only; do not expose internal database entities or Android framework objects unnecessarily.

## 3. Plugin manifest

The directory and installed package must agree on a signed manifest. Proposed fields:

| Field | Meaning |
|---|---|
| `plugin_id` | Reverse-DNS stable identifier |
| `display_name` | User-visible name |
| `publisher_id` | Verified community publisher |
| `version` | Plugin semantic version |
| `api_min` / `api_max` | Supported host API range |
| `min_host_version` | Minimum TomOS SIP version |
| `service_component` | Explicit IPC service component |
| `capabilities` | Requested capability identifiers |
| `artifact_sha256` | Expected package digest |
| `signing_certificate_sha256` | Expected signer certificate digest |
| `privacy_policy_url` | Publisher's privacy information |
| `source_url` | Optional public source repository |

A plugin ID is not proof of publisher identity. The host must verify the installed APK signer and directory signature.

## 4. Initial capability catalogue

Free capabilities (subject to user consent):
- `call.events.read.basic` — receive limited call lifecycle events.
- `notifications.post` — post plugin-scoped notifications.
- `contacts.read` — read contacts only when the user grants access.
- `contacts.write` — not granted by default; require explicit consent.
- `settings.read.basic` — read non-secret app preferences.

Premium capabilities (also require an active entitlement):
- `call.actions.advanced` — request supported advanced call actions.
- `call.routing.advanced` — participate in approved routing workflows.
- `automation.rules` — register bounded automation rules.
- `backup.export` / `backup.import` — request mediated backup operations.
- `codec.profiles.manage` — manage supported codec profiles through host validation.
- `crm.integrations` — use mediated CRM integration interfaces.

Capabilities are deny-by-default. Premium status never bypasses the user's per-plugin permission grant.

## 5. Security and privacy rules

- Never expose SIP passwords, auth secrets, private keys or raw account credentials.
- Never expose unrestricted Linphone Core access.
- Sensitive actions must be mediated by the host and show clear consent/context.
- Validate every IPC call, including calls from an already approved plugin.
- Bind permission grants to plugin ID, signing certificate and capability.
- Revoke grants when a plugin is uninstalled, its signer changes, or its publisher is revoked.
- Do not treat a directory listing as a security guarantee; verify package digest and signer at installation.
- Keep an auditable local record of plugin enable/disable, permission changes and update events.
- Avoid collecting call contents, audio or contact data unless a specific feature requires it and the user explicitly consents.
- Plugins must not run unrestricted background work; use bounded host-managed lifecycle callbacks.

## 6. Host API surface (conceptual)

The first SDK should expose narrow interfaces rather than internal implementation classes:

- `PluginMetadata` — validated plugin identity and compatibility.
- `PluginCapability` — a declared permission identifier.
- `PluginHostConnection` — connection lifecycle and API version.
- `CallEvent` — redacted call lifecycle event DTO.
- `CallActionRequest` — validated request for a supported host action.
- `ContactAccess` — mediated contact operations.
- `BackupRequest` — host-mediated import/export request.
- `PluginResult` — bounded success/error result.

No interface should accept or return SIP passwords, authentication tokens, Linphone SDK objects or arbitrary file-system paths.

## 7. Directory trust and community submissions

Directory entries are community-submitted and reviewed before publication.

Submission pipeline:
1. Developer registers a publisher identity and signing certificate.
2. Developer submits manifest, APK, source link, privacy policy and requested capabilities.
3. Automated checks validate schema, package ID, API range, signer, digest, dependencies and basic security signals.
4. Human review checks permissions, behaviour, privacy disclosures and policy compliance.
5. Approved version is published with review status and release history.
6. Updates repeat signature, digest, compatibility and review checks.

The directory should distinguish:
- Verified and approved.
- Community submitted / pending review (not installable by default).
- Rejected or revoked.

Provide reporting and takedown mechanisms. Never silently replace a plugin package with a different signer.

## 8. Compatibility and lifecycle

- Use semantic API versions and explicit minimum/maximum supported API levels.
- Reject incompatible plugins before binding.
- Host lifecycle callbacks must be idempotent and time-bounded.
- Plugin upgrades must preserve grants only when plugin ID and signer remain unchanged and the new manifest requests no broader capabilities.
- A broader permission request requires renewed consent.
- Host updates must not silently break older plugins; maintain documented deprecation windows.

## 9. Required tests

- Valid and invalid plugin signatures.
- Package name spoofing and signer changes.
- Directory digest mismatch and tampered manifest.
- Unsupported API and host version.
- Missing/revoked capabilities.
- Binder caller identity validation.
- Malformed, oversized and slow IPC requests.
- Plugin crash, timeout and repeated reconnect.
- Premium expiry while a plugin is enabled.
- Permission revocation during active use.
- Ensure plugins cannot access SIP credentials or call audio by default.

## 10. Implemented SDK prototype

The `:plugin-api` Android library now contains:

- Versioned AIDL interfaces for the plugin service, host callbacks and action results.
- A stable capability identifier catalogue, including Free/Premium capability classification.
- Strict manifest parsing for plugin identity, semantic version, API compatibility, minimum host version, service component, requested capabilities, APK digest and signer digest.
- Unit tests for compatibility, malformed IDs/digests, unknown/duplicate capabilities and size limits.
- A host-side installed-package verifier in the app that checks package ID, one-APK installation, signer certificate SHA-256, APK SHA-256, exported service and manifest metadata.
- Android package visibility declaration for the plugin binding action.

The verifier is a pre-bind check, not the complete trust chain. The next host milestone must implement directory-signature verification, explicit discovery and lifecycle management, Binder caller-UID checks on every callback, and per-plugin permission grants. A package must be rechecked after binding to reduce package-update/TOCTOU risk.

See `plugin-api/README.md` for the developer-facing contract and example manifest.

## 11. First sample plugins

- Free: call-event notification plugin.
- Free: read-only contact lookup plugin.
- Premium: advanced call-routing plugin.
- Premium: mediated encrypted-backup provider.

These are test fixtures until reviewed and released through the official directory.
