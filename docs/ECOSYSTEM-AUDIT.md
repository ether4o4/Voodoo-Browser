# Ecosystem audit

Audit started 2026-10-24, before implementation.

- Exact repository: https://github.com/ether4o4/Voodoo-Browser
- Baseline branch: `main`
- Baseline commit: `98834f52a2d713f8dd65e434069d161e031eb84e`
- Implementation branch: `implementation/ai-userscript-ecosystem`
- No main-branch edits.

## Reuse decisions

`MainActivity.java` owns eight-tab navigation, asset-origin message routing,
settings, pins, history, downloads and Suno integration. Keep those owners.
`AiClient.java` already provides HTTPS transport for OpenAI-compatible,
Anthropic and Gemini protocols. `SecureKeys.java` already uses Android
Keystore AES-GCM with profile-bound associated data and backup exclusion.
Repair these implementations rather than adding a competing client.

Studio owns the installed scripts array in `studio`, the editor, chat,
import/export and twenty-entry draft history. Workbench owns a separate
versioned songwriting store, with revision checks and locked-bar protection.
Retain both stores and expose the same Script Hub from Workbench.

## Confirmed gaps in baseline

- Chat awaits an entire Studio save before starting HTTP. A stale Studio
  revision prevents sending even though provider settings saved successfully.
- Provider save refreshes profiles but does not select the saved provider
  for a new chat. Model changes require another save; no cancellation UI.
- `AiClient.cancel()` is not routed from the UI. HTTP is non-streaming.
  Numeric bridge request IDs collide across multiple local documents.
- The runner uses `new Function` on website globals and webpage localStorage
  for GM values. Values are website-readable and do not follow a script
  between sites. Every script gets helpers regardless of its grants.
- Native matching rejects include/exclude/require/resource/connect and almost
  all grants. Injection discards errors silently and always excludes frames.
- No native GM request bridge, dependency cache, discovery, installed-version
  history, per-script approval or local model runtime exists.
- Existing console capture is a page-forgeable text log, not a trusted
  script execution health record.

## Baseline checks

- Node/DOM: 44/44 passed, zero skipped.
- First Gradle attempt failed before compilation: JDK absent from PATH.
  Existing workspace JDK 17 / Android SDK reused through the existing env file.
- Android baseline build/lint: recorded in final verification report.
- ADB: no connected device. No Galaxy S24 Ultra / Android 16 testing claimed.
- No user-supplied provider credentials available for live paid API tests.

## Security architecture

Use AndroidX WebKit execution worlds, with one isolated world and native
message listener per script. Native listeners are bound to script identity,
not caller-supplied script IDs. Check origin, installed source revision,
enabled state, site policy and grant on native calls. Keep CSP, SSL validation,
mixed-content policy and internal-origin restrictions intact.

If the installed WebView lacks isolated-world support, privileged scripts must
fail closed with a visible compatibility explanation. A page-world secret or
randomly named global bridge is not an acceptable security boundary.

## Discovery reference

Reviewed Magic Userscript+ commit
`b3c9dfbe4120240f04b2be315ad393be75c40eb6`, including
`src/js/background.js`, `src/js/storage.js`, `src/UserJS/main.js` routing and
MIT license. Port the website-specific Greasy Fork discovery experience into
native Voodoo controls; do not install the upstream extension or its broad GM
permissions. Preserve attribution. Search uses
`https://api.greasyfork.org/scripts/by-site/{host}.json?language=all`.
An unauthenticated live query for github.com returned script records with
name, description, version, source URL and code URL during this audit.
