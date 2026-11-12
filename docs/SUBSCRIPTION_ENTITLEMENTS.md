# TomOS SIP Subscription and Entitlement Contract

Status: initial backend/API design. No production payment provider or backend is configured by this document.

## Product

- Product ID: `tomos-sip-premium`
- Plans: `monthly`, `yearly`
- Identity: TomOS account
- Distribution channels: `google_play`, `github_direct`
- One application package and application ID across supported Android distribution channels.

## Purchase verification

### Google Play channel

- Use the currently supported Google Play Billing integration.
- Send purchase tokens to the backend over authenticated TLS.
- Verify purchases with Google Play server-side APIs.
- Process purchase notifications idempotently.
- A client callback alone never grants Premium.

### GitHub direct channel

- Open a hosted checkout with an opaque, short-lived transaction reference.
- Receive payment-provider webhooks on the backend.
- Verify webhook signatures and event timestamps.
- Deduplicate webhook events and reconcile subscription state with the provider.
- Do not store card data in the Android app or TomOS entitlement service.

Both adapters update the same account entitlement record. A purchase from either channel is reflected when the user signs into the same TomOS account, subject to the provider's transfer and cancellation rules.

## Proposed API

All endpoints use HTTPS, authenticated TomOS account sessions, bounded request/response sizes and versioned JSON schemas.

### `POST /v1/auth/login`

Accepts an email address and password over HTTPS and returns an account ID, short-lived access token, rotating refresh token and access-token expiry in Unix milliseconds. Passwords are never persisted by the Android client.

### `POST /v1/auth/refresh`

Accepts the current refresh token and returns a new access token and rotated refresh token. The previous refresh token must be invalidated by the service after successful rotation.

### `POST /v1/auth/logout`

Accepts the current refresh token and revokes its token family. The Android client clears local credentials even if this endpoint is unavailable.

### `GET /v1/me/entitlements`

Requires an account access token. Returns a signed envelope:

```json
{
  "signature_algorithm": "Ed25519",
  "key_id": "entitlements-2026-01",
  "signed_payload": "base64-of-exact-utf8-json-bytes",
  "signature": "base64-ed25519-signature"
}
```

The decoded signed payload has this illustrative schema:

```json
{
  "schema_version": 1,
  "account_id": "opaque-account-id",
  "product_id": "tomos-sip-premium",
  "status": "active",
  "plan": "monthly",
  "source": "google_play",
  "valid_from": "2026-11-01T00:00:00Z",
  "valid_until": "2026-12-01T00:00:00Z",
  "grace_until": null,
  "features": [
    "multiple_sip_accounts",
    "advanced_codec_controls",
    "encrypted_backup",
    "premium_plugin_capabilities"
  ],
  "issued_at": "2026-11-09T14:00:00Z",
  "expires_at": "2026-11-09T15:00:00Z"
}
```

The Ed25519 signature covers the exact UTF-8 bytes represented by `signed_payload`; clients must verify those bytes before parsing or trusting any field. The client checks the configured key ID, product ID, account ID, feature set, UTC timestamps and short snapshot expiry. Active and grace-period snapshots must list the complete supported Premium feature set; Free, expired, refunded and revoked snapshots must have an empty feature list. A Free account may omit `plan` and `source`. Key rotation requires shipping a trusted key set or a separately signed key-rotation document; changing the key ID alone must never trust a new key.

### `POST /v1/billing/google-play/verify`

Accepts the Play purchase token and product/plan identifiers. The backend validates the token with Google Play and associates the verified purchase with the authenticated account.

### `POST /v1/billing/github/checkout`

Creates a hosted checkout session for a selected plan. The response contains a short-lived checkout URL and opaque transaction reference.

### `POST /v1/billing/restore`

Reconciles purchases already associated with the authenticated account and the selected distribution providers. The client calls this endpoint only when the user explicitly selects Restore Purchases, then fetches a fresh signed entitlement snapshot. The client must not submit another account's credentials or raw payment details.

### `POST /v1/billing/cancel`

Returns the provider-managed subscription management destination or initiates a supported cancellation flow. Cancellation does not erase access before the paid-through period ends unless the provider reports immediate revocation/refund.

## Entitlement states

- `free`
- `active`
- `grace_period`
- `expired`
- `refunded`
- `revoked`
- `verification_unavailable`

The client also tracks local `loading` and `unknown` states. Unknown, malformed, invalidly signed or expired snapshots grant Free only.

## Offline behaviour

- Cache only a signed, time-limited snapshot.
- Verify its signature on every app start and before granting a Premium operation.
- Apply a bounded offline grace policy only if the backend explicitly issued it.
- Do not extend validity using the device clock alone.
- If the snapshot expires and the backend is unreachable, preserve data and core Free calling; do not grant new Premium-only operations.
- Never erase Premium data when entitlement is lost.

## Account and device security

- Use short-lived access tokens and refresh-token rotation.
- Store tokens using Android Keystore-backed encrypted storage.
- Rate-limit sign-in, checkout and restore endpoints.
- Do not log purchase tokens, session tokens or sensitive payment identifiers.
- Define account recovery, device/session revocation and support procedures.
- Device limits and concurrent-session policy remain a product decision and must be documented before launch.

## Billing lifecycle tests

Test new purchase, pending payment, renewal, cancellation, grace, account hold, refund, chargeback, expiry, duplicate webhook, delayed webhook, account sign-in on a second device, channel switch, offline start, invalid signature and backend outage.
