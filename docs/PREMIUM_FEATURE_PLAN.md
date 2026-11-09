# TomOS SIP Free and Premium Development Plan

## Confirmed product decisions

- One Android application with Free and Premium entitlements.
- Premium is subscription-based, with monthly and yearly billing options.
- Distribution through both Google Play and direct GitHub APK releases.
- Users upgrade inside the installed application; Free and Premium are not separate package identities.
- Free supports one SIP account.
- Plugins are available to Free and Premium users, with capability tiers.
- Plugins are discovered through the in-app official directory.
- Community developers may submit plugins; directory publication requires review and verification.
- Premium entitlements are associated with a TomOS account so access can be restored across supported distribution channels and devices.

## Product principles

- Core SIP calling, registration reliability, contacts, call history, call controls and access to personal data remain usable in Free.
- Free is limited to one configured SIP account. Multiple accounts are Premium.
- Do not advertise or gate a feature until its implementation, entitlement enforcement and tests are complete.
- Subscription expiry or verification failure must never delete or corrupt user data.
- Entitlement checks must be enforced in domain/service operations, not only by hiding UI.
- Do not embed payment secrets, signing secrets or permanent Premium flags in the APK.
- Keep payment providers replaceable behind a common entitlement interface.

## Editions

### Free

- One SIP account.
- SIP registration and calling over supported UDP, TCP and TLS transports.
- Incoming/outgoing calls, dial pad, mute, speaker, hold/resume and incoming-call notifications.
- Contacts and call history.
- Essential diagnostics and network recovery.
- Basic backup and restore.
- Responsive phone, tablet and foldable layouts.
- Free plugins and explicitly permitted plugin capabilities.

### Premium

Includes Free, plus the first planned paid capabilities:

- Multiple SIP accounts.
- Advanced codec controls and per-account codec profiles.
- Encrypted backup and restore.
- Premium plugin capabilities and Premium-only integrations.

Further advanced call management, automation and diagnostics are candidates for later releases, not launch promises.

## Branch and source strategy

- `main`: Free production release line. It contains shared application code and the Free experience; Premium code may be present but all Premium capabilities remain entitlement-gated.
- `premium`: Premium integration/release line. It carries Premium-specific integration and release work while sharing the same application ID, package namespace and signing identity.
- Feature branches are used for individual changes and reviewed through pull requests.
- Shared fixes are merged into `main` first and then forward-ported to `premium`.
- Premium-only work must not be merged back to `main` without review.
- Do not maintain two divergent copies of the application source or use separate application IDs.
- CI must test both `main` and `premium`; release builds must use a stable, protected signing identity.

## Subscription and account entitlement architecture

A TomOS account is the cross-channel identity. A backend entitlement service is the source of truth.

- Google Play purchases are verified server-side using the supported Play purchase verification flow and processed idempotently.
- GitHub-distributed builds use a hosted checkout/payment provider and verified payment webhooks.
- Both purchase adapters grant the same product entitlement to the signed-in TomOS account.
- The app retrieves a signed, time-bounded entitlement snapshot from the backend.
- Entitlement states include loading, Free, active monthly, active yearly, grace period, expired, refunded/revoked and verification unavailable.
- The app caches only a verifiable, time-limited entitlement snapshot for defined offline operation.
- Account sign-in, restore purchases, subscription management and sign-out must be accessible.
- Never trust a client-side boolean or an unverified purchase callback.
- Keep payment card data outside the app and entitlement backend.
- Refunds, cancellations, billing retries, grace periods, account recovery, device limits and support workflows require explicit backend rules before launch.

## Plugin platform

