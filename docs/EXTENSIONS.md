# Chrome extensions (0.3.7 preview)

Voodoo can install and run Chrome extensions (Manifest V2 and V3). Android System
WebView has no extension engine of its own, so this is Voodoo's own runtime for the
Chrome extension format and `chrome.*` APIs. The most widely used APIs are implemented.
Some APIs have no WebView equivalent and are stubs. Check the table before relying on
an extension. **This has not been tested on a physical device yet.**

## Installing

**Tools → Extensions**

- **Add from Chrome Web Store:** open the extension's page on
  `chromewebstore.google.com`, then choose this option. The link is filled in
  automatically, or you can paste a link or the 32-letter ID. Voodoo downloads the
  package from Google's update server. It installs only if the CRX3 signature is valid
  and proves the requested extension ID.
- **Install .crx / .zip file:** CRX2/CRX3 signatures are verified. A ZIP of an unpacked
  extension is marked **unsigned**, because its origin cannot be checked.
- Before installation, a review screen lists the sites and APIs the extension can use.
  Nothing installs until you approve it.

Tap an installed extension for its popup or action, side panel, context-menu items,
commands, options page, enable/disable, details (permissions, network-rule count,
recent errors), Web Store update and removal. Badge text appears next to its name. No
toolbar, address bar or other browser UI was changed. Reload open pages after adding
or enabling an extension; Chrome also requires this.

Extension files are stored in app-private storage (`files/extensions/<id>`). Their
storage data is kept separately (`files/extension-data/<id>`), so updates keep it.
Removing an extension deletes both.

## How it runs

| Part | Implementation |
|---|---|
| Extension origin | `https://<id>.ext.voodoo/`, served from the installed files by request interception (never fetched from the network). `chrome.runtime.getURL` returns these URLs; `chrome-extension://<id>/` in CSS and URLs is rewritten. |
| Background | One persistent hidden WebView per extension: the MV2 background page or scripts, or the MV3 `service_worker` (classic or `type: module`) run as a page script. `importScripts` is provided. It is never suspended. |
| Content scripts | `js`, `css`, `matches`, `exclude_matches`, `include_globs`, `exclude_globs`, `all_frames`, `run_at` and `world: MAIN`. They run in a separate AndroidX isolated world per extension, which page JavaScript cannot reach. |
| Web-accessible resources | Websites can load only listed resources. An extension's own pages can load all of its files. |
| Popups, options, side panel | The popup and side panel open in a dialog; the options page opens in a tab. `window.close()` closes the popup. |
| Network rules | `declarativeNetRequest` static rulesets, dynamic and session rules. Actions: `block`, `allow` and `allowAllRequests`. Conditions: `urlFilter`, `regexFilter`, request and initiator domains, resource types, methods and `domainType`. Matching is indexed by host, initiator and URL keyword. |

## API coverage

| API | Status |
|---|---|
| `runtime` | `id`, `getURL`, `getManifest`, `sendMessage`/`onMessage` (sync, `return true`, promise replies), `connect`/`onConnect` ports, `onInstalled` (install, update, previousVersion), `onStartup`, `lastError`, `openOptionsPage`, `getPlatformInfo`, `reload`. Promise and callback styles both work. |
| `storage` | `local`, `sync`, `session`, `managed` (read-only, empty), `onChanged`, `getBytesInUse`. Local storage holds 10 MB, or 200 MB with `unlimitedStorage`. `sync` is stored only on this device. |
| `tabs` | `query`, `get`, `getCurrent`, `create`, `update`, `remove`, `reload`, `goBack`/`goForward`, `sendMessage`, `connect`, `executeScript`/`insertCSS`/`removeCSS` (MV2), `captureVisibleTab`, and `onCreated`/`onUpdated`/`onActivated`/`onRemoved`. Without the `tabs` permission or host access, tab URLs and titles stay hidden, as in Chrome. |
| `scripting` | `executeScript` (files, func + args, isolated or MAIN world, frames), `insertCSS`, `removeCSS`, `registerContentScripts`/`update`/`unregister`/`getRegisteredContentScripts`. Files and functions run without `eval`, so a site's CSP cannot block them. Promise results are awaited. |
| `declarativeNetRequest` | Supported as described above. `redirect`, `upgradeScheme` and `modifyHeaders` cannot be expressed through WebView interception. They are counted and shown under Details, not applied. Resource types are inferred, because WebView does not report the request destination. |
| `action`/`browserAction`/`pageAction` | Popup, `onClicked` (**Run on this page**), badge text, title and enable/disable state. Icons are not drawn. |
| `contextMenus`/`menus` | Items are saved and shown as **Menu:** entries in the extension's menu, which fire `onClicked` with the current page. Long-pressing a page does not show them. |
| `alarms`, `i18n`, `permissions`, `cookies`, `downloads.download`, `notifications` (shown as toast), `offscreen`, `sidePanel`, `commands` (run from menu), `windows` (one window), `webNavigation` (main frame), `management.getSelf`, `idle` | Implemented or adapted as noted. |
| Extension-page `fetch()` | Cross-origin requests to hosts the extension may access go through native networking with the site's cookies, without CORS, like Chrome. XMLHttpRequest is not proxied. |

## Known limits

- `webRequest` events never fire. Blocking `webRequest` (MV2 blockers) cannot work in
  WebView; use the extension's MV3/DNR version instead (for example uBlock Origin Lite).
- `chrome.identity`, native messaging, `devtools`, `debugger`, `proxy`, `tabCapture`,
  `desktopCapture`, `chrome_url_overrides` (new-tab pages) and cross-extension
  messaging are not available. `history`, `bookmarks`, `topSites` and `sessions`
  return empty results.
- Content scripts in different manifest entries do not share top-level variables.
  Files within one entry do.
- MAIN-world `executeScript` runs in the top frame only.
- Background pages stay loaded instead of being suspended, which uses memory for each
  enabled extension.
- The 8-tab limit applies to tabs opened by extensions.
- The Web Store does not return some extensions to non-Chrome requests (HTTP 204).
  Install those from a `.crx` file.
- Extensions can read and change pages they have access to, including what you type.
  Voodoo verifies signatures but does not review extension code. The runtime has not
  been independently security-audited.

## Verification

- JVM tests: real CRX3 construction, signature verification, tamper and wrong-ID
  rejection, ZIP traversal and nested-manifest handling, manifest comments and locales,
  match patterns and network-rule decisions.
- Node/DOM tests: API surfaces for content scripts and extension pages, messaging and
  lastError, async replies, ports, storage events, content-script matching and timing,
  the script-execution callback and alarms.
- Real Web Store packages: uBlock Origin Lite and Dark Reader CRX3 files downloaded
  from Google's update server verify, and their IDs are proven by signature. uBlock
  Origin Lite's 17,940 default rules load, block Google ad and analytics scripts, and
  leave ordinary scripts alone. On a desktop JVM a decision averages about 13 µs.
  930 rules use unsupported redirect/header actions.
- **Not yet verified:** behavior in Android System WebView on a phone (isolated
  worlds, background-page timers, popups, real extension UIs). Device testing is required.
