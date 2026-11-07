# TomOS SIP for Android

A TomOS-branded Android SIP softphone built with Kotlin, Jetpack Compose and the Liblinphone SDK.

## Current features
- TomOS dark neon-punk interface and official TomOS launcher logo.
- SIP registration over UDP, TCP and TLS.
- Numeric dial pad with digits 0–9, star, hash, clear and backspace.
- Outgoing calls, incoming-call in-app controls and high-priority notifications.
- Mute, speakerphone and hold/resume controls.
- Locally stored contacts with one-tap calling and recent call history.
- Advanced SIP account settings for authentication username, caller ID/display name, server port and registration expiry.
- GitHub release update checker with version comparison, direct APK download, and Android package-installer handoff.
- Application-scoped Linphone Core ownership with a TomOS foreground service holding the same engine instance while the UI is backgrounded.
- Validated-network monitoring, debounced registration refresh after network handovers, and bounded registration retries after failures.
- Audio codec controls for enabling/disabling every audio codec exposed by the bundled Linphone SDK; codec preferences are saved locally and used for new calls.

## Build
Run from the repository root:

```bash
gradle --no-daemon --stacktrace assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Main-branch APKs use the GitHub Actions run number as their Android version code. CI signs published APKs with a persistent keystore supplied through GitHub Actions repository secrets; it does not rely on an ephemeral runner debug key.

## Installation and updates
Configure these GitHub Actions repository secrets before building or publishing from `main`:

- `TOMOS_SIGNING_KEY_BASE64`: the signing keystore encoded as one-line Base64.
- `TOMOS_SIGNING_STORE_PASSWORD`: the keystore password.
- `TOMOS_SIGNING_KEY_ALIAS`: the key alias.
- `TOMOS_SIGNING_KEY_PASSWORD`: the key password.

Keep the keystore and passwords private. Never commit them to the repository. Main-branch builds fail closed if any signing secret is missing; pull-request builds from forks may run without secrets, but those APKs are not suitable for updating an installed release.

The current public APK was signed with a different, temporary debug certificate. If that original private key is unavailable, Android cannot update that installation in place with a new signing identity. Back up important data (including locally stored contacts and call history) before uninstalling the old app and installing the first APK signed with the persistent keystore.

For that one-time transition only, set the repository Actions variable `TOMOS_ALLOW_SIGNING_MIGRATION` to `true`. The compatibility check will still verify package ID and increasing version code, but will explicitly report that the previous certificate differs. After the first APK signed with the persistent key has been published, immediately remove the variable or set it to `false`. All subsequent builds must match the published APK certificate or CI will fail.

The update check validates package ID, version code and signing certificate. It does not replace installing and updating on a physical Android device.
 
## Updates and audio codecs
- The update checker downloads the APK asset from the matching GitHub Release, verifies the download is complete, and opens the Android package installer. Android may ask the user to allow TomOS SIP to install apps from this source.
- Android still requires the downloaded APK to have the same signing certificate as the installed app and a higher version code. If the installed app was signed with an older, temporary debug key, uninstall it once and install a current main-branch build before testing in-app updates; uninstalling removes app-local contacts and call history.
- The codec screen lists only audio codecs actually provided by the bundled Linphone SDK. Enable multiple codecs that are also supported by the PBX; Linphone negotiates a common codec for each call. At least one codec must remain enabled.

## Current limitations / validation
- Advanced SIP settings are applied when registering the account. Confirm the configured port, authentication ID and registration expiry against your PBX.
- TomOS starts a dedicated sticky phone-call foreground service that references the same application-scoped TomSipEngine used by the UI; it deliberately does not start Linphone CoreService, which would own a separate Core. On process recreation, the Application rebuilds the SIP Core from saved account profiles and the sticky service is recreated by Android. Real-device testing is still required to confirm background registration and incoming-call delivery across Android versions and OEM battery policies. Force-stop, user-restricted background execution, and some OEM battery policies can still prevent automatic restart.
- Network recovery waits for Android to report a validated internet connection, debounces handovers, and limits automatic retries after registration failures. Linphone's own SIP transaction/registration retry behavior remains active between these explicit recovery attempts.
- Incoming-call notifications require notification permission on Android 13 and later.
- Test calls, audio routing, registration, NAT traversal and TLS against a real PBX before production use.
- Asterisk uses standard SIP/PJSIP registration; deployment-specific host, port, transport, extension/auth ID, password and TLS settings must be supplied for end-to-end validation.

Liblinphone is distributed under GPLv3. Review its licence and redistribution obligations before publishing binaries.