- Plugins are separately installed Android applications, not downloaded DEX/JAR code executed inside TomOS SIP.
- Plugins communicate through an explicitly versioned Android IPC contract (initial implementation should evaluate bound services with Binder/AIDL).
- Every plugin declares identity, publisher, version, compatible API range, minimum host version and requested capabilities.
- The host verifies package signing identity, compatibility and directory approval before enabling a plugin.
- Users grant and revoke narrow capabilities per plugin.
- Plugins never receive SIP passwords, account secrets or unrestricted access to the Linphone engine.
- Call control, contact writes, backup export/import and other sensitive operations require dedicated capabilities and explicit user-facing consent.
- Plugin failures must not crash or block SIP calling.
- Free users can install compatible Free plugins; Premium plugins and premium capabilities require an active entitlement.
- The official directory is community-submitted but curated: submissions undergo automated checks and human review before being marked verified/listed.
- Directory metadata and APK downloads must be signed or integrity-checked; the app must verify package signing identity and SHA-256 before installation.

## Distribution and release strategy

- Google Play and GitHub builds share application ID, versioning policy and release signing identity.
- Google Play builds use the Play-supported billing integration and follow current Play policy.
- GitHub builds use the direct-distribution checkout adapter; do not assume Play Billing is available in a sideloaded build.
- Both channels must point to the same TomOS account entitlement service.
- APK updates require stable signing continuity; do not publish production updates from ephemeral debug signing.
- CI creates test APKs/artifacts, but production publishing requires protected signing and release approval.
- Release notes identify the channel and supported update path.
- Verify current Google Play billing, user-choice and external-offer requirements before enabling production checkout links in Play builds.

## Milestones and acceptance criteria

### 0. Architecture foundation
- [x] Confirm product, pricing, channel and plugin decisions.
- [x] Create `premium` branch from current `main`.
- [ ] Merge reviewed architecture documentation.
- [ ] Define branch protection and CI matrix.

### 1. Feature entitlement foundation
- [ ] Audit existing Premium licence code and replace lifetime/offline assumptions with account-backed subscription entitlements.
- [ ] Define typed feature catalogue and entitlement states.
- [ ] Add domain-level guards for multiple accounts, advanced codecs and encrypted backups.
- [ ] Add tests for Free, active monthly/yearly, grace, expiry, refund/revocation, offline and verification failures.
- [ ] Verify Free remains fully usable and preserves all user data.

### 2. Account and subscription backend
- [ ] Define TomOS account identity and secure sign-in/session lifecycle.
- [ ] Define backend entitlement API and signed response format.
- [ ] Implement Google Play purchase verification adapter.
- [ ] Implement GitHub/direct checkout and payment webhook adapter.
- [ ] Add restore, cancellation, renewal, grace, refund and account recovery handling.
- [ ] Complete threat modelling, privacy review and operational monitoring.

### 3. Plugin SDK and host
- [ ] Publish versioned Kotlin SDK and Android IPC contract.
- [ ] Define plugin manifest and capability catalogue.
- [ ] Implement package discovery, signature verification and compatibility checks.
- [ ] Implement permission consent, revocation, lifecycle and failure isolation.
- [ ] Build sample Free and Premium plugins.
- [ ] Add automated host/plugin compatibility and security tests.

### 4. Community directory
- [ ] Create directory metadata schema and submission workflow.
- [ ] Add automated static checks, signature validation and malware scanning process.
- [ ] Add human review, publisher identity and verified/unverified status.
- [ ] Implement in-app browse, details, install, update and uninstall flows.
- [ ] Provide reporting, takedown and security-response procedures.

### 5. Release readiness
- [ ] Stable release signing and key custody.
- [ ] CI matrix for `main` and `premium`.
- [ ] Google Play and GitHub release pipelines.
- [ ] Migration, restore and update tests across distribution channels.
- [ ] Real-device SIP, audio, incoming-call and plugin isolation tests.
- [ ] Publish user, privacy, subscription and developer documentation.

## Current implementation status

This document is an architecture baseline, not a claim that subscription billing, a TomOS account backend, the plugin runtime or the directory already exist. Existing lifetime/offline activation code and the previous single-paid-app PR must be reviewed and replaced only in dedicated, tested changes. Keep PR #37 unmerged because its product model conflicts with the confirmed Free/Premium subscription model.

## Important commercial dependency

The repository documents the Linphone SDK as GPLv3. Before distributing commercial Google Play or GitHub builds, complete a licence and source-distribution compliance review for the exact SDK version and all bundled dependencies. This is a release gate, not a substitute for legal advice.
