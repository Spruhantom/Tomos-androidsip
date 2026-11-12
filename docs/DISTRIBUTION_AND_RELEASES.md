# TomOS SIP Distribution and Release Contract

## Product model

TomOS SIP is one completely free application. All calling, account, codec, backup and supported plugin features are included for every user. There are no paid tiers, subscriptions, in-app purchases, licence keys, billing adapters or entitlement services.

## Distribution channels

### Google Play
- Distribute through the normal Android app release process.
- Use Play App Signing and a stable release identity for compatible updates.
- Use internal testing and staged rollout before production releases.
- Do not add billing or purchase flows to the app.

### GitHub direct APK
- Publish APKs and SHA-256 checksums as GitHub Releases.
- Provide release notes, minimum supported version and migration notes.
- Use the same app signing identity required for compatible updates across supported channels.
- Do not publish production APKs signed with ephemeral debug keys when a stable signing identity is available.

## Shared identity

- One application ID: `uk.co.tomos.sip`.
- One stable release signing identity; protect the private key outside source control and CI logs.
- No account, payment, subscription or entitlement backend is required for core app use.

## CI and release gates

- Run Kotlin unit tests and Android build checks on `main` and pull requests.
- Build debug artifacts for CI without production signing secrets.
- Require successful tests, code review, dependency/licence review and release approval.
- Verify APK signature, package ID, version code, checksums and dependency inventory.
- Do not automatically publish production releases from arbitrary feature branches.

## Release checklist

- Confirm signing identity and update path.
- Confirm APK installation and update behaviour.
- Confirm plugin directory trust metadata, package signer validation and per-plugin user permissions.
- Confirm privacy disclosures, permissions and SDK/dependency licence obligations.
- Test on physical devices and supported Android versions.
