# Multiple SIP Accounts — implementation plan

## Goal

Allow TomOS SIP to configure and register more than one independent SIP identity in the same Linphone Core instance.

## Architecture

- **Profile model:** each identity has a stable ID, user-facing label, SIP username, authentication username, server, transport, port, display name, expiry and enabled state.
- **Credential boundary:** passwords must be stored through Android Keystore-backed encryption. They must not be written to ordinary SharedPreferences, logs, diagnostics or exported configuration.
- **Runtime account registry:** map each profile ID to its Linphone `Account` object. Registration callbacks must update the matching profile only; a single global registered flag is insufficient.
- **Default outgoing identity:** retain a selected profile ID and set Linphone's default account to the corresponding registered account. Calls to short extensions must use that account's server domain.
- **Incoming identity:** use the incoming call's local address/account to identify which configured identity received the call. Caller ID matching remains independent of the receiving account.
- **Lifecycle:** add, edit, enable/disable, remove and re-register profiles without deleting unrelated Linphone accounts. Do not remove an account while it owns an active call.

## Delivery stages

1. Add profile and validation domain types. (Implemented)
2. Add Android Keystore-backed credential storage and non-secret profile persistence. (Implemented; not yet wired into registration UI)
3. Implement the Linphone account registry and per-account registration callbacks. (Next)
4. Add account management UI, registration indicators and selected outgoing account.
5. Route outgoing calls through the selected identity and expose the receiving identity during incoming calls.
6. Add automated and Android Keystore instrumentation tests; expand the PBX/device checklist.

## Acceptance criteria

- Two or more SIP accounts can register simultaneously against different servers or extensions.
- A failed registration does not mark other accounts offline.
- Selecting an outgoing account changes the identity and domain used for new calls.
- Incoming calls identify the receiving account correctly.
- Editing or removing one profile does not disturb other registrations.
- Credentials are protected by Android Keystore-backed encryption and never appear in logs.
- Existing single-account users retain a clear migration path.
- CI build and automated tests pass; real-device/PBX validation is recorded separately.

## Current limitation

The profile model, profile metadata store and encrypted credential store now exist. The credential store uses a separate Android Keystore AES-256-GCM key per account; preferences contain only Base64-encoded IV and ciphertext. The profile metadata JSON contains no password field. The stores are not yet wired into registration UI or Linphone Core. Multi-account registration is not available until stages 3–5 are implemented and tested. If Android restores encrypted preferences without the corresponding Keystore key, the affected SIP password must be entered again.
