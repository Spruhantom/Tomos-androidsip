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
- GitHub release update checker with version comparison and a link to download the latest published APK.

## Build
Run from the repository root:

```bash
gradle --no-daemon --stacktrace assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Main-branch release APKs use the GitHub Actions run number as their Android version code so installed apps can identify newer releases. GitHub Actions caches the Android debug keystore for main-branch builds so successive APK releases retain the same signing identity.

## Installation and updates
- APKs published before the persistent signing-key cache was introduced may have different debug signatures. Android cannot update one of those installations in place with a differently signed APK.
- For that one-time transition, back up anything important and uninstall the old TomOS SIP app before installing the first APK built with the persistent signing key. Uninstalling removes app-local contacts and call history.
- After installing the first APK built with the persistent key, subsequent main-branch APK updates should retain the same signing identity and install as normal updates, provided the Android version code increases.
- These are debug-signed test builds, not production-signed releases. A production distribution should use a protected release keystore and signing secrets.

## Current limitations / validation
- Advanced SIP settings are applied when registering the account. Confirm the configured port, authentication ID and registration expiry against your PBX.
- The Linphone SDK CoreService has been declared as an Android phone-call foreground service. Real-device testing is still required to confirm background registration and incoming-call delivery across Android versions and OEM battery policies.
- Incoming-call notifications require notification permission on Android 13 and later.
- Test calls, audio routing, registration, NAT traversal and TLS against a real PBX before production use.
- Asterisk uses standard SIP/PJSIP registration; deployment-specific host, port, transport, extension/auth ID, password and TLS settings must be supplied for end-to-end validation.

Liblinphone is distributed under GPLv3. Review its licence and redistribution obligations before publishing binaries.
