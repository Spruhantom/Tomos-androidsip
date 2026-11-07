# SIP Account and Audio Configuration

This guide describes the account options exposed by TomOS SIP for Android. Obtain the exact values from the administrator of your Asterisk/PBX service; do not assume that every server uses the same port or transport.

## Account fields

| App field | Configuration guidance |
| --- | --- |
| SIP extension / identity | The extension or SIP username used in the address of record |
| SIP password | The SIP digest-authentication password |
| SIP server | Hostname or IP address of the SIP registrar/PBX |
| Authentication username | Digest-authentication identity; may differ from the extension |
| Caller ID / display name | Display name attached to the SIP identity |
| SIP port | Registrar/PBX listening port |
| Expiry | Registration lifetime in seconds; app validation is 60–86400 |
| Transport | UDP, TCP or TLS |

The app constructs a SIP identity from the extension and server and configures the selected transport and port. Confirm that the server has a matching listener and account configuration.

## Transport choices

- **UDP:** common in SIP deployments; verify NAT handling and firewall rules.
- **TCP:** use only when the PBX has a TCP SIP listener configured.
- **TLS:** use only when the PBX has a correctly configured TLS listener and certificate setup.

Transport selection in the app does not configure the PBX, firewall, certificates or NAT. Those must be configured independently.

## Asterisk / PJSIP notes

Asterisk deployments commonly use PJSIP. Check the endpoint, authentication object, address-of-record, transport and dial plan on the server. The app does not provision Asterisk automatically.

Before testing, confirm:

1. The extension and authentication username are correct.
2. The SIP password is current.
3. The PBX hostname resolves from the phone's network.
4. The selected port and transport match the PBX listener.
5. Firewall and NAT rules permit signalling and negotiated media traffic.
6. The PBX supports at least one codec enabled in the app.

## Audio codecs

TomOS SIP displays audio payload types exposed by its bundled Linphone SDK. Codec enablement is stored locally and applies to new calls. Keep at least one codec enabled and select codecs that are also enabled and supported by the PBX and the other endpoint.

Codec availability in the UI is not proof that a codec will be negotiated or that media will pass. Confirm the negotiated codec and RTP media using PBX diagnostics and a real call.

## Security and operational notes

- Treat SIP passwords and authentication usernames as credentials.
- Do not commit real account details, logs containing credentials, or screenshots showing secrets.
- Prefer TLS where the PBX is correctly configured to support it, and validate certificate trust on target devices.
- The repository's current APKs are debug-signed test builds. Use a protected release-signing process before distributing a production build.
