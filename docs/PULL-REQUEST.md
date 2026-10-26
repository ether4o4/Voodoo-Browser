> Historical Skywork notes from the uploaded package. These counts and APK details do not describe the revised bridge. See [BRIDGE-REVIEW.md](BRIDGE-REVIEW.md) for this review’s actual checks.

## Summary

Extends the existing native Java browser and Studio store without redesigning
navigation or introducing a second cloud AI client/script manager.

- Repair provider initialization/selection, secure endpoint-bound key storage,
  streamed responses, cancellation, timeouts and visible errors.
- Replace the old page-context evaluator with native isolated-world userscript
  coordination, permission approval, native storage and grant-enforced networking.
- Port Magic Userscript+ website discovery into native Greasy Fork
  preview/install/manage controls with MIT attribution.
- Extend the existing Script Hub with logs, health, site blocks, profiles,
  disabled update proposals, rollback and backups.
- Add a separate optional ARM64 llama.cpp coding assistant with verified Qwen
  downloads, local reference retrieval and review-only code proposals.
- Include an API compatibility matrix, reproducible runtime fetch and tests.

## Verification performed

- 56 Node/DOM tests passed.
- 7 Android JUnit tests passed.
- 39 original native regression assertions passed.
- assembleDebug, testDebugUnitTest and lintDebug passed; lint has 21 warnings.
- Debug APK signature, manifest, ARM64 runtime and absence of model weights checked.
- Exact unchanged baseline also built and passed its original 44 Node tests.

## Acceptance limits

Not full Tampermonkey compatibility. No physical device or emulator was
available. No authorized live provider calls or Android inference were performed.
Document-start cannot guarantee execution before every page script; several
advanced GM features and browser-equivalent notification/tab semantics are
unsupported. Actual isolation, lifecycle/CSP behavior, model execution and
Galaxy S24 Ultra performance remain mandatory device acceptance checks.

See `docs/VERIFICATION.md`, `docs/COMPATIBILITY.md`, and `docs/SCRIPT-AI.md`.
The debug package installs alongside the release app. No weights or provider
credentials are included.
