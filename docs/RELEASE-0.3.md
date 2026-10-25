> Historical Skywork notes from the uploaded package. These counts and APK details do not describe the revised bridge. See [BRIDGE-REVIEW.md](BRIDGE-REVIEW.md) for this review’s actual checks.

# Voodoo Browser 0.3.0 development release

Implementation branch: `implementation/ai-userscript-ecosystem`
Baseline: `98834f52a2d713f8dd65e434069d161e031eb84e` on `main`.
Target: Samsung Galaxy S24 Ultra, Android 16, ARM64.

## Implemented

- Existing cloud client repaired: saved-provider selection for new chats,
  endpoint-bound encrypted keys, independent network sending when Studio
  persistence fails, OpenAI-compatible SSE, unique document request IDs,
  cancellation, time limits and visible errors. Anthropic/Gemini paths retained.
- Native isolated-world userscript coordinator, source approval, grants,
  per-script native storage/listeners, permission-checked networking, resources,
  commands, basic downloads/clipboard/notifications/tab operations.
- Native bottom-right website discovery launcher, Greasy Fork results,
  metadata/source preview, disabled installation and installed controls.
- Existing Studio editor reused as Script Hub, also reachable from Workbench:
  source diffs, diagnostics, health/logs, site blocklists, source updates,
  installed-version rollback, enabled collections and extended backups.
- Optional offline ARM64 coding runtime, pinned Qwen downloads, local reference
  retrieval, proposal review, cancellation/unloading and actual-run measurement UI.

## Install

Unzip the delivered installer package and install `Voodoo-0.3.0-debug.apk`.
The debug application ID is `com.ether4o4.voodoo.dev`, so it installs alongside
the existing release browser. It does not replace or clear the release profile.
Export/import a browser backup if desired; provider keys must be re-entered,
and imported scripts start disabled.

Keep Android System WebView current. Open Script Hub → Health & versions to
check isolated-world availability. If unavailable, userscripts fail closed;
normal browsing and cloud chat remain available.

## Verification and exact outstanding work

Final automated test/build counts are recorded in `VERIFICATION.md`.

No authorized OpenAI, OpenRouter, Venice or Groq credentials were supplied.
Tests exercise request formats, parsing, error redaction and cancellation with
test transports; they are **not successful live paid-provider tests**.

No ADB device/emulator was connected. Therefore the following remain unverified
on Android hardware: actual isolated-world bridge separation, subframe/timing
behavior, CSP-site execution, native clipboard/download dialogs, Keystore
restoration, process execution of llama.cpp, model download/resume and inference,
Galaxy S24 Ultra resource consumption, and complete navigation/pin/Workbench
interaction regression. The automated fixtures are not substitutes.

This is not full Tampermonkey compatibility. See `COMPATIBILITY.md` for the API
matrix and timing/notification/tab/storage differences. `SCRIPT-AI.md` records
the offline runtime architecture, license distinctions and benchmark limits.

## Rebuild

Use JDK 17, Android SDK 36, Python 3.11+ and Node 22+:

```sh
npm ci
npm test
python3 tools/fetch-llama-runtime.py
./gradlew testDebugUnitTest assembleDebug lintDebug
```

An ARM64-capable `llvm-strip` or `aarch64-linux-gnu-strip` reduces native debug
symbols; `VOODOO_STRIP` can select it explicitly. Without it, the pinned runtime
still packages but is larger. The generated JNI files are ignored by Git and
recreated from the verified upstream archive. No model weights are needed to build.

Existing CI now fetches the pinned native runtime and runs native unit tests,
Node/DOM regression tests, Android builds and lint. No CI job downloads weights,
uses provider credentials, or claims hardware tests.

## Source delivery and GitHub status

The implementation was committed on the dedicated branch; `main` was not edited.
GitHub rejected push with HTTP 403 both through the configured Git credential
helper and through the existing GitHub CLI credential helper. Therefore no
remote implementation branch, pull request or new GitHub Actions run was created
by this delivery. Repository permission metadata alone did not establish usable
push authorization.

The delivery includes a complete Git bundle, source ZIP and commit patch:

```sh
git clone --branch implementation/ai-userscript-ecosystem \
  Voodoo-Browser-source.bundle Voodoo-Browser
cd Voodoo-Browser
git remote set-url origin https://github.com/ether4o4/Voodoo-Browser.git
git push -u origin implementation/ai-userscript-ecosystem
gh pr create --base main --head implementation/ai-userscript-ecosystem \
  --title "Native userscripts, chat fixes, discovery and optional Script AI" \
  --body-file docs/PULL-REQUEST.md
```

Run the final two commands only after granting an account the necessary
repository and workflow-write authorization. Nothing is merged automatically.
