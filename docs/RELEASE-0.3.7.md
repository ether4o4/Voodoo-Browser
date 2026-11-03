# 0.3.7 — Chrome extensions

Built on the complete 0.3.6 browser. The only addition is Chrome extension support.
Existing tabs, scripts, pins, settings and the browser UI are unchanged. The only
visible change is a new **Extensions** entry at the end of the Tools menu.

- Install from the Chrome Web Store (signature and ID verified) or from a `.crx`/`.zip`
  file, after a permission review.
- Supports Manifest V2 and V3: background pages and service workers, content scripts
  in isolated worlds, popups, options pages, side panels, messaging and ports,
  storage, tabs, scripting, alarms, context menus, cookies, i18n, offscreen documents
  and declarativeNetRequest blocking.
- Version 0.3.7, code 14. No existing profile keys or schemas are migrated or cleared;
  extension data uses new private folders.

See [extension support, API coverage and limits](EXTENSIONS.md). Some Chrome APIs
(webRequest blocking, identity, native messaging, DNR redirects/header edits) have no
WebView equivalent and are documented as unavailable.

## Verification

CI runs the existing web/DOM, bridge-server, Python and Android unit tests, debug and
release builds, lint and APK checks. Added tests cover CRX signature verification,
safe unpacking, manifests, locales, match patterns, network rules and the `chrome.*`
API layer. Real uBlock Origin Lite and Dark Reader packages from the Web Store
verified and parsed, and the uBlock Origin Lite rules blocked known ad and analytics
scripts in a desktop JVM check.

Not yet verified on a phone: run uBlock Origin Lite and Dark Reader, open a popup and
options page, check a content script on a site with a strict CSP, and confirm
extension state survives an app restart.

## Installation

The published preview APK is debug-signed, package `com.ether4o4.voodoo.dev`.
It is separate from the original `com.ether4o4.voodoo` release and does not import
that profile. Updating an installed APK in place requires its matching signing key.
Do not uninstall or clear data to resolve a signature mismatch.
