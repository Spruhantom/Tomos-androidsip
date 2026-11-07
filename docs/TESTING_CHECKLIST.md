# TomOS SIP — Manual Test Checklist

Use this checklist with a test PBX and non-production SIP accounts. Record the device model, Android version, app build, PBX version and test date. Never record SIP passwords.

## Install and permissions

- [ ] Install the APK from the official GitHub Release.
- [ ] Confirm the app launches without crashing.
- [ ] Grant microphone permission and verify the permission state.
- [ ] On Android 13+, grant notification permission and verify notification display.
- [ ] Confirm the app icon and TomOS SIP label appear correctly.

## SIP registration

- [ ] Register with valid UDP credentials.
- [ ] Confirm the UI changes to Registered / ONLINE.
- [ ] Confirm the ongoing registration notification appears.
- [ ] Test an incorrect password and confirm a useful failure state.
- [ ] Test an unreachable server and confirm the UI remains usable.
- [ ] Repeat with TCP if the PBX supports it.
- [ ] Repeat with TLS and a valid PBX certificate if the PBX supports it.
- [ ] Confirm the configured port and registration expiry match the PBX.

## Calling

- [ ] Place an outgoing call to a test extension.
- [ ] Confirm outgoing progress and ringing states are displayed.
- [ ] Answer an incoming call.
- [ ] Decline an incoming call.
- [ ] Confirm caller identity is displayed when supplied by the PBX.
- [ ] Confirm the dedicated call screen appears during calls.
- [ ] Confirm call duration starts after connection and advances correctly.
- [ ] End calls from the app and confirm the call screen closes.
- [ ] Confirm call history receives an entry.

## Contacts and caller ID

- [ ] Confirm existing contacts are retained after upgrading from a build that used the original plain-text contact list.
- [ ] Add a contact with a name and SIP extension.
- [ ] Try saving a contact with a blank name or number and confirm a useful validation message appears.
- [ ] Try saving a duplicate extension with different punctuation or a SIP URI and confirm it is rejected.
- [ ] Search contacts by name and by number.
- [ ] Edit a contact and confirm the change persists after restarting the app.
- [ ] Delete a contact and confirm it remains deleted after restarting the app.
- [ ] Place a call from a saved contact.
- [ ] Receive a call from a saved extension and confirm the saved contact name appears on the incoming-call screen and notification.
- [ ] Receive a call from an unknown extension and confirm the PBX display name or SIP username is used as the fallback.

## Audio and media

- [ ] Verify two-way audio on a normal call.
- [ ] Toggle mute and confirm the remote party cannot hear the local microphone while muted.
- [ ] Toggle speakerphone and verify the audio route changes.
- [ ] Place a call on hold and resume it.
- [ ] Verify the selected codec is compatible with the PBX.
- [ ] Disable a codec and confirm the preference persists after restarting the app.
- [ ] Confirm the UI prevents disabling the final enabled codec.

## Notifications and background operation

- [ ] Confirm the ongoing notification appears while registered.
- [ ] Confirm the notification updates during an active call.
- [ ] Confirm the notification clears when neither registered nor in a call.
- [ ] Tap the notification and confirm the app opens.
- [ ] Background the app during registration and observe whether registration remains active.
- [ ] Lock the device and test an incoming call.
- [ ] Repeat after enabling the device manufacturer's recommended battery settings.
- [ ] Repeat after process termination and device restart; record any limitations.

## Updates

- [ ] Confirm the app identifies a newer GitHub Release when one exists.
- [ ] Download an update and verify Android opens the package installer.
- [ ] Confirm a same-signature, higher-version APK installs as an update.
- [ ] Confirm the installed version launches after updating.
- [ ] Record the installed and downloaded APK certificate fingerprints when investigating update failures.

## Results

| Field | Result |
| --- | --- |
| Device model | |
| Android version | |
| App build / version code | |
| PBX / Asterisk version | |
| SIP transport and port | |
| Test date | |
| Tester | |
| Overall result | Not tested |

### Important

A green GitHub Actions build validates compilation and packaging only. It is not a substitute for these handset, network, PBX and audio tests. Mark each item only after it has been tested and record failures with non-sensitive diagnostic details.
