# Phone and PC device link

This preview adds an explicit local-network connection to Voodoo Browser desktop.
It is separate from the older ChatGPT remote browser-control bridge.

1. Put the PC and phone on the same private Wi-Fi network or phone hotspot.
2. Open Voodoo desktop → Device link → Enable link. Permit the app only on your
   trusted private network if Windows asks. No firewall rule or port-forward is
   created by Voodoo itself.
3. Create a pairing code with the PC's private IPv4 address. Keep it private;
   it contains the new pair's secret and expires after five minutes.
4. On the phone, choose Tools → Device link → Pair a PC, paste the code, and pair.
5. Use Sync now while this phone screen remains visible. Share a saved bookmark,
   paste a note snapshot, or use Tools → Send tab to PC from an HTTPS website.
6. Incoming tabs stay in the shared library until you tap one and confirm opening.

The app shares only selected HTTPS bookmark/tab URLs, titles, and note text.
No profile, cookies, API keys, passwords, AI conversation history or complete
writing backup is automatically synchronized. A note can contain private text
you paste into it; choose its contents carefully. Entries are immutable snapshots.
Links with fragments or query names suggesting credentials (such as `token`,
`session`, `code` or `api_key`) are refused rather than silently modified. Share
the normal website address instead. This filter is not a guarantee that arbitrary
text or every possible URL contains no secret.
Different note versions keep different IDs. A same-ID content conflict fails the
exchange without replacing the local library. The preview keeps up to 1,000 items
and 1 MB of shared data, displaying the newest 100 on the phone.

## Security boundaries

TLS is pinned to the exact certificate SHA-256 fingerprint carried in the pairing
code. It is used only for the literal private IP and port in that code. The custom
trust manager is confined to the device-link client; Android's global TLS and
cleartext policies are unchanged. Redirects and proxy use are disabled. Data also
uses AES-256-GCM with direction-bound authenticated metadata and fresh random
nonces. Send counters are persisted before transmission; response data and receive
counters are committed together. Responses must match the current request.

Pair secrets are held through the existing Android Keystore-backed SecureKeys
store. They are excluded from Android backup/transfer. They are not ordinary
provider keys and cannot be retrieved by a page, userscript, or the shared schema.
The native link screen prevents screenshots while open and does not log secrets.

This is not a password manager, device-compromise defense or independent security
certification. Phone installation, pinned TLS behavior on the owner's network,
network-switch/lifecycle handling and physical PC-to-phone pairing still need
device acceptance. Encryption does not protect data after a paired endpoint has
been compromised. Keep high-value credentials in an established browser/password
manager while the preview is evaluated.

`SyncInteropTest` is an opt-in JVM test against a real desktop Node HTTPS service.
It uses the production `SyncClient.transport` factory, checks that a wrong pin
fails TLS, decrypts paired responses, verifies bidirectional retained snapshots,
and rejects a replayed sequence. Supply a private-LAN fixture code through
`VOODOO_INTEROP_CODE`; never put a real pairing code in a command argument or log.
Without that environment variable the integration test is explicitly skipped.
This test does not exercise Android Keystore, phone networking or Activity lifecycle.

## Offline and recovery

No automatic retries or polling run when the phone screen is closed. Shared items
remain saved for a later manual exchange. Desktop must be open with its link
enabled. Guest Wi-Fi isolation and Windows firewall settings can block the link.
Nothing requires a hosted relay or a hosting subscription in this version.

If pairing fails after the PC accepted it, revoke the unused PC entry and create a
fresh code. Existing paired state is retained until a new pairing succeeds. If a
PC address/certificate changes or counters are restored from an old snapshot,
re-pair. Forget this PC deletes the local key but retains shared items; revoke the
phone on desktop to remove its server-side access as well. Revocation cannot erase
data already received by another device.
