# TomOS SIP for Android

A TomOS-branded Android SIP softphone using the Liblinphone engine.

## Features in 0.1.0
- TomOS neon-punk dark interface (pink/cyan accent palette).
- SIP account registration over UDP, TCP or TLS.
- Outgoing audio calls and hang-up controls.
- Registration and call-state feedback.
- Android microphone permission request.

## Build
Open this directory in Android Studio or run:

```bash
gradle --no-daemon --stacktrace assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Liblinphone is distributed under GPLv3. Review its licence and your distribution obligations before publishing or redistributing builds. SIP credentials are currently held in memory only and are not persisted.

## Notes
This is an initial implementation. Test registration, incoming-call handling, background operation, audio routing, NAT traversal and TLS certificate validation against your actual PBX before production use.
