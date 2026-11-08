# App updates and data retention

TomOS SIP is designed to keep its locally stored information when a newer APK is installed over the existing app.

## What Android preserves during an in-place update

Android updates an installed app in place when all of the following remain compatible:

- The application ID remains `uk.co.tomos.sip`.
- The APK is signed with the same release signing certificate (or a valid signing-key rotation lineage).
- The new APK has a higher `versionCode`.

For a normal in-place update, Android retains the app's private data directory and Android Keystore entries. The app does not need to export and re-import its data for every update.

## Data already persisted by TomOS SIP

| Information | Storage |
| --- | --- |
| SIP account profiles and selected account | `SipAccountProfileStore` / private SharedPreferences |
| SIP passwords | `SipCredentialStore` / AES-GCM with Android Keystore keys |
| Contacts and call history | private SharedPreferences |
| General SIP preferences | private SharedPreferences |
| Premium installation ID | private SharedPreferences |
| Premium licence | AES-GCM encrypted private storage with an Android Keystore key |

The Linphone Core is created by the app-owned `TomosSipApplication`; SIP profiles and credentials are restored by the app's own stores when the Core starts. This keeps the app's account data model independent of transient Activity state.

## Important limitations

- Uninstalling the app or clearing its storage removes local app data. Android Keystore keys are also removed on uninstall.
- `android:allowBackup="false"` disables Android backup/restore for the app; it does not prevent Android from retaining data during an in-place APK update.
- A signing-certificate or application-ID change is not a normal compatible update. Do not publish it as an in-place upgrade without a tested migration plan.
- Do not store SIP passwords in plain-text profile JSON, logs, exported diagnostics or source control.
- The current encrypted user backup covers contacts and call history. It does not export SIP passwords or Keystore-bound Premium licence material.

## Release checklist

1. Keep `applicationId = "uk.co.tomos.sip"` unchanged.
2. Keep the production signing key stable and tightly protected.
3. Increment `versionCode` for each distributable release. CI supplies a monotonically increasing run number for its build artifacts.
4. Test an upgrade on a device: install an older APK, create SIP account(s), save contacts and call history, activate Premium in a test environment, then install the newer APK over it.
5. Verify that profiles, credentials, contacts, call history and Premium verification survive the update and that the app re-registers successfully.
6. Separately test uninstall/reinstall and device migration; those are not equivalent to an in-place update.

A successful compile does not replace the on-device upgrade test, especially for Android Keystore-backed credentials.
