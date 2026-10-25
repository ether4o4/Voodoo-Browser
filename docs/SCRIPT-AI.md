# Offline Script AI

## Runtime and models

The APK packages official llama.cpp Android ARM64 CPU binaries from release
`b11868`, commit `78a8ae14be559b45f5f13ddee82c188b683d5d8d`.
The upstream archive SHA-256 is
`a583874feade63177ac7cf12282ebaae0a35d62cffa4a0942c0a07d459dcd76d`.
`tools/fetch-llama-runtime.py` verifies the archive and selects its completion
runtime and CPU libraries. It never downloads weights.

The completion executable is packaged as `libvoodoo-completion.so` in the APK's
extracted native library directory. Native code launches it directly with
`ProcessBuilder`, never a shell or an HTTP server. Shared libraries are loaded
from that same directory. ELF inspection confirms AArch64 binaries, Android
API 28 build notes and 16-KiB LOAD alignment in the inspected upstream runtime.
App execution still requires an actual Android installation test.

Optional downloads:

| Model | Exact bytes | License |
|---|---:|---|
| Qwen2.5-Coder-1.5B-Instruct Q4_K_M | 1,117,320,768 | Apache-2.0 |
| Qwen2.5-Coder-3B-Instruct Q4_K_M | 2,104,932,800 | Qwen Research License; review restrictions |

The catalog pins official repository revisions and SHA-256 values. The
download UI shows progress, pause/resume, verification, selection and deletion.
Incomplete files remain private `.partial` files; only exact-size,
checksum-verified files become available for inference. A server that ignores
Range causes a clean restart of that partial download.

Weights are not in the APK, repository, source bundle or browser backups.
Browsing, scripts and cloud chat do not require any model.

## Behavior

Open Workbench → Script Hub → Offline Script AI, or open AI & Scripts → Scripts.
Download a model, then ask to generate, explain, debug, repair or convert a
userscript. The default local context consists of the current draft and its
script-scoped runtime logs. No webpage snapshot is automatically captured.

Each request:

1. Checks model verification and available memory.
2. Retrieves up to four relevant sections from the bundled versioned reference.
3. Builds a bounded ChatML prompt with explicit untrusted-source/log boundaries.
4. Starts CPU-only, offline inference: four threads, 8,192-token context,
   768-token output cap, temperature 0.2 and fixed seed 42.
5. Streams generated text to the local UI.
6. Releases the process/model on completion, cancellation or memory pressure.

The reference covers metadata, GM APIs, lifecycle/CSP, DOM/selectors, events,
JavaScript, dependency/resources and debugging. It is curated retrieval, not
the whole MDN/Tampermonkey corpus, and no model has been fine-tuned.

Proposed code must pass syntax compilation before the review button appears.
The user sees a source diff and chooses whether to put it in a disabled draft.
Saving/enabling still requires the existing review and native permission flow.
Syntax validation is **not** proof of correctness or safe behavior.

Cloud escalation is a separate user action. It asks permission to prepare a
request with script/diagnostics, then requires Send in cloud chat. No automatic
fallback uploads code, diagnostics or webpage contents.

## Measurement and limitations

“Test load & benchmark” runs a real generation when a model is downloaded.
The resulting receipt stores device/Android, model/runtime, wall time,
first-output time, runtime token-speed statistics and model/KV allocation logs.
Raw allocation logs are not peak process RSS. No unmeasured speed, memory or
startup number is presented as a benchmark.

`tests/local-ai-benchmarks.json` defines eight repeatable coding/repair cases.
Run these with both models before judging output quality or deciding on any
fine-tuning. This release has **not** run either model on a connected Android
device; load/generation, memory pressure and token-speed results remain pending.
No Galaxy S24 Ultra measurements are available.

The model unloads after each request rather than retaining a warm context.
Downloads are foreground-app work, not a background DownloadManager service.
They may pause when Android reports memory pressure; app/process death retains
the partial file for the next resume. A cancelled native model may take a short
time to release memory. Long/complex scripts may exceed context or output caps.
The assistant does not automatically execute behavioral tests for proposed code.
