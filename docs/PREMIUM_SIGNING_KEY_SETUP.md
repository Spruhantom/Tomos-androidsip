# Premium production signing-key setup

TomOS SIP verifies Premium licences with Ed25519. The Android app must contain only
the matching public key. The private signing key is used by the trusted
`tomos-premiumgen` administrator tool and must never enter this repository,
an APK, CI logs, or GitHub Actions variables/secrets.

## 1. Create the production key pair

On the trusted administrator workstation (Arch Linux / Omarchy Linux):

```bash
tomos-premiumgen keygen \
  --private-key ~/.config/tomos-premiumgen/private.pem \
  --public-key ~/.config/tomos-premiumgen/public.pem
chmod 600 ~/.config/tomos-premiumgen/private.pem
```

Back up the private key securely and restrict access to it. Do not regenerate it
for each release: licences issued with an older private key will no longer verify
if Android is updated to a different public key. Plan key rotation as a separate
migration with an overlap period before changing the embedded public key.

The public key is not secret, but treat it as a release-controlled trust anchor.
Verify its fingerprint out of band before configuring builds.

## 2. Build a release with the public key

The Android Gradle build accepts the PEM public key from either a Gradle property
or an environment variable. For example:

```bash
TOMOS_PREMIUM_PUBLIC_KEY="$(cat ~/.config/tomos-premiumgen/public.pem)" \
  ./gradlew :app:assembleRelease
```

Or:

```bash
./gradlew :app:assembleRelease \
  -PtomosPremiumPublicKey="$(cat ~/.config/tomos-premiumgen/public.pem)"
```

The `preReleaseBuild` task rejects a missing key, malformed PEM, non-Ed25519
SubjectPublicKeyInfo key, or a private-key PEM. Debug builds intentionally do
not require production signing material and remain usable as the Free SIP app.

For optional online redemption, configure the HTTPS service URL separately:

```bash
TOMOS_PREMIUM_API_URL="https://licensing.example.invalid" \
TOMOS_PREMIUM_PUBLIC_KEY="$(cat ~/.config/tomos-premiumgen/public.pem)" \
  ./gradlew :app:assembleRelease
```

The URL above is a placeholder; no production activation service is configured
by this repository change. Offline file activation needs only the public key.

## 3. CI / release workflow configuration

When a release workflow is ready to build a Premium-enabled APK, store the PEM
public key as a GitHub Actions **repository variable** named
`TOMOS_PREMIUM_PUBLIC_KEY` (or pass it as the Gradle property). It is public
verification material, not a secret. Do not add the private key to GitHub.
The release job must pass the variable into the Gradle process and must fail if
it is absent. Keep the APK signing identity separate: this key verifies Premium
licences and does not sign Android APK updates.

## 4. Verify the activation flow

1. Install a release APK built with the production public key.
2. Copy the installation UUID from the Premium screen.
3. Issue an offline licence on the administrator workstation using the matching
   private key and that UUID.
4. Import the JSON licence file in the app.
5. Confirm the UI reports a verified signature and matching device ID.
6. Restart the app and confirm the saved licence is re-verified from Android
   Keystore-backed encrypted storage.
7. Try a different device UUID and a modified signature; both must be rejected.

A debug APK built without the production public key deliberately cannot import
licences. The shared cross-language CI fixture is test-only and must never be
used as a production key.

## 5. Current release limitations

- This change configures and validates the public-key trust anchor; it does not
  generate or commit a production key pair.
- No online licence-service URL, payment backend, checkout provider, or webhook
  is configured.
- The current Premium screen supports offline file import and optional online
  code redemption, but feature entitlements remain disabled until their domain
  operations have completed their own enforcement and tests.
- Android APK update signing is a separate concern and still requires a stable,
  securely managed APK signing identity.
