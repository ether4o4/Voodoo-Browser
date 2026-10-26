# ChatGPT Browser Bridge

The Android browser keeps signed-in websites on the phone and opens an outbound
WSS connection to a relay. An MCP client sends structured tools through that
relay. This does not require a running PC after deployment, and does not copy
a PC's browser session or make unsupported ChatGPT mobile integrations appear.
See [relay setup](../bridge-server/README.md) for hosting and OAuth configuration.

## Phone setup and controls

1. Pair the relay from Tools → ChatGPT Browser Bridge with its five-minute code.
2. Open Allowed websites and enter the exact hosts you want controlled, such as
   `suno.com` and `www.suno.com`. Subdomains require separate entries. Internal
   browser pages are always excluded. Changing sites pauses work.
3. Resume bridge tasks and return to a website tab. Keep Voodoo visible; split
   screen may allow Voodoo and the MCP client to remain visible together.
4. Pause/disconnect/revoke from the same native settings screen. Opening the
   settings screen backgrounds the browser and pauses work. Resume immediately
   before returning. Startup, backgrounding and connection loss also pause it.

Pairing tokens are encrypted using Android Keystore and bound to the relay
origin. Remote control requires Android System WebView's isolated-world
injection support. If unavailable, controls fail closed; there is no insecure
page-world fallback. Cookies and provider API keys are not tool outputs.
Visible site content and screenshots can contain private information.

## Browser tools

Tabs have stable IDs for their lifetime. Page reads return a document version,
snapshot ID, visible text and up to 300 interactive element references. An
interaction must use those exact references; removed, changed or stale elements
are rejected. Scroll does not require an element reference. Reads are bounded to
20,000 text characters and the main frame; cross-origin frames, shadow DOM and
some custom controls are unsupported.

Typing and selection verify the resulting DOM state. Clicks report only that
an event was dispatched; the client must inspect the page or wait for an
observable result before claiming a playlist or other change was saved.
Navigation waits for a load callback on an allowed site. Downloads, purchases,
or credit spending require explicit user instructions; the generic click tool
cannot infer those business effects by itself.

Actions run one at a time. A command journal records IDs, operation names,
hashes, status and timestamps, without raw text or screenshots. Interrupted
commands become uncertain. Cancel stops queued work and pauses the bridge;
already dispatched site actions cannot be rolled back. Reconnecting does not
automatically repeat or resume writes. After a timeout, read task status and
inspect the page before retrying. Separate tool calls receive separate IDs.

## Verification

Run the root Node tests, relay integration tests, Android unit tests, APK build,
and Android lint. Exact results and physical-device acceptance gaps are recorded
in [BRIDGE-REVIEW.md](BRIDGE-REVIEW.md). Do not treat the uploaded APK or Skywork's
older verification notes as verification of this revised bridge.
