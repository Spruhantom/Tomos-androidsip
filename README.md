# TomOS SIP for Android

A TomOS-branded Android SIP softphone built with Kotlin, Jetpack Compose and the Liblinphone SDK.

## Current features
- TomOS dark neon-punk interface and official TomOS launcher logo.
- SIP registration over UDP, TCP and TLS.
- Numeric dial pad, outgoing and incoming calls, mute, speakerphone and hold/resume.
- Locally stored contacts with one-tap calling and recent call history.
- Advanced SIP account settings and multiple SIP accounts.
- GitHub release update checker with version comparison, direct APK download, and Android package-installer handoff.
- Application-scoped Linphone Core ownership, foreground call service and network recovery.
- Audio codec controls for codecs exposed by the bundled Linphone SDK.

## Build
Run from the repository root:

```bash
gradle --no-daemon --stacktrace assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Main-branch APKs use the GitHub Actions run number as their Android version code. CI uses Android Gradle Plugin's standard debug signing; no repository signing secrets, private keystore or signing migration variable are required.

## Installation and updates

GitHub Actions runs Kotlin unit tests, builds the debug APK, creates a SHA-256 checksum, uploads the APK as a workflow artifact, and publishes main-branch APKs to GitHub Releases.

The app checks GitHub Releases, downloads the newest APK and opens Android's package installer. Android requires an update APK to have the same signing certificate as the installed app. GitHub-hosted runners do not provide a guaranteed persistent debug signing identity, so APKs built on separate runs may have different certificates. Consequently, **in-place updates are not guaranteed** with this signing-free CI setup.

If Android reports a package/signature conflict, the existing app must be uninstalled before installing the newly downloaded APK. Android will normally remove app-local data when an app is uninstalled. Export or otherwise back up important contacts, call history and SIP configuration before doing this. Do not uninstall the existing app until you have a backup and are ready to restore/reconfigure it.

This approach deliberately removes the persistent signing-key secrets and the signing compatibility gate. It does not bypass Android's package-signature security or silently uninstall the app. For seamless in-place updates in future, TomOS SIP would need a stable signing identity managed outside ephemeral CI runners (for example, a properly secured release keystore or a managed app-distribution signing service).

Android may ask the user to allow TomOS SIP to install apps from this source. Confirm the installation in Android's installer.

## Current limitations / validation
- Real-device testing is required for APK installation, SIP registration, audio routing, NAT traversal, TLS and incoming-call delivery.
- Advanced SIP settings must be checked against the target PBX.
- Background registration and incoming calls can be affected by Android and OEM battery policies.
- Asterisk uses standard SIP/PJSIP registration; deployment-specific host, port, transport, extension/auth ID, password and TLS settings must be supplied for end-to-end validation.

Liblinphone is distributed under GPLv3. Review its licence and redistribution obligations before publishing binaries.
