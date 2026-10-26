# Voodoo 0.3 userscript compatibility

This is a native Android userscript engine with a documented subset of
Tampermonkey APIs. **It is not full Tampermonkey compatibility.** Compilation
and simulated tests do not establish physical WebView behavior.

## Execution boundary

- AndroidX WebKit 1.18.0, `JS_INJECTION_IN_FRAME_AND_WORLD`, one world per script.
- Page JavaScript cannot access another world's native message listener.
  Listener identity is bound by native registration, not supplied by a page.
- Each native request rechecks installed source hash, enabled state, verified
  frame origin, matching URL, noframes, site block and applicable grant.
- Internal browser origins are always excluded. There is no insecure fallback
  to an `addJavascriptInterface` bridge or a secret page-world variable.
- Userscript source and approved dependencies are inserted as literal functions,
  not evaluated from strings in a website's execution context.
- Syntax compilation uses a separate network-blocked, bridge-free blank WebView.
  This compiles code without executing its body; it is not a behavioral test.
- CSP, TLS validation and mixed-content protections stay enabled. DOM-created
  style/script/resource elements may still be restricted by website CSP.

If isolated worlds are unavailable in the installed Android System WebView,
scripts are marked **incompatible**, not silently run with weaker isolation.
Update WebView and check Script Hub's capability report.

## Metadata matrix

| Metadata | Status / qualification |
|---|---|
| name, namespace, version, description | Parsed, retained and exposed in script information |
| match, exclude-match | HTTP(S), wildcard host/path and all_urls; case-sensitive path matching |
| include, exclude | Glob and slash-delimited regex; bounded pattern length |
| run-at | document-start, document-body, document-end, document-idle |
| grant | Recognized helpers only; unknown grants reported; none cannot combine with helpers |
| connect | Explicit HTTPS destination permission, domain/subdomain, self and wildcard |
| noframes | Main-frame-only when present; matching frames otherwise eligible |
| require | HTTPS downloads on execution approval; cached, hash-recorded, ordered inclusion |
| resource | Named text/binary resources cached on approval |
| updateURL, downloadURL | Explicit checks; metadata version comparison, review before installation |
| integrity fragments | sha256 hex/base64 supported; other supplied algorithms rejected |
| sandbox, unwrap, webRequest | Incompatible directives; no silent emulation |
| localized metadata, run-in, context-menu | Not implemented as Tampermonkey features |

Native origin registration is coarser than path matching; runtime and native
message checks enforce the full URL scope. Custom ports, regex edge cases,
cross-origin frames and Android WebView lifecycle behavior need device testing.

## GM API matrix

Both naming families are exposed for supported helpers. `GM.xmlHttpRequest`
and legacy `GM_xmlhttpRequest` are supported spellings.

| API | Implemented behavior | Differences / limits |
|---|---|---|
| getValue / setValue / deleteValue / listValues | Native per-script JSON storage across sites, tabs and restarts | 1 MB/script; no functions, cycles, DOM nodes or stored undefined |
| Legacy storage | Synchronous reads from initialized instance snapshot; writes queued to native | A legacy write has no durability acknowledgement; await modern setValue |
| Modern storage | Promise operations; getValue/listValues refresh native state | Not a cross-call transaction |
| getValues / setValues / deleteValues | Bulk convenience helpers | Writes are per-key, not atomic; bulk reads use snapshot |
| addValueChangeListener / removeValueChangeListener | Script-key listeners and remote-instance flag | Live instances only; no background service wakeup |
| xmlhttpRequest / xmlHttpRequest | Native HTTPS, methods, safe headers, body, redirects, response type, abort | Cookie-free; no browser credentials, synchronous mode, fetch mode, upload/stream progress, partitioned cookies or request interception |
| Request response types | text, json, arraybuffer, blob, document | Text decoding UTF-8; detached HTML parser for document; readyState callback at completion only |
| Request callbacks | onload, onerror, ontimeout, onabort | 1 MB request / 4 MB response; 60-second native deadline |
| getResourceText / getResourceURL | Cached resource text or data URL | Modern spelling is getResourceURL; getResourceUrl alias not provided |
| addStyle / addElement | DOM element creation | No CSP bypass; returned DOM element is in the shared document |
| download | Confirmed HTTPS transfer, redirect checks, Android Downloads storage | 4 MB; @connect required; abort applies after transfer starts; no saveAs chooser/advanced options |
| setClipboard | Confirmed plain-text native clipboard write | No HTML/image clipboard; text only |
| registerMenuCommand / unregisterMenuCommand | Native discovery panel → Commands | Basic label/callback only, no icons, access keys or options updates |
| notification | In-app native dialog | Not Android system notifications; no persistent tag/image/click-equivalent semantics |
| info / log | Metadata snapshot and script-scoped diagnostics | Selected info fields, not every Tampermonkey field |
| openInTab | Confirmed ordinary-website tab; close only an instance-owned tab | Existing 8-tab limit; no full tab lifecycle/onclose/saveTab APIs |
| unsafeWindow, cookie, webRequest, audio, getTab/saveTab/getTabs | Not supported | Reported incompatible when declared as grants |

## Lifecycle and management limitations

`document-start` is the earliest native injection point, but script source waits
for a native storage/resource handshake. **Strict synchronous execution before
all page scripts is not guaranteed.** No historical DOM events are replayed.
Document-idle is a queued post-DOMContentLoaded task, not browser-idle detection.

Scripts start once per document. An unmatched SPA may become matched later.
An already-started script is not automatically restarted on every route change:
use a MutationObserver or `voodoo:urlchange` and idempotent initialization.
That event is a Voodoo extension, not Tampermonkey's `window.onurlchange`.
BFCache restores and background suspension require device verification.

Disabling, editing permissions or changing source revokes native access and
future injection. It cannot undo DOM edits, arbitrary timers or event handlers
already created by script code. Reload affected pages. Manual execution asks to
reload and schedules one approved run; early timing on subsequent navigation
must be verified on-device.

Installed source remains in the original Studio store. Workbench opens that same
Script Hub. Source versions (up to 20/script), disabled rollback, source diffs,
collections, per-site blocklists, logs and execution status are implemented.
Health duration measures synchronous script entry, not all asynchronous work.
The installed Studio store is capped at 5 MB and 100 scripts. Dependency caches
and retained histories can consume additional private storage; unused dependency
cache collection is not implemented in this version. Backups are capped at
12 MB, so large collections may need to be exported in smaller groups.

Discovery ports the Magic Userscript+ website-search workflow, not its whole
extension implementation. Searches return up to 100 displayed Greasy Fork
records per host. Exact-path compatibility is verified during preview/execution.
No discovery action automatically installs or enables a script. Dependency
content is approved through its declared URLs and then hash-recorded; dependency
updates are not independently auto-installed.

Optional automatic update checks run on app resume, at most daily, and never
install changes. They are not Android background jobs when the app is closed.
Source/version comparison is not a universal semantic-version ordering engine.

Backups include native script values and installed version history; restored
scripts are disabled and require approval again. Old website-localStorage GM
values from 0.2.4 are not silently migrated because webpages could modify them.
Provider keys, website login cookies and downloaded models are not exported.

## Security limits

Installed userscripts are user-approved executable code. They can manipulate
their matched DOM, and granted network access can transmit page data. Voodoo
does not claim to detect every malicious script or defend against WebView engine
vulnerabilities. Review script source, dependency URLs and broad grants carefully.
The host-native invariants are tested/reviewed, but device adversarial testing
remains outstanding.
