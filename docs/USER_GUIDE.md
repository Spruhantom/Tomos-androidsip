# TomOS SIP for Android — User Guide

## 1. What the app does

TomOS SIP is an Android softphone built with Kotlin, Jetpack Compose and the Linphone SDK. It supports SIP registration, dialling, incoming-call handling, contacts, recent call history, audio controls, codec preferences and checking GitHub Releases for app updates.

## 2. Install the app

1. Open the [TomOS SIP GitHub Releases](https://github.com/Spruhantom/Tomos-androidsip/releases).
2. Download the latest `app-debug.apk` asset.
3. Open the downloaded APK on your Android device and follow Android's package-installer prompts.
4. If Android asks whether TomOS SIP may install apps from this source, review the request and allow it only if you trust the APK source.
5. Launch **TomOS SIP**.

These are debug-signed test builds, not production-signed releases.

## 3. Grant permissions

The app requests microphone access for calls and notification access on Android 13 and later. Grant microphone permission if you want to make or answer calls. Allow notifications if you want call and registration notifications to appear in the notification shade.

You can review or change permissions later in Android Settings → Apps → TomOS SIP → Permissions. Menu names vary by Android version and device manufacturer.

## 4. Configure a SIP account

Open **Settings** and enter the details supplied by your SIP/PBX administrator:

| Field | Meaning |
| --- | --- |
| SIP extension / identity | Your SIP address username or extension |
| SIP password | The account's SIP authentication password |
| SIP server | PBX hostname or IP address |
| Authentication username | Digest-auth username; defaults to the extension when left blank |
| Caller ID / display name | Display name presented with the SIP identity |
| SIP port | Port configured on the PBX |
| Expiry | Registration expiry in seconds; the app accepts 60–86400 |
| Transport | UDP, TCP or TLS, as supported by the PBX |

Select **REGISTER ACCOUNT**. The account status area reports the registration state. A successful registration is shown as **Registered / ONLINE**.

Do not share your SIP password in screenshots, issue reports or public support requests.

## 5. Make a call

1. Open the **Dialler** tab.
2. Enter an extension or SIP address, or use the numeric keypad.
3. Tap **CALL**.
4. During a call, use the dedicated call screen to mute the microphone, switch speakerphone, hold/resume or end the call.

A SIP account must be registered before placing a call.

## 6. Answer an incoming call

When an incoming call is received, the app displays the caller and offers **ANSWER** and **DECLINE** controls. An incoming-call notification may also be shown if Android notification permission is granted and the device permits it.

## 7. Contacts and history

- **Contacts:** add a contact name and extension/SIP number. Saved contacts can be called from the list.
- **History:** review recent call entries stored locally by the app.

Contacts and call history are app-local data. Uninstalling the app removes this data.

## 8. Audio codecs

Open **Settings → Audio Codecs**. The list contains codecs exposed by the bundled Linphone SDK. Enable codecs supported by your PBX. At least one codec must remain enabled. Preferences are saved locally and apply to new calls; the two endpoints still need to negotiate a common codec.

## 9. Check for updates

Use the app's update checker to look for a newer GitHub Release. If an update is found, the app downloads the APK and hands it to Android's package installer.

Android requires the update to have the same signing certificate as the installed app and a higher version code. Builds made before persistent main-branch signing was introduced may not update in place. Follow the release instructions if Android reports a signature conflict.

## 10. Troubleshooting

- **Not registering:** check server address, port, transport, extension, authentication username and password with your PBX administrator.
- **Can register but cannot call:** confirm the destination and PBX dial plan; check firewall/NAT and PBX logs.
- **No microphone audio:** confirm microphone permission and Android audio routing.
- **No notification:** check notification permission and Android notification settings.
- **Update will not install:** confirm the APK is from the official repository release and that the installed app has the same signing certificate. Do not uninstall unless you have considered that local contacts and history will be lost.

For support, include the Android version, device model, app build, transport and non-sensitive error text. Never include passwords, authentication secrets or private SIP credentials.
