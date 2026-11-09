# TomOS SIP Distribution and Release Contract

## Channels

### Google Play

- Build and sign the Play distribution using the stable release identity and Play's supported app-signing workflow.
- Use the Google Play billing adapter for subscriptions in Play-distributed builds, subject to current Play policy and eligible programme rules.
- Upload Android App Bundles where required by the Play release process.
- Use Play-managed staged rollout, internal testing and release tracks.
- Do not make unsupported external checkout promises inside Play-distributed builds.

### GitHub direct APK

- Publish signed APKs and SHA-256 checksums as GitHub Releases.
- Use the direct checkout/payment adapter for monthly and yearly Premium subscriptions.
- Sign with the same app signing identity required for compatible updates across supported channels.
- Provide release notes, minimum supported version and migration notes.
- Do not publish production APKs signed with ephemeral debug keys.

## Shared identity

- One application ID: `uk.co.tomos.sip`.
- One stable release signing identity; protect the private key outside source control and CI logs.
- Same TomOS account and entitlement backend across both channels.
- Keep channel-specific billing implementation behind a common billing/entitlement interface.
- Test update compatibility and purchase restoration before claiming cross-channel continuity.

## CI and release gates

- Run Kotlin unit tests and Android build checks on `main`, `premium` and pull requests.
- Build debug artifacts for CI without production signing secrets.
- Run release build validation with protected signing configuration in a controlled release workflow.
- Require successful tests, code review, dependency/licence review and release approval.
- Verify APK/AAB signature, package ID, version code, checksums and dependency inventory.
- Do not automatically publish production releases from arbitrary feature branches.

## Release checklist

- Confirm Play policy and billing implementation for the current release.
- Confirm GitHub checkout/webhook and entitlement verification.
- Confirm signing identity and update path.
- Confirm account sign-in, restore and subscription status UX.
- Confirm Free behaviour after expiry, refund and offline verification failure.
- Confirm plugin directory trust metadata and plugin signer validation.
- Confirm privacy disclosures, permissions and SDK/dependency licence obligations.
- Test on physical devices and supported Android versions.
