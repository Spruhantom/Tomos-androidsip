# TomOS SIP for Android

A complete TomOS-branded Android SIP softphone built with Kotlin, Jetpack Compose and the Liblinphone SDK.

## Product model

TomOS SIP is a **single paid app with every feature included**. There are no subscriptions, Premium tiers, activation codes, licence files, device binding or in-app feature unlocks. Customers purchase the app once through the chosen sales channel.

## Included features

- TomOS dark neon-punk interface and official TomOS launcher logo.
- SIP registration over UDP, TCP and TLS.
- Numeric dial pad, outgoing and incoming calls, mute, speakerphone and hold/resume.
- Locally stored contacts with one-tap calling and recent call history.
- Multiple SIP accounts and account selection.
- Advanced SIP account settings.
- Per-codec controls for codecs exposed by the bundled Linphone SDK.
- Password-protected AES-256-GCM backup and restore.
- GitHub release update checker with version comparison, direct APK download, and Android package-installer handoff.
- Application-scoped Linphone Core ownership, foreground call service and network recovery.

## Build

Run from the repository root:

```bash
gradle --no-daemon --stacktrace assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs Kotlin unit tests and compiles both debug and release variants. CI uploads the debug APK as a short-lived testing artifact.

## Paid distribution and updates

The repository no longer publishes installable APKs automatically to public GitHub Releases. This prevents a paid build from being distributed publicly before a sales channel is selected.

Before selling a production APK, configure a stable Android release-signing identity and choose a storefront/distribution provider. Android updates must be signed with the same certificate as the installed app. Do not use ephemeral debug signing for customer releases. The app's update-check source must also be connected to the chosen distribution channel before promising in-app updates.

The app contains no purchase verification or DRM. The sales platform handles payment and delivery; the app itself provides the complete feature set after installation.

## Current limitations / validation

- Real-device testing is required for APK installation, SIP registration, audio routing, NAT traversal, TLS and incoming-call delivery.
- Real PBX details and hardware testing are required for end-to-end SIP validation.
- Background registration and incoming calls can be affected by Android and OEM battery policies.
- Liblinphone is distributed under GPLv3. Review its licence and redistribution obligations, including source-code and notice requirements, before commercial distribution.
