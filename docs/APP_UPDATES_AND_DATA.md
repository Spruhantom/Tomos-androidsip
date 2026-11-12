# App updates and data retention

TomOS SIP stores SIP configuration, credentials, contacts, call history and preferences locally. A newer APK can retain this data when Android accepts it as a compatible in-place update.

## What Android preserves during an in-place update

Android updates an installed app in place when all of the following remain compatible:

- The application ID remains `uk.co.tomos.sip`.
- The APK is signed with the same release signing certificate (or a valid signing-key rotation lineage).
- The new APK has a higher `versionCode`.

For a normal in-place update, Android retains the app's private data directory and Android Keystore entries.

## Data persisted by TomOS SIP

| Information | Storage |
| --- | --- |
| SIP account profiles and selected account | `SipAccountProfileStore` / private SharedPreferences |
| SIP passwords | `SipCredentialStore` / AES-GCM with Android Keystore keys |
| Contacts and call history | private SharedPreferences |
| General SIP preferences | private SharedPreferences |

The Linphone Core is created by the app-owned `TomosSipApplication`; SIP profiles and credentials are restored by the app's own stores when the Core starts.

## Important limitations

- Uninstalling the app or clearing its storage removes local app data. Android Keystore keys are also removed on uninstall.
- `android:allowBackup="false"` disables Android backup/restore for the app; it does not prevent Android from retaining data during an in-place APK update.
- A signing-certificate or application-ID change is not a normal compatible update. Do not publish it as an in-place upgrade without a tested migration plan.
- Do not store SIP passwords in plain-text profile JSON, logs, exported diagnostics or source control.
- The password-protected user backup can include contacts, call history and SIP configuration. SIP credentials are encrypted within the backup and must never be written as plaintext.

## Release checklist

1. Keep `applicationId = "uk.co.tomos.sip"` unchanged.
2. Keep the production signing key stable and tightly protected.
3. Increment `versionCode` for each distributable release.
4. Test an upgrade on a device: install an older APK, create SIP account(s), save contacts and call history, then install the newer APK over it.
5. Verify that profiles, credentials, contacts, call history and preferences survive the update and that the app re-registers successfully.
6. Separately test uninstall/reinstall and device migration; those are not equivalent to an in-place update.

A successful compile does not replace the on-device upgrade test, especially for Android Keystore-backed credentials.
