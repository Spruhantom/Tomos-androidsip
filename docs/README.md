# TomOS SIP documentation

Welcome to the documentation for the TomOS SIP Android softphone.

## Guides

- [User guide](USER_GUIDE.md) — install, configure and use the app.
- [SIP configuration](SIP_CONFIGURATION.md) — account fields, transports and codec guidance.
- [Testing checklist](TESTING_CHECKLIST.md) — manual validation steps for Android devices and PBX environments.
- [Build 49 release notes](RELEASE_NOTES_BUILD_49.md) — features and known validation limits in the current main-branch release.

## Important testing note

A successful GitHub Actions build confirms that the APK compiles and is packaged. It does not prove that SIP registration, calls, audio routing, notifications or background operation work on every Android device. Complete the real-device checklist before relying on the app in production.
