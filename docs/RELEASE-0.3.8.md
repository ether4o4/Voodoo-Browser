# 0.3.8 — Private mode

Built on 0.3.7. A new **Private mode** entry at the end of the Tools menu opens private
tabs; normal tabs are kept aside and restored when you leave.

- A throwaway WebView profile holds all site data and is deleted on exit and at the
  next launch. Tabs, recent sites and failure records aren't written. Screenshots are
  blocked.
- Optional built-in Tor (on by default) hides your IP address and DNS. It fails
  closed: websites stay blocked until Tor is connected.
- WebRTC, sensors, location and media devices are blocked. The user agent, client
  hints, time zone, language, screen, canvas, WebGL and audio fingerprints are masked.
  Do Not Track and Global Privacy Control signals are sent.
- Version 0.3.8, code 15. The APK grows by about 17 MB for the ARM Tor binaries.

See [what private mode protects and its limits](PRIVACY.md).

## Verification

CI runs the existing suites, builds, lint and APK checks. New tests cover the
fingerprint shield. Not yet verified on a phone: Tor connection, IP/DNS hiding (check
an IP-display site and a DNS-leak test), data deletion and restoring normal tabs.

## Installation

The published preview APK is debug-signed, package `com.ether4o4.voodoo.dev`.
It is separate from the original `com.ether4o4.voodoo` release and does not import
that profile. Updating an installed APK in place requires its matching signing key.
Do not uninstall or clear data to resolve a signature mismatch.
