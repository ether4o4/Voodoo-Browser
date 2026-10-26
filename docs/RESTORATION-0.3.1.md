# Integrated restoration, 0.3.1

This work starts from PR #1 at `7d0895f`, retaining the 0.3 AI adapters, streaming,
isolated userscript engine, Script Hub, optional local model runtime and ChatGPT
browser bridge. It does not deploy a relay or configure an account.

## Recovery evidence

The owning conversation independently inspected the actual 0.2.5 APK and reported
black surfaces, a fifth `favorites` pin group, the Skills & scripts chat sidebar,
script/skill Workbench modes, active skill prompts and voice controls. Those
findings guide this integration. The Windows executor could not materialize the
Library ZIP: the supported helper downloaded to a temporary path but failed with
`AttributeError: module 'os' has no attribute 'setxattr'` and left no readable
destination. No local byte comparison or screenshot comparison with that APK is
claimed. GitHub contains no 0.2.5 tag/release/branch; the reported old APK revision
does not reproduce its uncommitted assets.

## Restored behavior

- Five pin groups share native/JS/backup validation. Favorites retains its fixed
  name; Browse, Create, Build and Saved retain editable names. IDs, settings keys,
  existing records, browser profile and provider key storage remain in place.
- Home has Favorites with add, show-all, retry and empty states. Recent browsing
  remains available. Search clears on focus. Tabs replaces Shortcuts and each tab
  has its own close control. Saved opens favorites, saved sites, bookmarks and
  group naming. No Suno shortcut is added to the bottom controls.
- Workbench opens the integrated script and skill editors. Skills persist with
  draft state, import/export, explicit activation and active prompt inclusion.
  The previous writing tool and its stored data remain accessible as Writing
  archive. Newer script diagnostics, updates, collections and local AI remain.
- Mic performs dictation without sending. Explicit Voice conversation mode sends
  recognized text and speaks replies. Native microphone permission, foreground
  ownership, cancellation epochs and stop-on-navigation limit voice lifetime.
  Device speech services and physical-phone behavior remain unverified.

## Review repairs

Controller names use filtered text, forms use browser-resolved destinations,
route changes invalidate references, inherited disabled state is enforced, and
selection verifies exact option identity. Navigation completion requires expected
URL/start generation plus a new controller document reporting a complete load;
redirects and unrelated navigation fail closed. Cancellation has a bounded relay
slot even when ordinary work is saturated.

Script fixes cover document-body timing, repeated Run once scheduling, session
checks after permission dialogs, request cleanup on pagehide, normalized blocklist
hosts, discovery on actual page paths and cancellation just after publishing the
local AI process. Runtime downloads publish verified cache files atomically and
recover from truncated/interrupted cache entries.

## Validation and acceptance boundary

Local Windows: 79 JS/DOM tests and two mocked Python download tests passed.
Local relay integration cannot be called passed: loopback first required sandbox
approval, then Windows directory-fsync semantics rejected state saves. Linux CI
is required. Native unit tests, APK builds and lint run only in GitHub Actions.
See the exact-head run associated with the delivered commit for terminal results.

No phone/emulator, live account, microphone, provider, signed-in site, model
inference or live ChatGPT MCP test has been performed. The bridge is not declared
safe or production-ready by this change. Black UI is implemented from reported
recovery facts; pixel parity with the approved build remains to be compared.

CI produces `com.ether4o4.voodoo.dev` signed with a development key and an unsigned
`com.ether4o4.voodoo` release APK. An update preserving the installed release
profile requires the owner's original signing key. Do not uninstall or use a new
release key to force an update. No signing credential was configured here.
