# Voodoo Browser

## Latest download — rolling updates

- [Latest Android preview APK](https://github.com/ether4o4/Voodoo-Browser/releases/download/mobile-preview/Voodoo-Browser-mobile-preview.apk)
- [Release notes and SHA-256 checksums](https://github.com/ether4o4/Voodoo-Browser/releases/tag/mobile-preview)
- [Desktop companion and Windows downloads](https://github.com/ether4o4/Voodoo-Browser-desktop#latest-download--rolling-updates)

These links stay the same as preview builds are updated. The APK is a development
build (`com.ether4o4.voodoo.dev`), installed separately from the original release.
It does not replace or import your original browser profile. A data-preserving
release update requires the original signing key. Preview builds have not received
an independent security audit; keep high-value credentials in your existing browser.

## 0.3.4 — mobile script discovery

Magic scripts now searches the site's registrable domain: `m.youtube.com` and
`www.youtube.com` search `youtube.com`; mobile Facebook searches `facebook.com`.
The existing OkHttp public suffix list handles domains such as `bbc.co.uk` and
keeps separate `github.io` tenants separate. Refresh bypasses the shared domain
cache, and the panel names the catalog being searched. Installed script URL
rules and permissions continue to apply to the exact page. See
[fix and validation details](docs/RELEASE-0.3.4.md).

## 0.3.3 — encrypted desktop link

Tools → Device link pairs with the Windows companion on the same Wi-Fi or phone
hotspot. Paste a five-minute code from the PC, then use **Sync now**. Share selected
HTTPS bookmarks, immutable note snapshots and tabs. Received tabs open only when
you choose them. **Tools → Send tab to PC** queues the current website and opens
the link screen to send it. Both apps must be open, and the PC's link must be enabled.

The connection pins the PC's certificate and encrypts shared payloads with
AES-256-GCM, with durable replay counters. Pairing keys use Android Keystore-backed
storage. Website cookies, API keys, saved passwords and browser profiles are never
part of the sync schema. Sync cancels when this screen is backgrounded; no service
or periodic job is installed. Notes are selected snapshots, not automatic archive
replacement. See [pairing, safety limits and recovery](docs/DESKTOP-LINK.md).

## 0.3.2 — Suno section removed

The dedicated Suno section, native draft filling, floating panel and automatic
generation loop have been removed. Writing, saved versions, AI chat and scripts
remain available. Existing stored prompts, presets and attempt notes are preserved
in writing backups; this update does not erase saved data. Suno remains accessible
as an ordinary website.

See [removal notes](docs/RELEASE-0.3.2.md).

## 0.3.1 integrated restoration

This branch restores the black interface, Favorites, editable Saved/pin group names,
per-tab close controls and the integrated Skills & scripts Workbench. It preserves
cloud AI streaming, provider settings, Script Hub, isolated userscripts, the optional
local coding model and the browser bridge. No relay or credentials are configured.

- [Restoration evidence, review repairs and acceptance limits](docs/RESTORATION-0.3.1.md)
- [Release and rebuild instructions](docs/RELEASE-0.3.md)
- [Userscript compatibility and security boundaries](docs/COMPATIBILITY.md)
- [Offline Script AI, model licensing and benchmarks](docs/SCRIPT-AI.md)

CI builds a signed development package (`com.ether4o4.voodoo.dev`) and an unsigned
release package. Updating the installed release while keeping its data requires
the owner's original signing key. These artifacts are not a same-key release update.

A personal Android browser with built-in AI chat, an editable userscript workspace, a writing archive and reusable skills.

The app is new code built around **Android System WebView**, the Chromium-based web engine supplied by Android. It is not a patched Firefox APK or a new rendering engine. The homepage banner reads **“Voodoo browser”**; a transparent cutout of the supplied embroidered globe sits above it. The interface uses black surfaces with cyan, pink, green, gold and mint accents.

## What is built in

- **Favorites and recent websites:** home shows saved Favorites and the six most recently visited sites. Tabs has an individual close control for each tab; Saved opens Favorites, Saved, bookmarks and group naming.
- **Workspace pins:** a persistent pin bar with cyan, pink, green, gold and Favorites groups, saved favicons when available, and custom workspace names. Open a whole workspace, move pins between groups, or unpin while keeping the bookmark.
- **Plush globe:** a bundled Three.js globe with cloth bump mapping, stitched land patches, orbiting all-caps lettering, and pin insertion animation. Drag to rotate, select a pin, or place it on the globe. Automatic animation is capped at 30 fps, direct dragging at 60 fps. Rendering stops entirely while idle, offscreen, or in the background; reduced-motion preferences are respected. A static cutout and the pin list remain usable if WebGL is unavailable.
- **AI chat:** Venice, OpenAI, OpenRouter, Groq, Anthropic, Gemini, and custom HTTPS API endpoints. Each preset saves its own key and model. OpenAI-compatible, Anthropic Messages, and Gemini GenerateContent adapters are implemented. Model lists load from the configured provider.
- **AI-assisted scripts:** complete generated userscripts appear in the draft editor. Review, save, enable, or run once on the target website. Drafts and recent draft history save locally.
- **Userscript workspace:** import/export `.user.js`; per-script switches; `@match`, `@exclude-match`, `@run-at`; local GM value/style helpers. This is a supported subset of Tampermonkey behavior, not the Tampermonkey extension or a full extension platform.
- **Skills Workbench:** persistent skills, editable drafts, import/export and explicit activation in chat; script and skill editor modes share one workspace. Mic dictates without sending; explicit Voice conversation sends and speaks. Device speech services still need phone acceptance.
- **Writing archive:** lyric checks, protected lines, saved versions and JSON backups. Previously saved prompts, presets and attempt notes are retained in backups.
- **Browser tools:** eight tabs (saved background pages load only when selected), bookmarks, site notes, reader view, clean-link copy, find, text zoom, desktop mode, share, PDF printing, HTTPS downloads, file selection, and a keep-screen-awake switch.
- **Persistence:** settings, scripts, drafts, provider keys and the browsing profile remain when a newer APK with the same application ID/signing key is installed over the existing app.

## Mobile fit and performance

The 0.2.1 mobile pass contains narrow-screen grid sizing, 48 px touch targets for small action controls, scrolling dialogs that fit the available viewport, and deferred restoration of background tabs. The globe uses a demand-driven renderer with capped pixel density and animation rates; paused, unchanged frames do not keep a render loop running. These are implemented improvements, not measured battery or frame-rate benchmarks. Device testing is still required for keyboard behavior, Android display/font scaling, WebView rendering, and long sessions on the owner's phone.

The 0.2.3 interface pass makes search easier to reach, uses plain section names, groups browser tools, and hides the empty pin bar. Pink, seafoam, cyan, and gold distinguish key headings, saved sites, tabs, and labels while body text stays neutral. The launcher uses the transparent homepage PNG directly and includes Samsung's icon-frame opt-out metadata. Samsung launcher/theme behavior still needs confirmation on the owner's phone; the app does not substitute a filled adaptive-icon tile.

## First use

1. Use a build signed with the original release key to update the installed release. The CI development APK has a separate app ID and does not read the release profile.
2. Open **Tools → AI chat → API keys**, choose a provider, enter its key, select a model and save.
3. Open **AI chat** or **Workbench** from Tools. Page context and script source are shared only when explicitly selected for that request. An activated skill is included in the chat prompt.
4. Review generated scripts in **Workbench → Script editor**. Use **Run once on target**, or save with automatic execution enabled for matching sites. Review skills in **Skill editor** before activating them.

The supplied artwork is bundled. **Settings → Homepage image** lets you choose another image or restore the Voodoo cutout. Pin a website using **Tools → Bookmarks & notes → Pin / save bookmark**, **Pin a site** on the homepage, or **＋** in the browser pin bar once it is visible. Long-press a bar pin to move or unpin it. The homepage adds editing, workspace naming, and **Place on globe**. Positions are personal layout choices; the app does not infer your location or route traffic through the selected point. There is no built-in VPN or proxy service.

Recent websites start filling as you browse after installing 0.2.4. Only successful external HTTPS visits are recorded, with one entry per host and 24 sites retained locally. The homepage shows the newest six. Internal Voodoo pages and failed loads are excluded; recent visits are not included in exported tool backups.

## Keys, scripts, and updates

API keys are encrypted with Android Keystore and used by native HTTPS networking. Website JavaScript cannot read them. Keys are excluded from exports and Android backup/transfer. Redirects are rejected before forwarding credentials. Changing an API base URL requires re-entering its key.

Userscripts now use native isolated WebView execution worlds, reviewed permissions, native storage and grant-scoped networking. Internal browser pages are excluded. See [compatibility limits](docs/COMPATIBILITY.md) for the supported GM APIs and timing differences. Script Hub adds optional Greasy Fork discovery and an optional local ARM64 coding assistant; model weights are downloaded only on request.

The **private signing recovery archive supplied separately** is needed to sign future release updates. Never commit it, its password, provider keys, or personal backups here. Increase `versionCode` for updates and keep `com.ether4o4.voodoo` and the signing key unchanged. Engine updates come through Android System WebView; app/tool updates come through new Voodoo APKs. There is no automatic app updater in this version. Uninstalling or clearing app data deletes the local profile.

## Build

Requires JDK 17 and Android SDK platform 36/build tools. Gradle 8.13 and Android Gradle Plugin 8.13.2 are pinned.

```sh
npm ci
npm run build:web
npm test
npm ci --prefix bridge-server
npm test --prefix bridge-server
python3 tools/fetch-llama-runtime.py
./gradlew testDebugUnitTest assembleDebug assembleRelease lintRelease
```

`app/build/outputs/apk/release/app-release-unsigned.apk` is unsigned. Sign with your private key:

```sh
python3 tools/sign_release.py --keystore /private/voodoo-release.jks \
  --credentials /private/signing.json --out Voodoo-Browser.apk
```

The debug variant has application ID `com.ether4o4.voodoo.dev` so CI/debug signing does not conflict with the signed release. CI produces a development APK and an unsigned release APK; neither should be mistaken for a release signed with your private update key.

## Validation and current limits

See [the current bridge review](docs/BRIDGE-REVIEW.md) for executed checks and remaining acceptance work. The JavaScript suite covers lyric protection, backups, writing UI startup, script URL rules, helpers, editor persistence, AI-to-draft behavior, and workspace pin validation and flows. Native checks cover script URL boundaries and HTTPS API endpoints. See [docs/TESTING.md](docs/TESTING.md).

This is an **initial personal-use alpha**, not a production-audited browser. No physical-device or emulator UI run, real provider-key request, or live Suno generation has been completed in this environment. The phone's USB connection is not exposed to the build environment. Some identity providers disallow embedded-browser sign-in. Website camera/microphone permissions, geolocation, password-manager integration, external-page blob downloads, and full browser-extension compatibility are not implemented. Normal HTTPS downloads and native Workbench/script/backup exports are implemented.

All AI adapters are text chat adapters; “custom endpoint” supports the three implemented wire formats, not every possible proprietary API. Chat-app subscriptions are separate from provider API access/billing.

## Artwork and bundled assets

See [docs/ARTWORK.md](docs/ARTWORK.md) for the artwork treatment, palette, and asset provenance. Three.js is bundled locally under its MIT license; geographic land shapes come from public-domain Natural Earth data. The homepage does not fetch a remote script, map, or favicon service.

## Phone browser bridge

The reviewed bridge adds explicit site permissions, structured controls and an OAuth-protected MCP relay. See [phone setup](docs/CHATGPT-BROWSER-BRIDGE.md) and [relay deployment](bridge-server/README.md). A deployed HTTPS relay and a compatible MCP client are required; uploading this source does not activate the connection.
