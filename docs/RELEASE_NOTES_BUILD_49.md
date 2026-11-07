# TomOS SIP — Build 49

**Release:** [v0.1.0-build-49](https://github.com/Spruhantom/Tomos-androidsip/releases/tag/v0.1.0-build-49)  
**Branch:** `main`  
**Merged source commit:** `0031d3c0809e24306f8a0b24284d00f4d1e55bf3`

## Included features

- TomOS-branded Android SIP softphone built with Kotlin, Jetpack Compose and Linphone.
- SIP account registration over UDP, TCP and TLS.
- Numeric dialler, local contacts and recent call history.
- Incoming-call answer and decline controls.
- Dedicated active-call screen with mute, speakerphone, hold/resume and call duration.
- Registration and call status notifications.
- Configurable audio codec preferences.
- GitHub Release update checker with APK download and Android installer handoff.
- Persistent main-branch debug signing identity in the GitHub Actions build workflow.

## Build validation

The GitHub Actions workflow for the main-branch build completed successfully:

- Debug APK build — passed.
- APK existence verification — passed.
- SHA-256 checksum generation — passed.
- Workflow artifact upload — passed.
- GitHub Release publishing — passed.

See the [Build 49 workflow run](https://github.com/Spruhantom/Tomos-androidsip/actions/runs/42409391786).

## Validation limits

The successful workflow is automated build and packaging validation. It does not establish that real SIP registration, two-way audio, call controls, notification behavior, background operation or update installation have been tested on a physical Android device.

Complete the [manual testing checklist](TESTING_CHECKLIST.md) against the target Android versions and PBX before production use.

## Distribution

- [Download app-debug.apk](https://github.com/Spruhantom/Tomos-androidsip/releases/download/v0.1.0-build-49/app-debug.apk)
- [Download SHA-256 checksum](https://github.com/Spruhantom/Tomos-androidsip/releases/download/v0.1.0-build-49/app-debug.apk.sha256)

Build 49 is a debug-signed test build. It is not a production-signed release.
