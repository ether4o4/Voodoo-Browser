# 0.3.6 — immersion and workspace globe

Built on the complete 0.3.5 browser, including Magic scripts, Favorites, Workbench,
ChatGPT Browser Bridge and device linking.

- A 48 dp navigation toggle beside Go hides the bottom toolbar and gives its space
  to the webpage. The toggle remains reachable and highlights when navigation is
  hidden. TalkBack labels describe the next action and, on Android 11+, the state.
- Portrait and landscape preferences persist independently, defaulting to visible.
  Rotation applies the matching preference without recreating or reloading tabs.
  Android Back, website media controls and system navigation remain available.
- The fabric globe has richer plum and jade coloring, brighter stitching, edge
  lighting, more legible pin heads and a selection ring. The static background glow
  adds no render loop. Existing rotation limits, reduced motion, pin placement,
  selection and workspace data are preserved.
- Version 0.3.6, code 13. No existing profile keys or schemas are migrated or cleared.

## Verification

The existing CI runs web/DOM, bridge-server, Python and Android unit tests, debug
and release builds, lint, and APK source-asset/version/signature checks. Added JVM
checks cover independent orientation choices, restoration and profile preservation.
The WebGL globe was rendered in desktop Chromium at a phone-sized viewport;
this does not establish physical Android visual or lifecycle behavior.

On a phone, hide navigation in landscape, rotate to portrait and back, restart the
app and restore navigation. Check Android Back, the keyboard, tabs and fullscreen
video. Confirm pins can still be selected and moved. These device checks remain
manual; no connected Galaxy was available.

## Installation

The published preview APK is debug-signed, package `com.ether4o4.voodoo.dev`.
It is separate from the original `com.ether4o4.voodoo` release and does not import
that profile. Updating an installed APK in place requires its matching signing key.
Do not uninstall or clear data to resolve a signature mismatch. The unsigned release
APK in the build artifact needs the original owner's signing key for a release update.
