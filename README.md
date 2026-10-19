# Voodoo Browser

A personal Android browser with built-in AI chat, an editable userscript workspace, a music workbench, and a completion-triggered Suno loop.

The app is new code built around **Android System WebView**, the Chromium-based web engine supplied by Android. It is not a patched Firefox APK or a new rendering engine. The homepage banner reads **“Voodoo browser”**; a transparent cutout of the supplied embroidered globe sits above it. The interface uses charcoal gray with muted retrowave accents.

## What is built in

- **Recent websites:** the homepage shows the six most recently visited sites, with titles and locally captured favicons when available. A compact **Shortcuts** button immediately left of Home opens Suno, AI & scripts, Workbench, and GitHub.
- **Workspace pins:** a persistent pin bar with muted cyan, pink, green, and gold groups, saved favicons when available, and custom workspace names. Open a whole workspace, move pins between groups, or unpin while keeping the bookmark.
- **Plush globe:** a bundled Three.js globe with cloth bump mapping, stitched land patches, orbiting all-caps lettering, and pin insertion animation. Drag to rotate, select a pin, or place it on the globe. Automatic animation is capped at 30 fps, direct dragging at 60 fps. Rendering stops entirely while idle, offscreen, or in the background; reduced-motion preferences are respected. A static cutout and the pin list remain usable if WebGL is unavailable.
- **AI chat:** Venice, OpenAI, OpenRouter, Groq, Anthropic, Gemini, and custom HTTPS API endpoints. Each preset saves its own key and model. OpenAI-compatible, Anthropic Messages, and Gemini GenerateContent adapters are implemented. Model lists load from the configured provider.
- **AI-assisted scripts:** complete generated userscripts appear in the draft editor. Review, save, enable, or run once on the target website. Drafts and recent draft history save locally.
- **Userscript workspace:** import/export `.user.js`; per-script switches; `@match`, `@exclude-match`, `@run-at`; local GM value/style helpers. This is a supported subset of Tampermonkey behavior, not the Tampermonkey extension or a full extension platform.
- **Writing Workbench:** lyric checks, protected lines, presets, saved versions, attempt notes, and JSON backups. Send a selected draft to Suno explicitly.
- **Non-AI completion loop:** learn Create/loading/completed indicators, confirm completion, wait a random delay, and start another generation. Has Stop and an optional run cap. It never starts itself.
- **Browser tools:** eight tabs (saved background pages load only when selected), bookmarks, site notes, reader view, clean-link copy, find, text zoom, desktop mode, share, PDF printing, HTTPS downloads, file selection, and a keep-screen-awake switch.
- **Persistence:** settings, scripts, drafts, provider keys and the browsing profile remain when a newer APK with the same application ID/signing key is installed over the existing app.

## Mobile fit and performance

The 0.2.1 mobile pass contains narrow-screen grid sizing, 48 px touch targets for small action controls, scrolling dialogs that fit the available viewport, and deferred restoration of background tabs. The globe uses a demand-driven renderer with capped pixel density and animation rates; paused, unchanged frames do not keep a render loop running. These are implemented improvements, not measured battery or frame-rate benchmarks. Device testing is still required for keyboard behavior, Android display/font scaling, WebView rendering, and long sessions on the owner's phone.

The 0.2.3 interface pass makes search easier to reach, uses plain section names, groups browser tools, and hides the empty pin bar. Pink, seafoam, cyan, and gold distinguish key headings, saved sites, tabs, and labels while body text stays neutral. The launcher uses the transparent homepage PNG directly and includes Samsung's icon-frame opt-out metadata. Samsung launcher/theme behavior still needs confirmation on the owner's phone; the app does not substitute a filled adaptive-icon tile.

## First use

1. Extract the installer ZIP in the phone's file manager and tap the signed APK. Install over the existing Voodoo app on Android 10 or newer. Keep Android System WebView updated.
2. Open **Shortcuts → AI & scripts → API keys**, choose a provider, enter its API key, load or type a text/code model ID, and save. Shortcuts is the grid button immediately left of Home.
3. Open a website, then **Shortcuts → AI & scripts → AI chat**. Describe the script. Page text and element labels are shared only when you select the page-context checkbox. The current script and chat are sent to the selected provider.
4. Open **Scripts** to review the generated draft. Use **Run once on target** to try it, or save it with automatic execution enabled for matching sites. Reload a website to apply automatic changes or remove a previous script's timers/DOM changes.

