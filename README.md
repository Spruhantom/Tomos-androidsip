# TomOS SIP for Android

A TomOS-branded Android SIP softphone built with Kotlin, Jetpack Compose and the Liblinphone SDK.

## Current features
- TomOS dark neon-punk interface and official TomOS launcher logo.
- SIP registration over UDP, TCP and TLS.
- Numeric dial pad, outgoing calls, incoming-call controls and notifications.
- Mute, speakerphone and hold/resume controls.
- Locally stored contacts and recent call history.
- Multiple SIP accounts and advanced account settings.
- GitHub release update checker with version comparison, direct APK download, and Android package-installer handoff.
- Application-scoped Linphone Core ownership and foreground-service support.
- Network recovery and audio codec controls.

## Build
Run from the repository root:

```bash
gradle --no-daemon --stacktrace assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Main-branch APKs use the GitHub Actions run number as their Android version code. CI uses Android's standard debug signing; no repository signing secrets or private keystore are required.

## Installation and updates

Main-branch builds are published as prerelease assets on [GitHub Releases](https://github.com/Spruhantom/Tomos-androidsip/releases). The app checks for newer builds, downloads the APK and hands it to Android's package installer. Android always requires the user to approve installation.

**Important signing limitation:** Android only permits an in-place update when the downloaded APK is signed with the same certificate as the installed app. GitHub-hosted runners may generate a different debug certificate on a later run, so CI-built APKs are not guaranteed to update an earlier installation in place. If Android reports a signature conflict, the old app must be uninstalled before the new APK can be installed. Uninstalling removes app-local data, including contacts, call history and SIP settings. Export or otherwise back up anything important before uninstalling.

This project no longer requires or reads `TOMOS_SIGNING_KEY_BASE64`, `TOMOS_SIGNING_STORE_PASSWORD`, `TOMOS_SIGNING_KEY_ALIAS`, `TOMOS_SIGNING_KEY_PASSWORD`, or `TOMOS_ALLOW_SIGNING_MIGRATION`.

## Current limitations / validation
- A successful CI build does not prove registration, audio routing, NAT traversal, TLS or background incoming calls work against a real PBX.
- Confirm SIP host, port, transport, extension/auth ID, password and registration expiry against your PBX.
- Test on physical Android devices and review OEM battery restrictions.
- Incoming-call notifications require notification permission on Android 13 and later.
- Liblinphone is distributed under GPLv3. Review its licence and redistribution obligations before publishing binaries.
