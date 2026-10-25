# Browser Bridge review — 2026-10-25

Baseline: `98834f52a2d713f8dd65e434069d161e031eb84e` in `ether4o4/Voodoo-Browser`.
Reviewed inputs: the supplied source ZIP, relay ZIP and debug APK ZIP. The source
also contains Skywork’s AI/userscript ecosystem changes; those are retained.
Attached documentation was treated as provenance, not instructions.

## Blocking issues corrected

- The relay used an unadvertised static bearer secret rather than an OAuth flow
  usable by a pre-registered MCP client. It now uses the official MCP SDK,
  protected-resource discovery, PKCE S256, owner consent, resource-bound tokens,
  rotation and revocation. This is one owner per deployment, not multi-tenancy.
- Pairing now uses 64 random bits, one-use expiry, bounded capacity and rate
  limits. Device tokens are hashed at rest, response ownership is checked, old
  socket closure cannot erase a replacement socket, and phone revocation also
  revokes the server credential.
- Browser tools validate strict inputs and cannot override the operation through
  extra arguments. Requests are size/time bounded. Pending results are registered
  before dispatch. Timeouts/disconnects produce uncertain outcomes without retry.
- Native remote control is confined to isolated execution worlds and explicit
  HTTPS site permissions. No arbitrary JavaScript, cookie or key retrieval tool
  is exposed. Password/payment/OTP/file fields are excluded from control.
- Snapshots keep exact DOM nodes and verify identity/state before interaction.
  Scroll no longer incorrectly requires an element reference. Type/select verify
  resulting state; click reports event dispatch and requires outcome inspection.
- Work is serialized and journaled before execution. Pause, background, restart,
  disconnect and cancellation stop queued actions; a previously dispatched site
  effect cannot be undone. Separate tool calls are not automatically deduplicated.
- Replaced the relay’s source-string test with real HTTP/OAuth/MCP/WebSocket
  integration tests; added DOM interaction and JVM policy coverage.
- Added relay tests to Android CI and corrected outdated setup/verification claims.
  CI now requests explicit SDK packages; its old default requested the removed
  `tools` package and failed before reaching the build.

## Checks actually executed

| Check | Result |
|---|---|
| Browser/DOM tests | 65 passed, 0 failed or skipped |
| Relay integration tests | 8 passed, 0 failed or skipped |
| Web asset build | Passed; existing globe bundle reproduced unchanged |
| Production dependency audits | No reported npm vulnerabilities in root or relay dependencies |
| Native runtime fetch | Pinned llama.cpp archive SHA-256 verified; 13 ARM64 libraries installed; no model weights downloaded |
| Uploaded APK | Signature v2 verified; development application ID `com.ether4o4.voodoo.dev`, version `0.3.0-dev`/7, min SDK 29, target SDK 36 |
| Android JVM tests | 10 passed, 0 failures/errors/skipped |
| Original native harness | 39 scope/endpoint/recent-site assertions passed |
| Debug and unsigned release builds | Passed with JDK 17, Gradle 8.13, AGP 8.13.2 and SDK 36 |
| Release Android lint | Passed: 0 errors, 31 warnings (not warning-free) |
| Rebuilt APK inspection | Debug signature verified; 13 ARM64 libraries, no model weights; final controller asset checked against source |
| Source whitespace and secret-pattern scan | Passed; no matching private keys or credential patterns |

Lint warnings include synchronous durability commits, feature-check recognition, existing API/resource guidance and untranslated native UI strings. Physical WebView security and lifecycle behavior still require acceptance testing.

The uploaded APK is an input, not a build of the revised source. It is not
published as the reviewed build. CI produces a fresh development APK and unsigned
release APK; a release update still needs the original private signing key.

## Acceptance work still needed

No Android device/emulator or live ChatGPT custom MCP connection was available.
Do not call this a production-verified mobile bridge yet. Verify on the actual
phone: isolated-world support and page callbacks, pairing/revocation persistence,
split-screen visibility, pause/background/resume, navigation and SPA changes,
provider-key/profile preservation, and an end-to-end read-only MCP session.
Then test an explicitly authorized reversible website edit, inspect the saved
result, interrupt/reconnect, and confirm it was not repeated.

Shadow DOM, subframe control, trusted user gestures, custom canvas controls,
background execution and horizontal relay replication are not supported. The
relay must be deployed behind trusted HTTPS. ChatGPT account/mobile availability
must be checked with the actual client; adding Android code cannot enable a
custom MCP feature that the client does not expose. No relay was deployed, no
provider requests were made, and no Suno downloads, generations or edits were
performed during this review.
