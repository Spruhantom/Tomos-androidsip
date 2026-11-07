# TomOS SIP Premium Feature Plan

## Goal

Introduce an optional Premium tier without making the basic SIP softphone unreliable or taking away core calling features. Premium access must be explicit, testable, revocable and recoverable when a customer changes device.

This document is a proposal. No features are paywalled and no payment or licence provider is configured by this change.

## Recommended product tiers

### Free — dependable SIP softphone

Keep these available without a subscription:

- SIP registration over UDP, TCP and TLS.
- One SIP account.
- Dialler, outgoing and incoming calls.
- Mute, speakerphone, hold and resume.
- Incoming-call notifications and background registration.
- Contacts and call history.
- Essential SIP connection diagnostics and clear error messages.
- Basic backup/export and restore, so users can retain and move their own data.
- Responsive phone, tablet and foldable layouts.

Core calling, security transports, emergency/reliability behaviour and access to personal data should not be used as pressure points for an upgrade.

### Premium — advanced power-user tools

Proposed candidates, subject to user approval:

- Multiple simultaneously configured SIP accounts (Free remains limited to one).
- Advanced audio codec selection and per-account codec profiles.
- Encrypted backup and restore with password protection.
- Scheduled/automated backup reminders and additional backup workflow options.
- Advanced call-management features when implemented and tested (for example attended transfer, call recording only if legally and technically appropriate, and voicemail integration).
- Advanced diagnostics/exportable support bundle.

Do not advertise features as Premium until they exist, are tested and have a defined support policy. Do not put the responsive foldable layout itself behind Premium.

## Entitlement design

Use a central feature catalogue rather than scattering checks through Compose screens.

Suggested feature identifiers:

- `multiple_sip_accounts`
- `advanced_codec_controls`
- `encrypted_backup`
- `scheduled_backup_tools`
- `advanced_call_management`
- `support_diagnostics_export`

Suggested tiers: `FREE` and `PREMIUM`.

The app should ask an `EntitlementRepository` whether a feature is available. UI visibility is not security: every operation that consumes a Premium capability must also enforce the entitlement in its domain/service layer.

Represent entitlement state explicitly, for example:

- Loading / unknown
- Free
- Premium active (with expiry and source)
- Expired
- Offline grace period
- Verification error

Never treat a missing response, parsing failure, clock change or network outage as proof of Premium. Fail safely to Free while preserving all user data and core calling.

## Purchase and licence architecture

The current app is distributed as debug-signed APKs through GitHub Releases, not through Google Play. The repository currently has no billing SDK, account system, purchase backend or licence verification service.

For the existing direct-APK distribution, the likely route is a hosted checkout plus a small licence service:

1. Customer purchases a plan through a hosted checkout.
2. The provider sends a signed webhook to the licence backend.
3. Backend records the customer entitlement and issues a short-lived, signed licence token.
4. App redeems a one-time activation code or signs in, then stores the token securely.
5. App refreshes entitlement periodically and supports a documented offline grace period.
6. Backend can revoke or expire access; app returns to Free without deleting user data.

Do not embed a signing secret, payment-provider secret or permanent Premium flag in the APK. Validate signed tokens and their expiry; protect activation codes against replay and rate-limit redemption. Keep payment card details out of the app and backend.

If TomOS SIP is later distributed through Google Play, in-app purchases for digital features generally need Google Play Billing unless a policy exception/program applies. Keep the purchase adapter replaceable so direct distribution and Play distribution can use different purchase providers without changing feature checks. Review current Play policy before publishing there.

## Technical implementation phases

### Phase 0 — product decisions

- Confirm the Premium feature list and Free account limit.
- Choose monthly/yearly pricing and whether a one-time lifetime option is offered.
- Decide whether users need an account/email sign-in or can activate with a purchase code.
- Decide whether Premium can be shared across devices and the maximum active-device count.
- Select distribution/payment route: direct APK only, Google Play, or both.
- Publish clear renewal, cancellation, refund, privacy and offline-access terms.

### Phase 1 — entitlement foundation (no paywall yet)

- Add a pure Kotlin feature catalogue and entitlement model.
- Add an injectable repository with a default Free implementation.
- Add unit tests for Free, Premium, expired, unknown and verification-error states.
- Add feature guards at domain/service boundaries.
- Keep all current functionality enabled during this phase; this avoids accidentally locking out users before purchase infrastructure exists.

### Phase 2 — premium UX

- Add a Premium page showing exact benefits, current entitlement and renewal/expiry details.
- Show a clear explanation when a locked action is selected.
- Do not use deceptive countdowns, hidden recurring terms or fake purchase success.
- Make restore-purchase / restore-licence available and easy to find.

### Phase 3 — payments and licence service

- Implement the selected payment adapter and backend.
- Verify purchases server-side; process signed webhooks idempotently.
- Implement activation, refresh, expiry, revocation, device management and support tooling.
- Add privacy/security review and abuse/rate-limit controls.

### Phase 4 — enable feature gates gradually

Recommended order after entitlement and payment validation:

1. Advanced codec controls.
2. Encrypted backup premium extras (keep basic export/restore available to everyone).
3. Multiple SIP accounts.
4. Advanced call-management tools only after those tools are implemented and stable.

Release behind a remotely controlled rollout/kill switch if the service architecture supports it. Never make core calling dependent on the licence server being reachable.

## Acceptance criteria

- A new install with no network is fully usable as Free.
- Core SIP calling and incoming-call handling are unchanged by entitlement failures.
- Free users cannot invoke Premium operations by bypassing or hiding UI.
- Active Premium customers retain access during the documented offline grace period.
- Expired/revoked Premium returns to Free without deleting accounts, contacts, call history or backups.
- Reinstall/device replacement can restore a legitimate entitlement.
- Purchase, renewal, cancellation, refund, expiry, revocation and clock/network edge cases have automated tests.
- CI builds and unit tests pass before each rollout.

## Decisions needed before implementation

1. Should Premium be monthly/yearly subscription, one-time lifetime purchase, or both?
2. Is the initial distribution direct GitHub APK only, or do you intend to publish on Google Play too?
3. Should Premium activation use an email account or a purchase/activation code?
4. Confirm the proposed Premium feature list, especially multiple SIP accounts, advanced codec controls and encrypted backup.
