# Private mode (0.3.8 preview)

**Tools → Private mode** opens private tabs. Your normal tabs are put aside untouched
and come back when you choose **Tools → Leave private mode**.

No browser is 100% private. This mode removes what this app stores and blocks the
common ways websites identify you. The limits are listed below.

## Nothing saves

| What | Private mode |
|---|---|
| Cookies, local storage, IndexedDB, cache, service workers | Kept in a separate throwaway WebView profile (`voodoo-private`). It is deleted when you leave private mode or close the app, and again at the next launch if the app was killed. |
| Tabs, recent sites, page-failure records | Not written. Your saved normal tab list is untouched. |
| Screenshots and the recent-apps preview | Blocked (`FLAG_SECURE`) while private mode is on. |
| Keyboard learning in the address bar | The keyboard is asked not to learn words (`IME_FLAG_NO_PERSONALIZED_LEARNING`). Keyboards may ignore this, and website text fields can't be covered. |
| Extensions, userscripts, Magic scripts, ChatGPT bridge | Not injected into private tabs. Extensions can't see or control private tabs, and websites can't load extension files to detect them. Extension network-blocking rules still apply. |

Things you do on purpose are still saved where you put them: downloads, bookmarks, pins,
notes and AI chats. With Tor on, downloads are disabled, because Android's download
manager doesn't use Tor and would reveal your IP address.

## Location and IP address

- **Tor (optional, on by default):** every WebView request goes through the bundled Tor
  client. Websites see a Tor exit address, not yours. DNS lookups also go through Tor,
  using Tor's HTTP tunnel, so hostnames aren't looked up on your network. WebView
  traffic is blocked (pointed at a closed local port) until Tor reports an established
  circuit. If Tor can't connect or stops, websites stay blocked; you choose to retry,
  continue without Tor, or leave.
- Location access, camera, microphone and screen capture are always denied.
- **WebRTC** is removed, because it can reveal your real IP address outside the proxy.
  This also covers child frames and new windows a page creates, as soon as they are
  reachable.

## Fingerprinting

Private tabs report:

- A generic Chrome-on-Android user agent and client hints, with no phone model,
  Android version or WebView marker.
- The UTC time zone, `en-US` language, 4 CPU cores, 4 GB of memory and a common
  412×915 screen.
- Per-session noise on canvas, WebGL pixels and audio fingerprints, and generic GPU
  strings.
- No motion or orientation sensors, battery, gamepads, Bluetooth, USB, serial, HID or
  media-device lists.
- The Do Not Track and Global Privacy Control signals, as JavaScript values and as
  `DNT`/`Sec-GPC` request headers where WebView supports custom headers.
- Third-party cookies blocked, HTTPS only (no plain-HTTP or mixed content).
- The same protections inside dedicated and shared workers. Service workers are
  disabled.
- Private tabs are excluded from Android autofill and password managers.

## Limits

- Signing in to a site tells it who you are, in any mode.
- WebView is not Tor Browser. A determined tracker can still tell this browser apart
  from others; it just learns much less. Use Tor Browser when your safety depends on it.
- The `Accept-Language` header still uses your Android language (WebView doesn't let
  apps change it). JavaScript reports `en-US`. Older WebView versions may also send an
  `X-Requested-With` header naming the app; this AndroidX version no longer lets apps
  control it.
- Tor makes pages slower, and some sites block Tor or show more CAPTCHAs.
- Tor covers WebView traffic only. Voodoo's AI chat, extension installs, script
  discovery and device link use their own connections and do not go through Tor.
- Android, Google Play services and your keyboard work outside the browser and are not
  controlled by it.
- Your network can see that you use Tor (not which sites). Bridges/obfuscation are not
  included yet.
- This uses Tor 0.4.9.5 (the newest tor-android release that builds with this
  project's Android SDK 36 toolchain). It hasn't been tested on a phone yet, and
  Voodoo's privacy code has not been independently audited.

## Verification

- Node/DOM tests: generic device values, WebRTC and sensor removal, UTC time zone
  throughout, native-looking patched functions and geolocation denial.
- The Android build compiles, links the Tor library and passes the existing tests
  and lint.
- **Not yet verified on a phone:** Tor connecting, IP and DNS hiding (check a site that
  shows your IP and a DNS-leak test), profile deletion, and leaving private mode.