The supplied artwork is bundled. **Settings → Homepage image** lets you choose another image or restore the Voodoo cutout. Pin a website using **Tools → Bookmarks & notes → Pin / save bookmark**, **Pin a site** on the homepage, or **＋** in the browser pin bar once it is visible. Long-press a bar pin to move or unpin it. The homepage adds editing, workspace naming, and **Place on globe**. Positions are personal layout choices; the app does not infer your location or route traffic through the selected point. There is no built-in VPN or proxy service.

Recent websites start filling as you browse after installing 0.2.4. Only successful external HTTPS visits are recorded, with one entry per host and 24 sites retained locally. The homepage shows the newest six. Internal Voodoo pages and failed loads are excluded; recent visits are not included in exported tool backups.

## Suno loop

Open Suno and sign in. The 4GA panel contains the loop. Start one generation manually, then teach it the Create button, a loading indicator, and a completed indicator. Start the loop while a generation is loading. It requires loading to clear **and** a new completed result; then it waits within your min/max delay and clicks Create once. Keep the Suno tab visible. Tab changes, app backgrounding, timeouts, and recognized errors stop the loop. Randomness applies to the delay, not the prompt.

The loop uses Suno's normal generation credits. Site markup or login behavior may change; the live signed-in Suno flow has not been tested on a physical phone in this build.

## Keys, scripts, and updates

API keys are encrypted with Android Keystore and used by native HTTPS networking. Website JavaScript cannot read them. Keys are excluded from exports and Android backup/transfer. Redirects are rejected before forwarding credentials. Changing an API base URL requires re-entering its key.

Userscripts run in the website's JavaScript context, in the top frame. GM values use that site's local storage; they are not a secret vault. Native origin checks scope the privileged workspace bridge to the app's internal HTTPS origin. Internal pages are excluded from user-script execution. No third-party script store, remote `@require`, or privileged `GM_xmlhttpRequest` implementation is included.

The **private signing recovery archive supplied separately** is needed to sign future release updates. Never commit it, its password, provider keys, or personal backups here. Increase `versionCode` for updates and keep `com.ether4o4.voodoo` and the signing key unchanged. Engine updates come through Android System WebView; app/tool updates come through new Voodoo APKs. There is no automatic app updater in this version. Uninstalling or clearing app data deletes the local profile.

## Build

Requires JDK 17 and Android SDK platform 36/build tools. Gradle 8.13 and Android Gradle Plugin 8.13.2 are pinned.

```sh
npm ci
npm run build:web
npm test
./gradlew assembleRelease lintRelease
```

`app/build/outputs/apk/release/app-release-unsigned.apk` is unsigned. Sign with your private key:

```sh
python3 tools/sign_release.py --keystore /private/voodoo-release.jks \
  --credentials /private/signing.json --out Voodoo-Browser.apk
```

The debug variant has application ID `com.ether4o4.voodoo.dev` so CI/debug signing does not conflict with the signed release. CI produces a development APK and an unsigned release APK; neither should be mistaken for a release signed with your private update key.

## Validation and current limits

The Android debug/release builds and Android lint are checked locally. The JavaScript suite covers lyric protection, backups, the completion loop, script URL rules, helpers, editor persistence, AI-to-draft behavior, and workspace pin validation and flows. Native checks cover script URL boundaries and HTTPS API endpoints. See [docs/TESTING.md](docs/TESTING.md).

This is an **initial personal-use alpha**, not a production-audited browser. No physical-device or emulator UI run, real provider-key request, or live Suno generation has been completed in this environment. The phone's USB connection is not exposed to the build environment. Some identity providers disallow embedded-browser sign-in. Camera, microphone, geolocation, password-manager integration, external-page blob downloads, and full browser-extension compatibility are not implemented. Normal HTTPS downloads and native Workbench/script/backup exports are implemented.

All AI adapters are text chat adapters; “custom endpoint” supports the three implemented wire formats, not every possible proprietary API. Chat-app subscriptions are separate from provider API access/billing.

## Artwork and bundled assets

See [docs/ARTWORK.md](docs/ARTWORK.md) for the artwork treatment, palette, and asset provenance. Three.js is bundled locally under its MIT license; geographic land shapes come from public-domain Natural Earth data. The homepage does not fetch a remote script, map, or favicon service.
