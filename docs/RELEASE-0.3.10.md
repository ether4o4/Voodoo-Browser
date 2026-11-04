# 0.3.10 — private mode hardening

Fixes four gaps found reviewing 0.3.9's private mode:

- **WebRTC through child frames (IP leak).** A page could create an empty frame and
  borrow its unpatched `RTCPeerConnection`, whose UDP traffic bypasses Tor. The shield
  now protects every same-origin frame (and `window.open` windows) the moment it is
  inserted or reached, before page code can use it.
- **Workers.** Dedicated and shared workers ran unshielded and exposed the real time
  zone, CPU count and language. They now start through a prelude that runs the shield
  first; classic workers keep resolving relative URLs against their real address.
  Service workers are disabled in private mode, as in Tor Browser.
- **Autofill.** Private tabs are excluded from Android autofill, so password managers
  are not offered private-tab input.
- **Tor callbacks** now always run on the main thread.

## Verification

Node/DOM tests cover frames reached by index, `innerHTML`, `insertAdjacentHTML` and
`contentWindow`, worker preludes and service-worker blocking. In desktop Chromium
(time zone America/Chicago, locale de-DE) with the shield loaded only in the top page:
the 0.3.9 shield let a new frame construct `RTCPeerConnection` and a worker report
America/Chicago; the 0.3.10 shield removes WebRTC from those frames and the worker
reports UTC, en-US and 4 cores, with relative `importScripts` still working. Not yet
verified on a phone.

## Installation

Development build `com.ether4o4.voodoo.dev`, signed with the persistent development
key: it installs over 0.3.9 and keeps data. Earlier previews need a one-time uninstall.
