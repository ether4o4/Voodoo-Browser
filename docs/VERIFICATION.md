> Historical Skywork notes from the uploaded package. These counts and APK details do not describe the revised bridge. See [BRIDGE-REVIEW.md](BRIDGE-REVIEW.md) for this review’s actual checks.

# Voodoo 0.3.0 verification

Date: 2026-10-24 (UTC). Baseline:
`98834f52a2d713f8dd65e434069d161e031eb84e`.
Implementation branch: `implementation/ai-userscript-ecosystem`.

## Executed checks

| Check | Actual result |
|---|---|
| Unchanged baseline Node tests | 44 passed |
| Unchanged baseline debug build and lint | BUILD SUCCESSFUL |
| Final Node/DOM tests | 56 passed, 0 failed, 0 skipped |
| Android JUnit tests | 7 passed, 0 failures/errors/skipped |
| Original native scope/endpoint/recent-site harness | 39 assertions passed |
| Final assembleDebug, testDebugUnitTest, lintDebug | BUILD SUCCESSFUL in 41 seconds |
| Debug lint | 0 errors, 21 warnings; not a warning-free build |
| Git whitespace validation | `git diff --check` passed |
| APK manifest and dex | Manifest, three dex files and launchable MainActivity present |
| APK signature | Verified; APK Signature Scheme v2, one debug signer |
| APK native architecture | arm64-v8a only, 13 runtime libraries |
| Model-weight packaging | No GGUF weights in APK |
| Upstream native runtime | Pinned archive SHA-256 verified before extraction |
| ELF inspection | AArch64 runtime; inspected executable LOAD alignment is 16 KiB |
| Greasy Fork reachability | Live public github.com search returned script records with source/version fields |
| Connected ADB targets | None |

The local build used JDK 17, Gradle wrapper 8.13, AGP 8.13.2, SDK platform 36,
and installed SDK build tools. An environment-only Gradle init script disabled
incremental Java compilation after a shared-cache stall. This workaround is not
an app-source change and is not required by the source build contract.

Lint warnings include optional WebView feature-check recognition, deprecated
APIs and UI/resource guidance. They are not proof of runtime safety. Full reports
are included in the downloadable verification package.

## Verified APK identity

- Filename in delivery: `Voodoo-0.3.0-debug.apk`
- Application ID: `com.ether4o4.voodoo.dev`
- Version: `0.3.0-dev`, version code 7
- Minimum Android SDK: 29; target/compile SDK: 36
- Size: 18,796,097 bytes
- SHA-256: `755588843fccad212bb0756fd3b105d018a012e1935a21b40d193f19a96ce44f`
- Debug signing only; this is not a production-signed release.

It installs alongside the original release ID. No installation or launch was
performed in this environment.

## What the automated tests establish

Provider tests use fake HTTP connections to exercise the four configured
OpenAI-compatible providers, model selection/listing, request shape, response
parsing, SSE completion, cancellation, endpoint rules and secret redaction.
DOM tests cover cloud opt-in context, independent persistence failure handling,
stream routing, source proposals and existing Studio/Workbench/home behavior.

Userscript fixtures cover metadata scope, exclusions, grants, native-runtime
message protocol, storage snapshots/Promises/listeners, independent errors,
request aliases, menu commands and reviewable source diffs. JVM tests and source
review cover permission boundaries. These are not adversarial tests running
inside Android WebView.

Local-AI tests verify the pinned model catalog, reference coverage and bounded
prompt construction/retrieval. They do not establish model quality or speed.
`tests/local-ai-benchmarks.json` is a reproducible prompt set, not results.

## Outstanding runtime and acceptance tests

No authorized provider credentials or Android device/emulator were available.
The following remain unverified, and must pass before treating this as a
production-ready ecosystem:

1. Save credentials, restart, switch providers, stream and cancel with each
   actual OpenAI/OpenRouter/Venice/Groq account and valid model.
2. On the S24 Ultra: confirm WebView isolated-world feature support and attempt
   hostile page access to native GM and internal browser bridges.
3. Exercise matching/excluded pages, CSP pages, same/cross-origin subframes,
   document lifecycle, SPA routes, BFCache, manual reload, and tab isolation.
4. Verify native script storage across restarts; update/review/rollback,
   backup/restore, site blocks, native download/clipboard/notification prompts.
5. Complete actual Greasy Fork preview/install/enable/disable/update flows.
6. Download/resume/hash-verify each model; launch the packaged Android runtime;
   generate and explain scripts; cancel/unload under memory pressure.
7. Record cold startup, first token, tokens/second and peak process memory.
   **No hardware benchmarks or successful Android inference runs are claimed.**
8. Complete navigation, tab, favorites/pins, homepage, Studio, Workbench and
   Suno interaction regression on the phone.

See `COMPATIBILITY.md` for exact unsupported APIs, weaker timing guarantees,
notification/tab differences and security limitations. Model download is
optional; ordinary browsing does not require weights.
