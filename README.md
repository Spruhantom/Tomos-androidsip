# TomOS SIP for Android

A TomOS-branded Android SIP softphone built with Kotlin, Jetpack Compose and the Liblinphone SDK.

## Current features
- TomOS dark neon-punk interface and official TomOS launcher logo.
- SIP registration over UDP, TCP and TLS.
- Outgoing calls, incoming-call in-app controls and high-priority notifications.
- Mute, speakerphone and hold/resume controls.
- Locally stored contacts and recent call history.

## Build
Run from the repository root:

```bash
gradle --no-daemon --stacktrace assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Current limitations / validation
- The Linphone SDK CoreService has been declared as an Android phone-call foreground service. Real-device testing is still required to confirm background registration and incoming-call delivery across Android versions and OEM battery policies.
- Incoming-call notifications require notification permission on Android 13 and later.
- Test calls, audio routing, registration, NAT traversal and TLS against a real PBX before production use.
- Asterisk uses standard SIP/PJSIP registration; deployment-specific host, port, transport, extension/auth ID, password and TLS settings must be supplied for end-to-end validation.

Liblinphone is distributed under GPLv3. Review its licence and redistribution obligations before publishing binaries.
