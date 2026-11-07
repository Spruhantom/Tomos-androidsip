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

## Build
Run from the repository root:

```bash
gradle --no-daemon --stacktrace assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Current limitations / validation
- Advanced SIP settings are applied when registering the account. Confirm the configured port, authentication ID and registration expiry against your PBX.
- The Linphone SDK CoreService has been declared as an Android phone-call foreground service. Real-device testing is still required to confirm background registration and incoming-call delivery across Android versions and OEM battery policies.
- Incoming-call notifications require notification permission on Android 13 and later.
- Test calls, audio routing, registration, NAT traversal and TLS against a real PBX before production use.
- Asterisk uses standard SIP/PJSIP registration; deployment-specific host, port, transport, extension/auth ID, password and TLS settings must be supplied for end-to-end validation.

Liblinphone is distributed under GPLv3. Review its licence and redistribution obligations before publishing binaries.
